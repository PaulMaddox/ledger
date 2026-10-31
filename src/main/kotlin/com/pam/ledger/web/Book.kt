package com.pam.ledger.web

import com.pam.ledger.IdempotencyConflictException
import com.pam.ledger.NotFoundException
import com.pam.ledger.OutcomeUnknownException
import com.pam.ledger.Receipt
import com.pam.ledger.SportsbookWallet
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.random.Random

internal fun payout(amount: Long, hundredths: Int): Long {
    require(amount >= 0 && hundredths >= 0) { "amount and rate must be non-negative" }
    return Math.addExact(
        Math.multiplyExact(amount / 100, hundredths.toLong()),
        (amount % 100) * hundredths / 100,
    )
}

/*
 * ============================================================================
 *  THE BOOK: fixtures, odds, bet placement and settlement for the web app
 * ============================================================================
 *
 *  Fixtures are demo data persisted beside the ledger; every money movement
 *  goes through SportsbookWallet and therefore through the ledger's single writer.
 *
 *  THE ONE RACE THIS CLASS MUST GET RIGHT: a bet being placed on a fixture at
 *  the moment that fixture is settled. If the placement checks "fixture open",
 *  then settlement runs, then the placement commits, the bet would be left open
 *  on a finished match forever. Each fixture therefore has a read/write lock:
 *  placements hold the READ lock while they check the result and register their
 *  non-cancellable ledger future. Settlement takes the WRITE lock to close the
 *  fixture, then waits for every registered future before collecting bets. A
 *  timed-out request therefore cannot leave a late placement behind. Cash-outs
 *  also hold the READ lock so no new offer can be accepted after a known result.
 */
class Book(private val wallet: SportsbookWallet, private val readModel: ReadModel, private val directory: Path? = null) {

    class Fixture(
        val id: String,
        val home: String,
        val away: String,
        /** Decimal odds in hundredths (2.10 -> 210), for HOME, DRAW and AWAY. */
        val odds: Map<String, Int>,
    ) {
        @Volatile var result: String? = null
            internal set
        @Volatile var settlementComplete = false
            internal set
        internal val lock = ReentrantReadWriteLock()
        internal val pendingPlacements = ConcurrentHashMap.newKeySet<CompletableFuture<Receipt>>()
        val isOpen get() = result == null
    }

    data class Settlement(val fixtureId: String, val result: String, val won: Int, val lost: Int, val voided: Int, val alreadySettled: Int)
    private data class PendingPlacement(
        val playerId: String,
        val fixtureId: String,
        val selection: String,
        val stake: Long,
        val future: CompletableFuture<Receipt>,
    )

    private val fixtures = ConcurrentHashMap<String, Fixture>()
    private val openById = ConcurrentHashMap<String, Fixture>()
    private val counter = AtomicInteger()
    private val persistenceLock = Any()
    private val pendingByBetId = ConcurrentHashMap<String, PendingPlacement>()
    internal var placementTimeout: Duration = Duration.ofSeconds(30)

    init {
        restoreFixtures()
        fixtures.values.filter { it.isOpen }.forEach { openById[it.id] = it }
    }

    fun fixtures(): List<Fixture> = fixtures.values.sortedBy { it.id.removePrefix("F").toInt() }
    /** Current betting candidates, without scanning or sorting historical definitions. */
    fun openFixtures(): List<Fixture> = openById.values.filter { it.isOpen }
    fun fixture(id: String): Fixture = fixtures[id] ?: throw NotFoundException("fixture $id does not exist")
    fun openBetsForPlayer(playerId: String): List<ReadModel.BetView> {
        readModel.catchUp()
        return readModel.openBetsForPlayer(playerId)
    }

    /** Create a fixture with plausible odds: random win probabilities plus a 6% bookmaker margin. */
    fun addFixture(random: Random = Random.Default): Fixture {
        val (home, away) = TEAMS.shuffled(random).take(2)
        val h = random.nextDouble(0.25, 0.6)
        val d = random.nextDouble(0.18, 0.3)
        val a = (1.0 - h - d).coerceAtLeast(0.1)
        val total = h + d + a
        fun price(p: Double) = (100 / (p / total * 1.06)).let { (it / 5).toInt() * 5 }.coerceAtLeast(105)
        val f = Fixture("F${counter.incrementAndGet()}", home, away, mapOf("HOME" to price(h), "DRAW" to price(d), "AWAY" to price(a)))
        persistFixtures(additional = f) {
            fixtures[f.id] = f
            openById[f.id] = f
        }
        return f
    }

    /**
     * Place a bet. [betId] is chosen by the client (one per bet slip), which makes the
     * request idempotent: a retried or double-tapped slip is recorded once.
     */
    fun placeBet(playerId: String, fixtureId: String, selection: String, stake: Long, betId: String): Receipt {
        return awaitPlacement(placementFuture(playerId, fixtureId, selection, stake, betId), betId)
    }

    /** Wait for the original placement outcome without misreporting a timeout as a refusal. */
    internal fun placeBetDefinitive(playerId: String, fixtureId: String, selection: String, stake: Long, betId: String): Receipt {
        return awaitPlacement(placementFuture(playerId, fixtureId, selection, stake, betId), betId, null)
    }

    private fun placementFuture(playerId: String, fixtureId: String, selection: String, stake: Long, betId: String): CompletableFuture<Receipt> {
        val f = fixture(fixtureId)
        val odds = f.odds[selection] ?: throw IllegalArgumentException("selection must be HOME, DRAW or AWAY")
        try {
            payout(stake, odds)
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("potential payout exceeds the supported amount range")
        }
        val metadata = "$fixtureId|$selection|$odds"
        if (wallet.hasBetPlacement(betId)) return wallet.placeBetAsync(playerId, betId, stake, "EUR", selection = metadata)
        val future = f.lock.read {
            if (wallet.hasBetPlacement(betId)) {
                return@read wallet.placeBetAsync(playerId, betId, stake, "EUR", selection = metadata)
            }
            var created: PendingPlacement? = null
            val pending = pendingByBetId.compute(betId) { _, current ->
                if (current != null) {
                    if (current.playerId != playerId || current.fixtureId != fixtureId ||
                        current.selection != selection || current.stake != stake) {
                        throw IdempotencyConflictException("bet:$betId:place")
                    }
                    current
                } else {
                    require(f.isOpen) { "fixture $fixtureId has already been settled" }
                    // Settlement waits for this future even if the request times out.
                    val submitted = wallet.placeBetAsync(playerId, betId, stake, "EUR", selection = metadata)
                    val placement = PendingPlacement(playerId, fixtureId, selection, stake, submitted)
                    f.pendingPlacements.add(submitted)
                    created = placement
                    placement
                }
            }!!
            created?.let { placement ->
                placement.future.whenComplete { _, _ ->
                    f.pendingPlacements.remove(placement.future)
                    pendingByBetId.remove(betId, placement)
                }
            }
            pending.future
        }
        return future
    }

    /** The illustrative cash-out offer for an open bet: 85% of the stake. */
    fun cashOutOffer(betId: String): Long {
        // The read model trails the ledger by up to 100 ms; catch up before declaring a bet unknown.
        val bet = readModel.bet(betId) ?: readModel.catchUp().let { readModel.bet(betId) } ?: throw NotFoundException("bet $betId not found")
        return payout(bet.stake, 85)
    }

    fun cashOut(betId: String): Receipt = cashOut(betId, definitive = false)

    /** Simulator cash-outs use the same fixture guard and wait for a definitive wallet outcome. */
    internal fun cashOutDefinitive(betId: String): Receipt = cashOut(betId, definitive = true)

    private fun cashOut(betId: String, definitive: Boolean): Receipt {
        val bet = readModel.bet(betId) ?: readModel.catchUp().let { readModel.bet(betId) }
            ?: throw NotFoundException("bet $betId not found")
        val f = fixture(bet.fixtureId)
        return f.lock.read {
            // Committed retries still reach the ledger's content fingerprint check.
            require(f.isOpen || wallet.isSettled(betId)) { "fixture ${f.id} has already been settled" }
            val offer = payout(bet.stake, 85)
            if (definitive) wallet.cashOutDefinitive(betId, offer) else wallet.cashOut(betId, offer)
        }
    }

    /** Close the fixture with [result] (HOME, DRAW, AWAY or VOID) and settle every open bet on it. */
    fun settle(fixtureId: String, result: String): Settlement {
        require(result in setOf("HOME", "DRAW", "AWAY", "VOID")) { "result must be HOME, DRAW, AWAY or VOID" }
        val f = fixture(fixtureId)
        require(f.result != LEGACY_UNRESOLVED) {
            "fixture $fixtureId predates saved results; refusing to guess its outcome"
        }
        val pending = f.lock.write {
            require(f.result == null || f.result == result) { "fixture $fixtureId was already settled as ${f.result}" }
            persistFixtures(resultOverrides = mapOf(f.id to result)) {
                f.result = result
                openById.remove(f.id)
            }
            f.pendingPlacements.toList()
        }
        pending.forEach { runCatching { it.join() } }
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
        readModel.catchUp()
        if (readModel.openBetsOn(fixtureId).isEmpty()) {
            if (!f.settlementComplete) {
                persistFixtures(completionOverrides = mapOf(f.id to true)) { f.settlementComplete = true }
            }
        }
        return Settlement(fixtureId, result, won, lost, voided, already)
    }

    private fun restoreFixtures() {
        readModel.catchUp()
        val betsByFixture = readModel.allBets().groupBy { it.fixtureId }
        val file = directory?.resolve(FIXTURES_FILE)?.takeIf { Files.exists(it) }
        if (file == null) {
            betsByFixture.forEach { (id, bets) ->
                val odds = mutableMapOf("HOME" to 100, "DRAW" to 100, "AWAY" to 100)
                bets.forEach { odds[it.selection] = it.oddsHundredths }
                val fixture = Fixture(id, "Restored fixture", "result unavailable", odds)
                fixture.result = LEGACY_UNRESOLVED
                fixtures[id] = fixture
                reserveFixtureId(id)
            }
            if (betsByFixture.isNotEmpty()) persistFixtures()
            return
        }
        val properties = Properties().apply { Files.newInputStream(file).use(::load) }
        val count = properties.getProperty("count")?.toIntOrNull()
            ?: throw IllegalStateException("invalid fixture state in $file")
        var changed = false
        repeat(count) { index ->
            val prefix = "fixture.$index."
            val id = properties.getProperty(prefix + "id") ?: throw IllegalStateException("missing fixture id in $file")
            val odds = listOf("HOME", "DRAW", "AWAY").associateWith { selection ->
                properties.getProperty(prefix + "odds.$selection")?.toIntOrNull()
                    ?: throw IllegalStateException("missing $selection odds for $id in $file")
            }
            val fixture = Fixture(
                id,
                properties.getProperty(prefix + "home") ?: throw IllegalStateException("missing home team for $id in $file"),
                properties.getProperty(prefix + "away") ?: throw IllegalStateException("missing away team for $id in $file"),
                odds,
            )
            fixture.result = properties.getProperty(prefix + "result")?.ifEmpty { null }
            fixture.settlementComplete = properties.getProperty(prefix + "complete") == "true"
            if ((fixture.result == null && fixture.settlementComplete) ||
                (fixture.result != null && fixture.result !in setOf("HOME", "DRAW", "AWAY", "VOID", LEGACY_UNRESOLVED))) {
                fixture.result = LEGACY_UNRESOLVED
                fixture.settlementComplete = false
                changed = true
            } else if (fixture.result == LEGACY_UNRESOLVED && fixture.settlementComplete) {
                fixture.settlementComplete = false
                changed = true
            }
            fixtures[id] = fixture
            reserveFixtureId(id)
        }
        for ((id, bets) in betsByFixture) {
            val fixture = fixtures[id]
            if (fixture == null) {
                val odds = mutableMapOf("HOME" to 100, "DRAW" to 100, "AWAY" to 100)
                bets.forEach { odds[it.selection] = it.oddsHundredths }
                fixtures[id] = Fixture(id, "Restored fixture", "result unavailable", odds).apply {
                    result = LEGACY_UNRESOLVED
                }
                reserveFixtureId(id)
                changed = true
                continue
            }
            val oldResult = fixture.result
            val oldComplete = fixture.settlementComplete
            val hasSettledOutcome = bets.any { it.outcome != null && it.outcome != "CASHED_OUT" }
            if (fixture.result == null && (hasSettledOutcome || fixture.settlementComplete)) {
                fixture.result = LEGACY_UNRESOLVED
                fixture.settlementComplete = false
            } else if (fixture.result != null && fixture.result != LEGACY_UNRESOLVED) {
                val knownResult = fixture.result in setOf("HOME", "DRAW", "AWAY", "VOID")
                val consistent = knownResult && bets.all { bet ->
                    val outcome = bet.outcome ?: return@all true
                    outcome == "CASHED_OUT" || outcome == when {
                        fixture.result == "VOID" -> "VOIDED"
                        bet.selection == fixture.result -> "WON"
                        else -> "LOST"
                    }
                }
                if (!consistent) {
                    fixture.result = LEGACY_UNRESOLVED
                    fixture.settlementComplete = false
                } else {
                    val hasOpenBets = bets.any { it.isOpen }
                    if (fixture.settlementComplete && hasOpenBets) fixture.settlementComplete = false
                    if (!hasOpenBets) fixture.settlementComplete = true
                }
            }
            changed = changed || oldResult != fixture.result || oldComplete != fixture.settlementComplete
        }
        if (changed) persistFixtures()
        // IDs referenced by durable bets remain reserved even if fixture state was lost.
        betsByFixture.keys.forEach(::reserveFixtureId)
    }

    private fun reserveFixtureId(id: String) {
        val number = id.removePrefix("F").toIntOrNull()
            ?: throw IllegalStateException("invalid fixture id '$id' in ledger or fixture state")
        counter.updateAndGet { maxOf(it, number) }
    }

    private fun persistFixtures(
        additional: Fixture? = null,
        resultOverrides: Map<String, String?> = emptyMap(),
        completionOverrides: Map<String, Boolean> = emptyMap(),
        afterPersist: () -> Unit = {},
    ) {
        synchronized(persistenceLock) {
            val dir = directory
            if (dir != null) {
                val properties = Properties()
                val ordered = (fixtures.values + listOfNotNull(additional)).distinctBy { it.id }.sortedBy { it.id.removePrefix("F").toInt() }
                properties.setProperty("count", ordered.size.toString())
                ordered.forEachIndexed { index, fixture ->
                    val prefix = "fixture.$index."
                    properties.setProperty(prefix + "id", fixture.id)
                    properties.setProperty(prefix + "home", fixture.home)
                    properties.setProperty(prefix + "away", fixture.away)
                    fixture.odds.forEach { (selection, price) -> properties.setProperty(prefix + "odds.$selection", price.toString()) }
                    properties.setProperty(prefix + "result", (if (resultOverrides.containsKey(fixture.id)) resultOverrides[fixture.id] else fixture.result).orEmpty())
                    properties.setProperty(prefix + "complete", (completionOverrides[fixture.id] ?: fixture.settlementComplete).toString())
                }
                val target = dir.resolve(FIXTURES_FILE)
                val temporary = Files.createTempFile(dir, "fixtures-", ".tmp")
                try {
                    FileChannel.open(temporary, WRITE).use { channel ->
                        val output = Channels.newOutputStream(channel)
                        properties.store(output, "Demo fixture state")
                        output.flush()
                        channel.force(true)
                    }
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (e: AtomicMoveNotSupportedException) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    if (!System.getProperty("os.name").startsWith("Windows")) {
                        FileChannel.open(dir, READ).use { it.force(true) }
                    }
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            afterPersist()
        }
    }

    private fun awaitPlacement(future: CompletableFuture<Receipt>, betId: String, timeout: Duration? = placementTimeout): Receipt = try {
        if (timeout == null) future.join() else future.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
    } catch (e: CompletionException) {
        throw (e.cause as? RuntimeException ?: e)
    } catch (e: TimeoutException) {
        throw OutcomeUnknownException("bet:$betId:place")
    } catch (e: ExecutionException) {
        throw (e.cause as? RuntimeException ?: e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    }

    private companion object {
        const val FIXTURES_FILE = "demo-fixtures.properties"
        const val LEGACY_UNRESOLVED = "LEGACY_UNRESOLVED"

        val TEAMS = listOf(
            "Falcons", "Harbour City", "Northgate", "Riverside", "Old Town", "Westbrook",
            "Kingsport", "Lakeview", "Ashford", "Millbrook", "Sandhaven", "Redcliff",
        )
    }
}
