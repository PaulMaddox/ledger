package com.pam.ledger.web

import com.pam.ledger.IdempotencyConflictException
import com.pam.ledger.Ledger
import com.pam.ledger.NotFoundException
import com.pam.ledger.Receipt
import com.pam.ledger.SportsbookWallet
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.random.Random

/*
 * ============================================================================
 *  THE BOOK: fixtures, odds, bet placement and settlement for the web app
 * ============================================================================
 *
 *  Every money movement goes through SportsbookWallet and therefore through the
 *  ledger's single writer. Fixture definitions are demo data kept beside the
 *  ledger (fixtures.txt when --data is used), so a restarted app settles old
 *  bets against the right matches and never reuses a fixture id.
 *
 *  THE RACE THIS CLASS MUST GET RIGHT: a bet being placed on a fixture at the
 *  moment that fixture is settled. Each fixture has a read/write lock:
 *  placements hold the READ lock until their outcome is DEFINITIVE (committed or
 *  refused), and settlement takes the WRITE lock to close betting, so it waits
 *  for in-flight placements and blocks new ones. Read locks are shared, so many
 *  bets can still be placed on one fixture concurrently.
 *
 *  Settlement has two stages: closing betting (result recorded) and paying out
 *  every bet. If paying out is interrupted (overload, outage), calling settle()
 *  again with the same result resumes it; each bet's settlement is idempotent.
 */
class Book(
    private val ledger: Ledger,
    private val wallet: SportsbookWallet,
    private val readModel: ReadModel,
    /** Where fixture definitions are kept between runs, or null to keep them in memory only. */
    private val store: Path? = null,
) {

    class Fixture(
        val id: String,
        val home: String,
        val away: String,
        /** Decimal odds in hundredths (2.10 -> 210), for HOME, DRAW and AWAY. */
        val odds: Map<String, Int>,
    ) {
        /** Set when betting closes. */
        @Volatile var result: String? = null
            internal set
        /** Set once every bet on the fixture has been settled. */
        @Volatile var settled: Boolean = false
            internal set
        internal val lock = ReentrantReadWriteLock()
        val isOpen get() = result == null
        /** Betting has closed but some bets still await settlement (an interrupted settle()). */
        val needsSettlement get() = result != null && !settled
    }

    data class Settlement(val fixtureId: String, val result: String, val won: Int, val lost: Int, val voided: Int, val alreadySettled: Int, val complete: Boolean)

    private val fixtures = ConcurrentHashMap<String, Fixture>()
    private val counter = AtomicInteger()

    init {
        load()
        // Never reuse an id that any bet in the ledger refers to, even if fixtures.txt was lost.
        readModel.catchUp()
        val highest = (fixtures.keys + readModel.referencedFixtureIds()).maxOfOrNull { it.removePrefix("F").toIntOrNull() ?: 0 } ?: 0
        counter.set(highest)
    }

    fun fixtures(): List<Fixture> = fixtures.values.sortedBy { it.id.removePrefix("F").toInt() }
    fun fixture(id: String): Fixture = fixtures[id] ?: throw NotFoundException("fixture $id does not exist")

    /** Create a fixture with plausible odds: random win probabilities plus a 6% bookmaker margin. */
    fun addFixture(random: Random = Random.Default): Fixture {
        val (home, away) = TEAMS.shuffled(random).take(2)
        val h = random.nextDouble(0.25, 0.6)
        val d = random.nextDouble(0.18, 0.3)
        val a = (1.0 - h - d).coerceAtLeast(0.1)
        val total = h + d + a
        fun price(p: Double) = (100 / (p / total * 1.06)).let { (it / 5).toInt() * 5 }.coerceAtLeast(105)
        val f = Fixture("F${counter.incrementAndGet()}", home, away, mapOf("HOME" to price(h), "DRAW" to price(d), "AWAY" to price(a)))
        fixtures[f.id] = f
        save()
        return f
    }

    /**
     * Place a bet. [betId] is chosen by the client (one per bet slip), which makes the
     * request idempotent: a retried or double-tapped slip is recorded once.
     */
    fun placeBet(playerId: String, fixtureId: String, selection: String, stake: Long, betId: String): Receipt {
        val f = fixture(fixtureId)
        val odds = f.odds[selection] ?: throw IllegalArgumentException("selection must be HOME, DRAW or AWAY")
        require(stake > 0) { "stake must be positive" }
        payout(stake, odds) // refuse up front any stake whose payout could not be represented
        val label = "$fixtureId|$selection|$odds" // odds travel with the bet, so settlement pays what was offered

        // A retry of a slip that was already placed must replay its receipt, even if the
        // fixture has since been settled. The ledger decides: replay, or conflict if different.
        if (ledger.findByIdempotencyKey("bet:$betId:place") != null) {
            return wallet.placeBet(playerId, betId, stake, "EUR", selection = label)
        }
        return f.lock.read {
            require(f.isOpen) { "fixture $fixtureId has already been settled" }
            wallet.placeBetUntilDefinitive(playerId, betId, stake, "EUR", selection = label)
        }
    }

    /** The illustrative cash-out offer for an open bet: 85% of the stake, rounded down. */
    fun cashOutOffer(betId: String): Long {
        // The read model trails the ledger by up to 100 ms; catch up before declaring a bet unknown.
        val bet = readModel.bet(betId) ?: readModel.catchUp().let { readModel.bet(betId) } ?: throw NotFoundException("bet $betId not found")
        return percent(bet.stake, 85)
    }

    fun cashOut(betId: String): Receipt = wallet.cashOut(betId, cashOutOffer(betId))

    /**
     * Close the fixture with [result] (HOME, DRAW, AWAY or VOID) and settle every open bet
     * on it. Calling again with the same result resumes an interrupted settlement.
     */
    fun settle(fixtureId: String, result: String): Settlement {
        require(result in setOf("HOME", "DRAW", "AWAY", "VOID")) { "result must be HOME, DRAW, AWAY or VOID" }
        val f = fixture(fixtureId)
        f.lock.write {
            when (f.result) {
                null -> { f.result = result; save() }
                result -> require(!f.settled) { "fixture $fixtureId has already been settled as $result" }
                else -> throw IllegalArgumentException("fixture $fixtureId was already settled as ${f.result}")
            }
        }
        // Every placement on this fixture has now committed; make sure the read model has seen them.
        readModel.catchUp()
        var won = 0; var lost = 0; var voided = 0; var already = 0
        for (bet in readModel.openBetsOn(fixtureId)) {
            try {
                when {
                    result == "VOID" -> { wallet.voidBet(bet.betId); voided++ }
                    bet.selection == result -> { wallet.settleWin(bet.betId, payout(bet.stake, bet.oddsHundredths)); won++ }
                    else -> { wallet.settleLoss(bet.betId); lost++ }
                }
            } catch (e: IdempotencyConflictException) {
                already++ // cashed out first, or settled by an earlier attempt: resolved exactly once
            }
            // Any other failure (overload, outage) propagates; settle() again resumes from here.
        }
        readModel.catchUp()
        val complete = readModel.openBetsOn(fixtureId).isEmpty()
        if (complete) { f.settled = true; save() }
        return Settlement(fixtureId, result, won, lost, voided, already, complete)
    }

    // ---- Persistence of fixture definitions (demo data, not money) ----

    private fun save() {
        val file = store ?: return
        synchronized(this) {
            val text = fixtures().joinToString("\n") { f ->
                listOf(f.id, f.home, f.away, "${f.odds["HOME"]},${f.odds["DRAW"]},${f.odds["AWAY"]}", f.result ?: "", f.settled.toString()).joinToString("|")
            }
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, text)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun load() {
        val file = store ?: return
        if (!Files.exists(file)) return
        for (line in Files.readAllLines(file).filter { it.isNotBlank() }) {
            val p = line.split("|")
            val (h, d, a) = p[3].split(",").map(String::toInt)
            val f = Fixture(p[0], p[1], p[2], mapOf("HOME" to h, "DRAW" to d, "AWAY" to a))
            f.result = p[4].ifEmpty { null }
            f.settled = p[5].toBoolean()
            fixtures[f.id] = f
        }
    }

    companion object {
        private val TEAMS = listOf(
            "Falcons", "Harbour City", "Northgate", "Riverside", "Old Town", "Westbrook",
            "Kingsport", "Lakeview", "Ashford", "Millbrook", "Sandhaven", "Redcliff",
        )

        /**
         * Payout for a winning bet: stake x odds/100, rounded down to whole minor units.
         * Computed as quotient and remainder so the intermediate product cannot overflow when
         * the true result fits; throws if the payout itself cannot be represented.
         */
        fun payout(stake: Long, oddsHundredths: Int): Long = try {
            Math.addExact(Math.multiplyExact(stake / 100, oddsHundredths.toLong()), (stake % 100) * oddsHundredths / 100)
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("stake too large: its payout cannot be represented")
        }

        /** [pct] percent of [amount], rounded down, without an overflowing intermediate product. */
        fun percent(amount: Long, pct: Int): Long = (amount / 100) * pct + (amount % 100) * pct / 100
    }
}
