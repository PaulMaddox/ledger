package com.pam.ledger.web

import com.pam.ledger.AccountId
import com.pam.ledger.Ledger
import com.pam.ledger.SealedEntry
import com.pam.ledger.SportsbookWallet.Accounts

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
        val potentialPayout get() = stake * oddsHundredths / 100
    }

    data class Totals(
        val entries: Long, val deposits: Long, val withdrawals: Long,
        val staked: Long, val returned: Long, val kindCounts: Map<String, Long>,
    )

    private val lock = Any()
    private var next = 0L
    private val byPlayer = HashMap<String, MutableList<Long>>()
    private val bets = LinkedHashMap<String, BetView>()
    private val kindCounts = HashMap<String, Long>()
    private var deposits = 0L
    private var withdrawals = 0L
    private var staked = 0L
    private var returned = 0L
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
            "DEPOSIT" -> deposits += e.postings.first { it.amount > 0 }.amount
            "WITHDRAWAL" -> withdrawals += e.postings.first { it.amount > 0 }.amount
            "BET_PLACED" -> {
                val player = e.metadata.getValue("playerId")
                val stake = e.postings.first { it.amount > 0 }.amount
                staked += stake
                // The web app stores "fixture|selection|odds" in the placement's selection metadata.
                val parts = e.metadata["selection"]?.split("|")
                if (parts != null && parts.size == 3) {
                    bets[e.metadata.getValue("betId")] = BetView(
                        betId = e.metadata.getValue("betId"), playerId = player, fixtureId = parts[0],
                        selection = parts[1], oddsHundredths = parts[2].toInt(), stake = stake,
                        placedSeq = e.seq, placedAtMicros = e.timestampMicros,
                    )
                }
            }
            "BET_WON", "BET_LOST", "BET_VOIDED", "BET_CASHED_OUT" -> {
                val betId = e.metadata.getValue("betId")
                val payout = cashDelta(e.metadata.getValue("playerId"))
                returned += payout
                bets[betId]?.let { bets[betId] = it.copy(settledSeq = e.seq, outcome = e.kind.removePrefix("BET_"), payout = payout) }
            }
        }
    }

    // ---- Queries (all return copies, safe to use after the lock is released) ----

    fun seqsForPlayer(player: String): List<Long> = synchronized(lock) { byPlayer[player]?.toList() ?: emptyList() }
    fun betsForPlayer(player: String): List<BetView> = synchronized(lock) { bets.values.filter { it.playerId == player } }
    fun bet(betId: String): BetView? = synchronized(lock) { bets[betId] }
    fun openBets(): List<BetView> = synchronized(lock) { bets.values.filter { it.isOpen } }
    fun openBetsOn(fixtureId: String): List<BetView> = synchronized(lock) { bets.values.filter { it.isOpen && it.fixtureId == fixtureId } }
    fun recentSeqs(): List<Long> = synchronized(lock) { recent.toList().asReversed() }
    fun players(): Set<String> = synchronized(lock) { byPlayer.keys.toSet() }
    fun totals(): Totals = synchronized(lock) { Totals(next, deposits, withdrawals, staked, returned, HashMap(kindCounts)) }

    fun balance(account: AccountId) = ledger.balance(account)

    override fun close() {
        running = false
        poller.interrupt()
    }
}
