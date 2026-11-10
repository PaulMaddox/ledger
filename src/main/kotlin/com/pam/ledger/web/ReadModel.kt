package com.pam.ledger.web

import com.pam.ledger.AccountId
import com.pam.ledger.Ledger
import com.pam.ledger.SealedEntry
import com.pam.ledger.SportsbookWallet.Accounts
import java.math.BigInteger
import java.util.TreeSet
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/*
 * ============================================================================
 *  READ MODEL: what the dashboards query
 * ============================================================================
 *
 *  The ledger itself is optimised for one thing: committing transactions
 *  correctly. Questions like "all of alice's transactions" or "every open bet on
 *  fixture F3" would mean scanning the whole journal, so this class TAILS the
 *  ledger (reads each new committed entry exactly once, in order) and keeps
 *  indexes for the web app. It is the in-process version of the change-data-capture
 *  read models in the cloud architecture.
 *
 *  It never writes to the ledger and is never consulted for money decisions:
 *  balances shown to users come from the ledger itself, and every bet action
 *  goes through SportsbookWallet. If this model lags or is wrong, the worst case
 *  is a stale dashboard, never a wrong balance.
 *
 *  LOCKING: queries share a read lock, so they never wait for each other; only
 *  the tailer takes the write lock, and only while it has entries to apply. This
 *  lock guards the read model's own indexes. It is never held by, and never
 *  blocks, the ledger's writer thread.
 */
class ReadModel(private val ledger: Ledger) : AutoCloseable {

    /** A bet as reconstructed from its placement entry and (optionally) its settlement entry. */
    data class BetView(
        val betId: String,
        val playerId: String,
        val fixtureId: String,
        val selection: String,
        val oddsHundredths: Int,
        val stake: Long,
        val placedSeq: Long,
        val placedAtMicros: Long,
        val settledSeq: Long? = null,
        val outcome: String? = null,
        val payout: Long? = null,
    ) {
        val isOpen get() = settledSeq == null
        /** What the house would pay out if this bet won: stake x odds, rounded down to whole minor units. */
        val potentialPayout get() = payout(stake, oddsHundredths)
    }

    data class Totals(
        val entries: Long, val deposits: BigInteger, val withdrawals: BigInteger,
        val staked: BigInteger, val returned: BigInteger, val kindCounts: Map<String, Long>,
        val openBets: Int, val openStakes: BigInteger, val exposure: BigInteger,
    )
    data class HistoryPage(val entries: Int, val seqs: List<Long>)

    /**
     * What fixture restore needs to know about one fixture's bets, kept up to date
     * as entries arrive so a restart never has to rebuild every bet ever placed.
     *
     * @property odds the price last seen for each selection
     * @property outcomes for each selection, the settlement outcomes recorded against it
     * @property openBets bets on this fixture not yet settled
     */
    data class FixtureSummary(val odds: Map<String, Int>, val outcomes: Map<String, Set<String>>, val openBets: Int)

    private class FixtureIndex {
        val odds = LinkedHashMap<String, Int>()
        // selection -> outcome -> number of bets, so a re-settled legacy bet can move between outcomes.
        val outcomes = HashMap<String, HashMap<String, Int>>()
        var open = 0
    }

    private val lock = ReentrantReadWriteLock()
    // Read without the lock by catchUp's fast path; written only under the write lock.
    @Volatile private var next = 0L
    private class BetHistory(val placedSeq: Long, var settledSeq: Long = -1)
    private val byPlayer = HashMap<String, LongHistory>()
    // Placement entries only, so a page of bets never walks deposits and withdrawals.
    private val placementsByPlayer = HashMap<String, LongHistory>()
    // Sorted, so a search scans in a stable order without copying or sorting per request.
    private val playerIds = TreeSet<String>()
    // Every entry that posts to a player account, so an audit reads only that account's history.
    private val seqsByAccount = HashMap<String, LongHistory>()
    private val fixtureIndexes = HashMap<String, FixtureIndex>()
    private val bets = LinkedHashMap<String, BetHistory>()
    private val openViews = HashMap<String, BetView>()
    private val openBetsByPlayer = HashMap<String, MutableSet<String>>()
    private val openBetsByFixture = HashMap<String, MutableSet<String>>()
    private val kindCounts = HashMap<String, Long>()
    private var deposits = BigInteger.ZERO
    private var withdrawals = BigInteger.ZERO
    private var staked = BigInteger.ZERO
    private var returned = BigInteger.ZERO
    private var openBetCount = 0
    private var openStakes = BigInteger.ZERO
    private var exposure = BigInteger.ZERO
    private val recent = ArrayDeque<Long>()

    @Volatile private var running = true
    private val poller = Thread({
        while (running) {
            runCatching { catchUp() }
            try { Thread.sleep(100) } catch (e: InterruptedException) { break } // close() interrupts the sleep
        }
    }, "read-model").apply { isDaemon = true; start() }

    /**
     * Process every entry committed so far. Called by the poller, and directly when a caller needs to be current.
     *
     * [atLeast] is for a caller holding an account state: the writer publishes entries, then
     * balances, then the head, so a state can name an entry the head does not cover yet.
     * Such a caller passes its state's lastSeq + 1 and the tailer reads that far. Those
     * entries are already durable; only the head summarising them is still to come.
     */
    fun catchUp(atLeast: Long = 0) {
        // Already current: do not take the write lock, or idle readers would queue behind each other here.
        if (next >= maxOf(ledger.head().size, atLeast)) return
        lock.write {
            val size = maxOf(ledger.head().size, atLeast)
            while (next < size) {
                apply(ledger.entry(next))
                next++
            }
        }
    }

    private fun apply(sealed: SealedEntry) {
        val e = sealed.entry
        kindCounts.merge(e.kind, 1, Long::plus)
        recent.addLast(e.seq)
        if (recent.size > 60) recent.removeFirst()
        e.metadata["playerId"]?.let { player ->
            byPlayer.getOrPut(player) { playerIds.add(player); LongHistory() }.add(e.seq)
            if (e.kind == "BET_PLACED") placementsByPlayer.getOrPut(player) { LongHistory() }.add(e.seq)
        }
        // Validation rejects a transaction naming an account twice, so each entry is recorded once per account.
        for (p in e.postings) if (p.account.value.startsWith(PLAYER_PREFIX)) {
            seqsByAccount.getOrPut(p.account.value) { LongHistory() }.add(e.seq)
        }

        fun cashDelta(player: String) = e.postings.firstOrNull { it.account == Accounts.cash(player, it.currency) }?.amount ?: 0L

        when (e.kind) {
            "DEPOSIT" -> deposits = deposits.add(BigInteger.valueOf(e.postings.first { it.amount > 0 }.amount))
            "WITHDRAWAL" -> withdrawals = withdrawals.add(BigInteger.valueOf(e.postings.first { it.amount > 0 }.amount))
            "BET_PLACED" -> {
                val player = e.metadata.getValue("playerId")
                val stake = e.postings.first { it.amount > 0 }.amount
                staked = staked.add(BigInteger.valueOf(stake))
                // The web app stores "fixture|selection|odds" in the placement's selection metadata.
                val parts = e.metadata["selection"]?.split("|")
                if (parts != null && parts.size == 3) {
                    val betId = e.metadata.getValue("betId")
                    val bet = BetView(
                        betId = e.metadata.getValue("betId"), playerId = player, fixtureId = parts[0],
                        selection = parts[1], oddsHundredths = parts[2].toInt(), stake = stake,
                        placedSeq = e.seq, placedAtMicros = e.timestampMicros,
                    )
                    bets[betId] = BetHistory(e.seq)
                    openViews[betId] = bet
                    openBetsByPlayer.getOrPut(player) { LinkedHashSet() }.add(betId)
                    openBetsByFixture.getOrPut(parts[0]) { LinkedHashSet() }.add(betId)
                    fixtureIndexes.getOrPut(parts[0]) { FixtureIndex() }.let {
                        it.odds[parts[1]] = bet.oddsHundredths
                        it.open++
                    }
                    openBetCount++
                    val amount = BigInteger.valueOf(stake)
                    openStakes = openStakes.add(amount)
                    exposure = exposure.add(openExposure(bet))
                }
            }
            "BET_WON", "BET_LOST", "BET_VOIDED", "BET_CASHED_OUT" -> {
                val betId = e.metadata.getValue("betId")
                val payout = cashDelta(e.metadata.getValue("playerId"))
                returned = returned.add(BigInteger.valueOf(payout))
                bets[betId]?.let { history ->
                    recordOutcome(history, e.kind.removePrefix("BET_"))
                    openViews.remove(betId)?.let {
                        fixtureIndexes[it.fixtureId]?.let { index -> index.open-- }
                        openBetCount--
                        val amount = BigInteger.valueOf(it.stake)
                        openStakes = openStakes.subtract(amount)
                        exposure = exposure.subtract(openExposure(it))
                        openBetsByPlayer[it.playerId]?.remove(betId)
                        openBetsByFixture[it.fixtureId]?.remove(betId)
                        if (openBetsByPlayer[it.playerId]?.isEmpty() == true) openBetsByPlayer.remove(it.playerId)
                        if (openBetsByFixture[it.fixtureId]?.isEmpty() == true) openBetsByFixture.remove(it.fixtureId)
                    }
                    history.settledSeq = e.seq
                }
            }
        }
    }

    /** Count [outcome] against the bet's fixture and selection, replacing any outcome an earlier settlement recorded. */
    private fun recordOutcome(history: BetHistory, outcome: String) {
        val parts = ledger.entry(history.placedSeq).entry.metadata.getValue("selection").split("|")
        val outcomes = fixtureIndexes.getValue(parts[0]).outcomes.getOrPut(parts[1]) { HashMap() }
        if (history.settledSeq >= 0) {
            val previous = ledger.entry(history.settledSeq).entry.kind.removePrefix("BET_")
            outcomes.computeIfPresent(previous) { _, count -> if (count > 1) count - 1 else null }
        }
        outcomes.merge(outcome, 1, Int::plus)
    }

    private fun openExposure(bet: BetView): BigInteger {
        val stake = BigInteger.valueOf(bet.stake)
        // Aggregating generic wallet history must not introduce a Long payout overflow into catch-up.
        return stake.multiply(BigInteger.valueOf(bet.oddsHundredths.toLong()))
            .divide(BigInteger.valueOf(100)).subtract(stake)
    }

    private fun view(history: BetHistory): BetView {
        val placement = ledger.entry(history.placedSeq).entry
        val id = placement.metadata.getValue("betId")
        if (history.settledSeq < 0) return openViews.getValue(id)
        val parts = placement.metadata.getValue("selection").split("|")
        val settlement = ledger.entry(history.settledSeq).entry
        val player = placement.metadata.getValue("playerId")
        val payout = settlement.postings.firstOrNull {
            it.account == Accounts.cash(settlement.metadata.getValue("playerId"), it.currency)
        }?.amount ?: 0L
        return BetView(id, player, parts[0], parts[1], parts[2].toInt(),
            placement.postings.first { it.amount > 0 }.amount, placement.seq, placement.timestampMicros,
            settlement.seq, settlement.kind.removePrefix("BET_"), payout)
    }

    // ---- Queries (all return copies, safe to use after the lock is released) ----

    fun seqsForPlayer(player: String, limit: Int = Int.MAX_VALUE): List<Long> = lock.read {
        require(limit >= 0) { "history limit must be non-negative" }
        byPlayer[player]?.tail(limit) ?: emptyList()
    }
    fun historyForPlayer(player: String, limit: Int): HistoryPage = lock.read {
        require(limit >= 0) { "history limit must be non-negative" }
        val history = byPlayer[player]
        HistoryPage(history?.size ?: 0, history?.tail(limit) ?: emptyList())
    }
    /** The player's most recent [limit] bets, oldest first. Reads placements only, however long the rest of the history is. */
    fun betsForPlayer(player: String, limit: Int = Int.MAX_VALUE): List<BetView> = lock.read {
        require(limit >= 0) { "bet limit must be non-negative" }
        val placements = placementsByPlayer[player] ?: return@read emptyList()
        val result = ArrayList<BetView>()
        var index = placements.size - 1
        while (index >= 0 && result.size < limit) {
            // A placement without a well-formed selection has no view; skip it as the full-history walk did.
            ledger.entry(placements[index--]).entry.metadata["betId"]?.let(bets::get)?.let { result.add(view(it)) }
        }
        result.asReversed()
    }
    fun allBets(): List<BetView> = lock.read { bets.values.map(::view) }
    fun openBetsForPlayer(player: String): List<BetView> = lock.read {
        openBetsByPlayer[player].orEmpty().mapNotNull(openViews::get)
    }
    fun bet(betId: String): BetView? = lock.read { bets[betId]?.let(::view) }
    fun openBets(): List<BetView> = lock.read {
        openViews.values.sortedWith { a, b -> a.placedSeq.compareTo(b.placedSeq) }
    }
    fun openBetsOn(fixtureId: String): List<BetView> = lock.read {
        openBetsByFixture[fixtureId].orEmpty().mapNotNull(openViews::get)
    }
    fun recentSeqs(): List<Long> = lock.read { recent.toList().asReversed() }
    fun players(): Set<String> = lock.read { byPlayer.keys.toSet() }
    /** How many players have any entry. Constant time: dashboards poll this. */
    fun playerCount(): Int = lock.read { playerIds.size }
    /** Visit every player ID in sorted order without copying the set. [visit] must be quick: it runs under the read lock. */
    fun forEachPlayer(visit: (String) -> Unit) = lock.read { playerIds.forEach(visit) }
    /**
     * Sequence numbers of every indexed entry posting to player account [account], oldest first.
     * A convenience for audits: the caller must still recompute from the entries and check
     * the count against the ledger's own account version, never trust this list alone.
     */
    fun seqsForAccount(account: AccountId): LongArray = lock.read {
        val history = seqsByAccount[account.value] ?: return@read LongArray(0)
        LongArray(history.size) { history[it] }
    }
    /** One summary per fixture that has ever had a bet. Size depends on fixtures and selections, not on bets. */
    fun fixtureSummaries(): Map<String, FixtureSummary> = lock.read {
        fixtureIndexes.mapValues { (_, index) ->
            FixtureSummary(LinkedHashMap(index.odds), index.outcomes.mapValues { it.value.keys.toSet() }, index.open)
        }
    }
    /** Exact aggregates for one indexed prefix; polling never scans or sorts the open bets. */
    fun totals(): Totals = lock.read {
        Totals(next, deposits, withdrawals, staked, returned, HashMap(kindCounts), openBetCount, openStakes, exposure)
    }

    fun balance(account: AccountId) = ledger.balance(account)

    override fun close() {
        running = false
        poller.interrupt()
        poller.join(5_000)
        check(!poller.isAlive) { "read model did not stop" }
    }

    private companion object {
        const val PLAYER_PREFIX = "player:"
    }
}
