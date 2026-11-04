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

    /**
     * Open-bet aggregates for one fixture, all describing the same indexed prefix of the ledger
     * ([indexedEntries]). Immutable: later catch-up never changes a snapshot already returned.
     * [liability] has one entry per requested selection (zero when nothing is open on it).
     */
    data class FixtureSummary(
        val indexedEntries: Long, val openBets: Int, val staked: BigInteger, val liability: Map<String, BigInteger>,
    )

    /** Mutable running totals, owned by the read-model lock and never handed out. */
    private class OpenCounts {
        var bets = 0
        var stake = BigInteger.ZERO
        var liability = BigInteger.ZERO
    }

    private class FixtureOpen {
        val total = OpenCounts()
        val bySelection = HashMap<String, OpenCounts>()
    }

    private val lock = Any()
    private var next = 0L
    private val byPlayer = HashMap<String, MutableList<Long>>()
    private val bets = LinkedHashMap<String, BetView>()
    private val openBetsByPlayer = HashMap<String, MutableSet<String>>()
    private val openBetsByFixture = HashMap<String, MutableSet<String>>()
    private val openByFixture = HashMap<String, FixtureOpen>()
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
        e.metadata["playerId"]?.let { byPlayer.getOrPut(it) { ArrayList() }.add(e.seq) }

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
                    bets[betId] = bet
                    openBetsByPlayer.getOrPut(player) { LinkedHashSet() }.add(betId)
                    openBetsByFixture.getOrPut(parts[0]) { LinkedHashSet() }.add(betId)
                    openBetCount++
                    val amount = BigInteger.valueOf(stake)
                    openStakes = openStakes.add(amount)
                    exposure = exposure.add(openExposure(bet))
                    addOpen(bet)
                }
            }
            "BET_WON", "BET_LOST", "BET_VOIDED", "BET_CASHED_OUT" -> {
                val betId = e.metadata.getValue("betId")
                val payout = cashDelta(e.metadata.getValue("playerId"))
                returned = returned.add(BigInteger.valueOf(payout))
                bets[betId]?.let {
                    if (it.isOpen) {
                        openBetCount--
                        val amount = BigInteger.valueOf(it.stake)
                        openStakes = openStakes.subtract(amount)
                        exposure = exposure.subtract(openExposure(it))
                        removeOpen(it)
                    }
                    bets[betId] = it.copy(settledSeq = e.seq, outcome = e.kind.removePrefix("BET_"), payout = payout)
                    openBetsByPlayer[it.playerId]?.remove(betId)
                    openBetsByFixture[it.fixtureId]?.remove(betId)
                    if (openBetsByPlayer[it.playerId]?.isEmpty() == true) openBetsByPlayer.remove(it.playerId)
                    if (openBetsByFixture[it.fixtureId]?.isEmpty() == true) openBetsByFixture.remove(it.fixtureId)
                }
            }
        }
    }

    /**
     * Stake x odds rounded down, per bet. Equal to [BetView.potentialPayout] whenever that fits Long,
     * but aggregating generic wallet history must not introduce a Long payout overflow into catch-up.
     * Fixture liabilities sum these rounded per-bet payouts; they never round an aggregated stake.
     */
    private fun openPayout(bet: BetView): BigInteger =
        BigInteger.valueOf(bet.stake).multiply(BigInteger.valueOf(bet.oddsHundredths.toLong())).divide(BigInteger.valueOf(100))

    private fun openExposure(bet: BetView): BigInteger = openPayout(bet).subtract(BigInteger.valueOf(bet.stake))

    /** Called exactly once per console placement, as it is applied. */
    private fun addOpen(bet: BetView) {
        val fixture = openByFixture.getOrPut(bet.fixtureId) { FixtureOpen() }
        val payout = openPayout(bet)
        val amount = BigInteger.valueOf(bet.stake)
        fixture.total.apply { bets++; stake = stake.add(amount) }
        fixture.bySelection.getOrPut(bet.selection) { OpenCounts() }.apply { bets++; liability = liability.add(payout) }
    }

    /** Called only for a bet that was open, so retries and conflicting settlements never subtract twice. */
    private fun removeOpen(bet: BetView) {
        val fixture = openByFixture.getValue(bet.fixtureId)
        val payout = openPayout(bet)
        val amount = BigInteger.valueOf(bet.stake)
        fixture.total.apply { bets--; stake = stake.subtract(amount) }
        val selection = fixture.bySelection.getValue(bet.selection)
        selection.apply { bets--; liability = liability.subtract(payout) }
        // Drop empty buckets so history of settled fixtures and odd selections costs no memory.
        if (selection.bets == 0) fixture.bySelection.remove(bet.selection)
        if (fixture.total.bets == 0) openByFixture.remove(bet.fixtureId)
    }

    // ---- Queries (all return copies, safe to use after the lock is released) ----

    fun seqsForPlayer(player: String): List<Long> = synchronized(lock) { byPlayer[player]?.toList() ?: emptyList() }
    fun betsForPlayer(player: String, limit: Int = Int.MAX_VALUE): List<BetView> = synchronized(lock) {
        require(limit >= 0) { "bet limit must be non-negative" }
        byPlayer[player].orEmpty().asReversed().asSequence().mapNotNull { seq ->
            val entry = ledger.entry(seq).entry
            if (entry.kind == "BET_PLACED") entry.metadata["betId"]?.let(bets::get) else null
        }.take(limit).toList().asReversed()
    }
    fun allBets(): List<BetView> = synchronized(lock) { bets.values.toList() }
    fun openBetsForPlayer(player: String): List<BetView> = synchronized(lock) {
        openBetsByPlayer[player].orEmpty().mapNotNull(bets::get)
    }
    fun bet(betId: String): BetView? = synchronized(lock) { bets[betId] }
    fun openBets(): List<BetView> = synchronized(lock) {
        openBetsByFixture.values.asSequence().flatMap { it.asSequence() }
            .map(bets::getValue).sortedWith { a, b -> a.placedSeq.compareTo(b.placedSeq) }.toList()
    }
    fun openBetsOn(fixtureId: String): List<BetView> = synchronized(lock) {
        openBetsByFixture[fixtureId].orEmpty().mapNotNull(bets::get)
    }
    fun recentSeqs(): List<Long> = synchronized(lock) { recent.toList().asReversed() }
    fun players(): Set<String> = synchronized(lock) { byPlayer.keys.toSet() }
    /** Exact aggregates for one indexed prefix; polling never scans or sorts the open bets. */
    fun totals(): Totals = synchronized(lock) {
        Totals(next, deposits, withdrawals, staked, returned, HashMap(kindCounts), openBetCount, openStakes, exposure)
    }

    /**
     * Open-bet aggregates for the requested fixtures (each mapped to the selections whose liability is
     * wanted), all from one indexed prefix. Work is proportional to the request, never to bets or history.
     * Unknown fixtures and unrequested selections read as zero. Never base a money decision on this.
     */
    fun fixtureSummaries(selectionsByFixture: Map<String, Collection<String>>): Map<String, FixtureSummary> = synchronized(lock) {
        val result = LinkedHashMap<String, FixtureSummary>(selectionsByFixture.size * 2)
        for ((fixtureId, selections) in selectionsByFixture) {
            val open = openByFixture[fixtureId]
            val liability = LinkedHashMap<String, BigInteger>()
            for (selection in selections) liability[selection] = open?.bySelection?.get(selection)?.liability ?: BigInteger.ZERO
            result[fixtureId] = FixtureSummary(
                next, open?.total?.bets ?: 0, open?.total?.stake ?: BigInteger.ZERO, java.util.Collections.unmodifiableMap(liability),
            )
        }
        java.util.Collections.unmodifiableMap(result)
    }

    fun fixtureSummary(fixtureId: String, selections: Collection<String>): FixtureSummary =
        fixtureSummaries(mapOf(fixtureId to selections)).getValue(fixtureId)

    fun balance(account: AccountId) = ledger.balance(account)

    override fun close() {
        running = false
        poller.interrupt()
        poller.join(5_000)
        check(!poller.isAlive) { "read model did not stop" }
    }
}
