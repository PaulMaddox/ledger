package com.pam.ledger.web

import com.pam.ledger.AccountId
import com.pam.ledger.ChainLog
import com.pam.ledger.DerivedIndexUser
import com.pam.ledger.DerivedStorage
import com.pam.ledger.FixedLog
import com.pam.ledger.HashIndex
import com.pam.ledger.KeyIndex
import com.pam.ledger.Ledger
import com.pam.ledger.PagedFile
import com.pam.ledger.RecoveryListener
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
class ReadModel private constructor() : AutoCloseable {

    /** Tail [ledger] from its first entry. Prefer [Recovering] when opening a ledger with history. */
    constructor(ledger: Ledger) : this() {
        useStorage(ledger.derived, ledger.indexFlushSize)
        attach(ledger)
    }

    /**
     * Builds the read model WHILE the ledger recovers, instead of reading the whole journal
     * a second time afterwards:
     *
     *     val recovering = ReadModel.Recovering()
     *     val ledger = Ledger.open(config, recovering)
     *     val readModel = recovering.attach(ledger)
     *
     * Recovery hands over only entries that passed every check. If the ledger fails to open,
     * drop this object: it was never attached, so nothing can read what it indexed.
     */
    internal class Recovering : RecoveryListener, DerivedIndexUser {
        private val model = ReadModel()
        private var indexed = 0L

        override fun useStorage(storage: DerivedStorage, indexFlushSize: Int) = model.useStorage(storage, indexFlushSize)

        override fun recovered(entry: SealedEntry) {
            model.apply(entry)
            indexed = entry.entry.seq + 1
        }

        /** Hand over the indexes built so far; the returned model tails [ledger] from where recovery stopped. */
        fun attach(ledger: Ledger): ReadModel {
            check(indexed <= ledger.head().size) { "recovered entries do not belong to this ledger" }
            model.lock.write { model.next = indexed } // the lock publishes everything recovery wrote to other threads
            model.attach(ledger)
            return model
        }
    }

    private lateinit var ledger: Ledger

    // ---- Histories and the bet index live in the ledger's derived files, not on the heap. ----
    private lateinit var chainFile: PagedFile
    private lateinit var chains: ChainLog
    // Hash of a bet ID -> sequence numbers of its placement (and settlement) entries. As in the
    // engine's key index, a match is a candidate until the entry's own betId agrees.
    private lateinit var placements: HashIndex
    private lateinit var settlements: HashIndex

    private fun useStorage(storage: DerivedStorage, indexFlushSize: Int) {
        chainFile = storage.create("read-model-chains")
        chains = ChainLog(FixedLog(chainFile, 8))
        // The placement index has a filter so a settlement for a bet this model never indexed (one
        // placed without a market) is recognised from memory. Settlements are only looked up for
        // bets that exist, so they need none.
        placements = HashIndex(storage, "bets-placed", indexFlushSize, 10).apply { mayWait = true }
        settlements = HashIndex(storage, "bets-settled", indexFlushSize, 0).apply { mayWait = true }
    }

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

    /** Every query: refuse to answer from a model that may hold part of an entry twice (see [broken]). */
    private inline fun <T> guarded(block: () -> T): T {
        broken?.let { throw IllegalStateException("read model is unusable after an indexing failure", it) }
        return lock.read(block)
    }
    // Read without the lock by catchUp's fast path; written only under the write lock.
    @Volatile private var next = 0L
    // In memory a history is only its length and a few file positions; see ChainLog.
    private val byPlayer = HashMap<String, ChainLog.Chain>()
    // Placement entries only, so a page of bets never walks deposits and withdrawals.
    private val placementsByPlayer = HashMap<String, ChainLog.Chain>()
    // Sorted, so a search scans in a stable order without copying or sorting per request.
    private val playerIds = TreeSet<String>()
    // Every entry that posts to a player account, so an audit reads only that account's history.
    private val seqsByAccount = HashMap<String, ChainLog.Chain>()
    private val fixtureIndexes = HashMap<String, FixtureIndex>()
    // Settlements of bets that were not open when they arrived: an unknown bet, or a bet settled
    // again under a nonstandard key. Rare. Their effect on the fixture summaries needs the bet's
    // entries, which recovery cannot read, so it is worked out when the summaries are next asked for.
    private class LateSettlement(val betId: String, val seq: Long, val outcome: String)
    private val lateSettlements = ArrayList<LateSettlement>()
    // Decoded views are kept only for OPEN bets. Everything else about a bet is in the index files.
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
    // Set if indexing an entry failed part-way. Counters and history files may then hold part of
    // that entry, and indexing it again would record it twice, so the model is never used again.
    @Volatile private var broken: Throwable? = null
    private var poller: Thread? = null

    private fun attach(ledger: Ledger) {
        this.ledger = ledger
        poller = Thread({
            while (running) {
                runCatching { catchUp() }
                try { Thread.sleep(100) } catch (e: InterruptedException) { break } // close() interrupts the sleep
            }
        }, "read-model").apply { isDaemon = true; start() }
    }

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
        broken?.let { throw IllegalStateException("read model is unusable after an indexing failure", it) }
        if (next >= maxOf(ledger.head().size, atLeast)) return
        lock.write {
            broken?.let { throw IllegalStateException("read model is unusable after an indexing failure", it) }
            val size = maxOf(ledger.head().size, atLeast)
            while (next < size) {
                val sealed = ledger.entry(next) // a failure here has changed nothing, so it may be retried
                try {
                    apply(sealed)
                } catch (t: Throwable) {
                    broken = t
                    // The model shares the ledger's derived storage. A write that fails there means the
                    // derived files cannot be relied on, so the ledger fail-stops as it does for its own.
                    if (running) ledger.derived.failed(t)
                    throw t
                }
                next++
            }
            // Keep the set-aside list short: with the ledger at hand it can be settled up straight away.
            if (lateSettlements.isNotEmpty()) resolveLateSettlements()
        }
    }

    /**
     * Index one entry. Uses nothing but the entry itself: it must not read other entries
     * from the ledger, because it also runs during recovery, before the ledger exists.
     */
    private fun apply(sealed: SealedEntry) {
        val e = sealed.entry
        kindCounts.merge(e.kind, 1, Long::plus)
        recent.addLast(e.seq)
        if (recent.size > 60) recent.removeFirst()
        e.metadata["playerId"]?.let { player ->
            chains.append(byPlayer.getOrPut(player) { playerIds.add(player); ChainLog.Chain() }, e.seq)
            if (e.kind == "BET_PLACED") chains.append(placementsByPlayer.getOrPut(player) { ChainLog.Chain() }, e.seq)
        }
        // Validation rejects a transaction naming an account twice, so each entry is recorded once per account.
        for (p in e.postings) if (p.account.value.startsWith(PLAYER_PREFIX)) {
            chains.append(seqsByAccount.getOrPut(p.account.value) { ChainLog.Chain() }, e.seq)
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
                    placements.put(KeyIndex.hashOf(betId), e.seq)
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
                settlements.put(KeyIndex.hashOf(betId), e.seq)
                val outcome = e.kind.removePrefix("BET_")
                val open = openViews.remove(betId)
                if (open == null) {
                    // Not an open bet. If it may have been placed at all, settle up later; see lateSettlements.
                    if (placements.mightContain(KeyIndex.hashOf(betId))) lateSettlements.add(LateSettlement(betId, e.seq, outcome))
                } else {
                    fixtureIndexes[open.fixtureId]?.let { index ->
                        index.open--
                        index.outcomes.getOrPut(open.selection) { HashMap() }.merge(outcome, 1, Int::plus)
                    }
                    openBetCount--
                    val amount = BigInteger.valueOf(open.stake)
                    openStakes = openStakes.subtract(amount)
                    exposure = exposure.subtract(openExposure(open))
                    openBetsByPlayer[open.playerId]?.remove(betId)
                    openBetsByFixture[open.fixtureId]?.remove(betId)
                    if (openBetsByPlayer[open.playerId]?.isEmpty() == true) openBetsByPlayer.remove(open.playerId)
                    if (openBetsByFixture[open.fixtureId]?.isEmpty() == true) openBetsByFixture.remove(open.fixtureId)
                }
            }
        }
    }

    /** Where a bet was placed and, if it has been, settled. */
    private class BetRow(val placedSeq: Long, val settledSeq: Long)

    /**
     * Find a bet by ID as it stood just before entry [before]. The index offers candidate
     * entries by hash; each is read from the ledger and counts only if its own betId matches.
     * The newest placement defines the bet, and the newest settlement after it settles it.
     */
    private fun row(betId: String, before: Long = Long.MAX_VALUE, knownPlacement: Long = -1): BetRow? {
        val hash = KeyIndex.hashOf(betId)
        // A caller that already holds one of the bet's placements only needs to learn of a newer
        // one, and a settlement always follows its placement, so older index files are not searched.
        var placed = knownPlacement
        placements.anyCandidate(hash, knownPlacement + 1) { seq ->
            if (seq > placed && seq < before && ledger.entry(seq).entry.metadata["betId"] == betId) placed = seq
            false
        }
        if (placed < 0) return null
        var settled = -1L
        settlements.anyCandidate(hash, placed + 1) { seq ->
            if (seq > settled && seq > placed && seq < before && ledger.entry(seq).entry.metadata["betId"] == betId) settled = seq
            false
        }
        return BetRow(placed, settled)
    }

    /** Apply the settlements set aside by [apply] to the fixture summaries. Needs the ledger; caller holds the write lock. */
    private fun resolveLateSettlements() {
        for (late in lateSettlements) {
            val before = row(late.betId, late.seq) ?: continue // no such bet when this entry was committed
            val parts = ledger.entry(before.placedSeq).entry.metadata.getValue("selection").split("|")
            val outcomes = fixtureIndexes.getValue(parts[0]).outcomes.getOrPut(parts[1]) { HashMap() }
            if (before.settledSeq >= 0) {
                // Settled again: the newest outcome replaces the one counted before.
                val previous = ledger.entry(before.settledSeq).entry.kind.removePrefix("BET_")
                outcomes.computeIfPresent(previous) { _, count -> if (count > 1) count - 1 else null }
            }
            outcomes.merge(late.outcome, 1, Int::plus)
        }
        lateSettlements.clear()
    }

    private fun openExposure(bet: BetView): BigInteger {
        val stake = BigInteger.valueOf(bet.stake)
        // Aggregating generic wallet history must not introduce a Long payout overflow into catch-up.
        return stake.multiply(BigInteger.valueOf(bet.oddsHundredths.toLong()))
            .divide(BigInteger.valueOf(100)).subtract(stake)
    }

    private fun view(history: BetRow): BetView {
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

    fun seqsForPlayer(player: String, limit: Int = Int.MAX_VALUE): List<Long> = guarded {
        require(limit >= 0) { "history limit must be non-negative" }
        byPlayer[player]?.let { chains.tail(it, limit).asList() } ?: emptyList()
    }
    fun historyForPlayer(player: String, limit: Int): HistoryPage = guarded {
        require(limit >= 0) { "history limit must be non-negative" }
        val history = byPlayer[player]
        HistoryPage(Math.toIntExact(history?.count ?: 0), history?.let { chains.tail(it, limit).asList() } ?: emptyList())
    }
    /** The player's most recent [limit] bets, oldest first. Reads placements only, however long the rest of the history is. */
    fun betsForPlayer(player: String, limit: Int = Int.MAX_VALUE): List<BetView> = guarded {
        require(limit >= 0) { "bet limit must be non-negative" }
        val placed = placementsByPlayer[player] ?: return@guarded emptyList()
        val result = ArrayList<BetView>()
        if (limit > 0) chains.walkBack(placed) { seq ->
            val placement = ledger.entry(seq).entry
            // A well-formed placement is itself indexed, so the search can start from it. One without
            // a market is not; it has a view only if the same bet ID was also placed properly elsewhere.
            val indexed = placement.metadata["selection"]?.split("|")?.size == 3
            placement.metadata["betId"]?.let { row(it, knownPlacement = if (indexed) seq else -1) }?.let { result.add(view(it)) }
            result.size < limit
        }
        result.asReversed()
    }
    /** Every bet ever placed, in placement order. Reads each bet's entries: for tests and diagnostics, not for request paths. */
    fun allBets(): List<BetView> = guarded {
        val ids = LinkedHashSet<String>()
        val seqs = ArrayList<Long>()
        placements.forEach { _, seq -> seqs.add(seq) }
        for (seq in seqs.distinct().sorted()) ledger.entry(seq).entry.metadata["betId"]?.let(ids::add)
        ids.mapNotNull { row(it) }.sortedBy { it.placedSeq }.map(::view)
    }
    fun openBetsForPlayer(player: String): List<BetView> = guarded {
        openBetsByPlayer[player].orEmpty().mapNotNull(openViews::get)
    }
    fun bet(betId: String): BetView? = guarded { row(betId)?.let(::view) }
    fun openBets(): List<BetView> = guarded {
        openViews.values.sortedWith { a, b -> a.placedSeq.compareTo(b.placedSeq) }
    }
    fun openBetsOn(fixtureId: String): List<BetView> = guarded {
        openBetsByFixture[fixtureId].orEmpty().mapNotNull(openViews::get)
    }
    fun recentSeqs(): List<Long> = guarded { recent.toList().asReversed() }
    fun players(): Set<String> = guarded { byPlayer.keys.toSet() }
    /** How many players have any entry. Constant time: dashboards poll this. */
    fun playerCount(): Int = guarded { playerIds.size }
    /** Visit every player ID in sorted order without copying the set. [visit] must be quick: it runs under the read lock. */
    fun forEachPlayer(visit: (String) -> Unit) = guarded { playerIds.forEach(visit) }
    /**
     * Sequence numbers of every indexed entry posting to player account [account], oldest first.
     * A convenience for audits: the caller must still recompute from the entries and check
     * the count against the ledger's own account version, never trust this list alone.
     */
    fun seqsForAccount(account: AccountId): LongArray = guarded {
        seqsByAccount[account.value]?.let { chains.tail(it) } ?: LongArray(0)
    }
    /** One summary per fixture that has ever had a bet. Size depends on fixtures and selections, not on bets. */
    fun fixtureSummaries(): Map<String, FixtureSummary> {
        // Settlements set aside while indexing are settled up first; that changes the index, so it needs the write lock.
        if (guarded { lateSettlements.isNotEmpty() }) lock.write { resolveLateSettlements() }
        return guarded { summaries() }
    }

    private fun summaries(): Map<String, FixtureSummary> = run {
        fixtureIndexes.mapValues { (_, index) ->
            FixtureSummary(LinkedHashMap(index.odds), index.outcomes.mapValues { it.value.keys.toSet() }, index.open)
        }
    }
    /** Exact aggregates for one indexed prefix; polling never scans or sorts the open bets. */
    fun totals(): Totals = guarded {
        Totals(next, deposits, withdrawals, staked, returned, HashMap(kindCounts), openBetCount, openStakes, exposure)
    }

    fun balance(account: AccountId) = ledger.balance(account)

    override fun close() {
        running = false
        poller?.let {
            it.interrupt()
            it.join(5_000)
            check(!it.isAlive) { "read model did not stop" }
        }
        // Stop index maintenance and remove this model's derived files; the ledger's own are untouched.
        if (::placements.isInitialized) {
            placements.close()
            settlements.close()
            runCatching { chainFile.delete() }
        }
    }

    private companion object {
        const val PLAYER_PREFIX = "player:"
    }
}
