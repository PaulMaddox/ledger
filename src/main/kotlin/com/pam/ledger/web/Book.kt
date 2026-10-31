package com.pam.ledger.web

import com.pam.ledger.IdempotencyConflictException
import com.pam.ledger.NotFoundException
import com.pam.ledger.Receipt
import com.pam.ledger.SportsbookWallet
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
 *  Fixtures live in memory (they are demo data); every money movement goes
 *  through SportsbookWallet and therefore through the ledger's single writer.
 *
 *  THE ONE RACE THIS CLASS MUST GET RIGHT: a bet being placed on a fixture at
 *  the moment that fixture is settled. If the placement checks "fixture open",
 *  then settlement runs, then the placement commits, the bet would be left open
 *  on a finished match forever. Each fixture therefore has a read/write lock:
 *  placements hold the READ lock until their entry is committed, and settlement
 *  takes the WRITE lock to close the fixture, so it waits for in-flight
 *  placements to land and blocks new ones. Many bets can still be placed on the
 *  same fixture concurrently, because read locks are shared.
 */
class Book(private val wallet: SportsbookWallet, private val readModel: ReadModel) {

    class Fixture(
        val id: String,
        val home: String,
        val away: String,
        /** Decimal odds in hundredths (2.10 -> 210), for HOME, DRAW and AWAY. */
        val odds: Map<String, Int>,
    ) {
        @Volatile var result: String? = null
            internal set
        internal val lock = ReentrantReadWriteLock()
        val isOpen get() = result == null
    }

    data class Settlement(val fixtureId: String, val result: String, val won: Int, val lost: Int, val voided: Int, val alreadySettled: Int)

    private val fixtures = ConcurrentHashMap<String, Fixture>()
    private val counter = AtomicInteger()

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
        return f
    }

    /**
     * Place a bet. [betId] is chosen by the client (one per bet slip), which makes the
     * request idempotent: a retried or double-tapped slip is recorded once.
     */
    fun placeBet(playerId: String, fixtureId: String, selection: String, stake: Long, betId: String): Receipt {
        val f = fixture(fixtureId)
        val odds = f.odds[selection] ?: throw IllegalArgumentException("selection must be HOME, DRAW or AWAY")
        return f.lock.read {
            require(f.isOpen) { "fixture $fixtureId has already been settled" }
            // The odds travel with the bet, so settlement pays what was offered at the time.
            wallet.placeBet(playerId, betId, stake, "EUR", selection = "$fixtureId|$selection|$odds")
        }
    }

    /** The illustrative cash-out offer for an open bet: 85% of the stake. */
    fun cashOutOffer(betId: String): Long {
        // The read model trails the ledger by up to 100 ms; catch up before declaring a bet unknown.
        val bet = readModel.bet(betId) ?: readModel.catchUp().let { readModel.bet(betId) } ?: throw NotFoundException("bet $betId not found")
        return bet.stake * 85 / 100
    }

    fun cashOut(betId: String): Receipt = wallet.cashOut(betId, cashOutOffer(betId))

    /** Close the fixture with [result] (HOME, DRAW, AWAY or VOID) and settle every open bet on it. */
    fun settle(fixtureId: String, result: String): Settlement {
        require(result in setOf("HOME", "DRAW", "AWAY", "VOID")) { "result must be HOME, DRAW, AWAY or VOID" }
        val f = fixture(fixtureId)
        f.lock.write {
            require(f.isOpen) { "fixture $fixtureId was already settled as ${f.result}" }
            f.result = result
        }
        // Every placement on this fixture has now committed; make sure the read model has seen them.
        readModel.catchUp()
        var won = 0; var lost = 0; var voided = 0; var already = 0
        for (bet in readModel.openBetsOn(fixtureId)) {
            try {
                when {
                    result == "VOID" -> { wallet.voidBet(bet.betId); voided++ }
                    bet.selection == result -> { wallet.settleWin(bet.betId, bet.potentialPayout); won++ }
                    else -> { wallet.settleLoss(bet.betId); lost++ }
                }
            } catch (e: IdempotencyConflictException) {
                already++ // the player cashed out first: the ledger resolves the bet exactly once
            }
        }
        return Settlement(fixtureId, result, won, lost, voided, already)
    }

    private companion object {
        val TEAMS = listOf(
            "Falcons", "Harbour City", "Northgate", "Riverside", "Old Town", "Westbrook",
            "Kingsport", "Lakeview", "Ashford", "Millbrook", "Sandhaven", "Redcliff",
        )
    }
}
