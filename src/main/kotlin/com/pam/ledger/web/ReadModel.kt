package com.pam.ledger.web

import com.pam.ledger.AccountId
import com.pam.ledger.Ledger
import com.pam.ledger.SealedEntry
import com.pam.ledger.SportsbookWallet.Accounts
import java.math.BigInteger

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

    private val lock = Any()
    private var next = 0L
    private class BetHistory(val placedSeq: Long, var settledSeq: Long = -1)
    private val byPlayer = HashMap<String, LongHistory>()
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

    /** Process every entry committed so far. Called by the poller, and directly when a caller needs to be current. */
    fun catchUp() = synchronized(lock) {
        val size = ledger.head().size
        while (next < size) {
            apply(ledger.entry(next))
            next++
        }
    }

    private fun apply(sealed: SealedEntry) {
        val e = sealed.entry
        kindCounts.merge(e.kind, 1, Long::plus)
        recent.addLast(e.seq)
        if (recent.size > 60) recent.removeFirst()
        e.metadata["playerId"]?.let { byPlayer.getOrPut(it) { LongHistory() }.add(e.seq) }

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
                    openViews.remove(betId)?.let {
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

    fun seqsForPlayer(player: String, limit: Int = Int.MAX_VALUE): List<Long> = synchronized(lock) {
        require(limit >= 0) { "history limit must be non-negative" }
        byPlayer[player]?.tail(limit) ?: emptyList()
    }
    fun historyForPlayer(player: String, limit: Int): HistoryPage = synchronized(lock) {
        require(limit >= 0) { "history limit must be non-negative" }
        val history = byPlayer[player]
        HistoryPage(history?.size ?: 0, history?.tail(limit) ?: emptyList())
    }
    fun betsForPlayer(player: String, limit: Int = Int.MAX_VALUE): List<BetView> = synchronized(lock) {
        require(limit >= 0) { "bet limit must be non-negative" }
        val history = byPlayer[player] ?: return@synchronized emptyList()
        val result = ArrayList<BetView>()
        var index = history.size - 1
        while (index >= 0 && result.size < limit) {
            val entry = ledger.entry(history[index--]).entry
            if (entry.kind == "BET_PLACED") entry.metadata["betId"]?.let(bets::get)?.let { result.add(view(it)) }
        }
        result.asReversed()
    }
    fun allBets(): List<BetView> = synchronized(lock) { bets.values.map(::view) }
    fun openBetsForPlayer(player: String): List<BetView> = synchronized(lock) {
        openBetsByPlayer[player].orEmpty().mapNotNull(openViews::get)
    }
    fun bet(betId: String): BetView? = synchronized(lock) { bets[betId]?.let(::view) }
    fun openBets(): List<BetView> = synchronized(lock) {
        openViews.values.sortedWith { a, b -> a.placedSeq.compareTo(b.placedSeq) }
    }
    fun openBetsOn(fixtureId: String): List<BetView> = synchronized(lock) {
        openBetsByFixture[fixtureId].orEmpty().mapNotNull(openViews::get)
    }
    fun recentSeqs(): List<Long> = synchronized(lock) { recent.toList().asReversed() }
    fun players(): Set<String> = synchronized(lock) { byPlayer.keys.toSet() }
    /** Exact aggregates for one indexed prefix; polling never scans or sorts the open bets. */
    fun totals(): Totals = synchronized(lock) {
        Totals(next, deposits, withdrawals, staked, returned, HashMap(kindCounts), openBetCount, openStakes, exposure)
    }

    fun balance(account: AccountId) = ledger.balance(account)

    override fun close() {
        running = false
        poller.interrupt()
        poller.join(5_000)
        check(!poller.isAlive) { "read model did not stop" }
    }
}
