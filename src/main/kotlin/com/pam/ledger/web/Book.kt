package com.pam.ledger.web

import com.pam.ledger.IdempotencyConflictException
import com.pam.ledger.NotFoundException
import com.pam.ledger.OutcomeUnknownException
import com.pam.ledger.Receipt
import com.pam.ledger.SportsbookWallet
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.util.Base64
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.CRC32C
import kotlin.concurrent.read
import kotlin.concurrent.withLock
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
class Book(
    private val wallet: SportsbookWallet,
    private val readModel: ReadModel,
    private val directory: Path? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** A market's stable metadata code, display label and price in hundredths. */
    data class Selection(val code: String, val label: String, val odds: Int)

    class Fixture(
        val id: String,
        val home: String,
        val away: String,
        /** Decimal odds in hundredths (2.10 -> 210), indexed by this market's codes. */
        val odds: Map<String, Int>,
        val sport: Roster.Sport = Roster.Sport.SOCCER,
        val event: String = "Demo legacy match",
        val selections: List<Selection> = odds.map { (code, price) ->
            Selection(code, when (code) { "HOME" -> home; "AWAY" -> away; "DRAW" -> "Draw"; else -> code }, price)
        },
        startsAt: Long = 0,
        endsAt: Long = 0,
        managed: Boolean = false,
    ) {
        @Volatile var startsAt = startsAt
            internal set
        @Volatile var endsAt = endsAt
            internal set
        @Volatile var managed = managed
            internal set
        @Volatile var result: String? = null
            internal set
        @Volatile var settlementComplete = false
            internal set
        internal val lock = ReentrantReadWriteLock()
        internal val settlementLock = ReentrantLock()
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
    private var archiveFailure: IOException? = null
    // Admission still uses each fixture's read/write lock. This only publishes pool changes together.
    internal val fixtureLifecycle = ReentrantLock()
    private val activeIds = linkedSetOf<String>()
    private val archivedIds = hashSetOf<String>()
    @Volatile internal var onFixtureClosed: () -> Unit = {}
    private val settling = ConcurrentHashMap.newKeySet<String>()
    internal fun isSettling(id: String) = id in settling
    private val placementLock = ReentrantLock()
    private val pendingByBetId = HashMap<String, PendingPlacement>()
    private val cashOutLock = ReentrantLock()
    private val pendingCashOuts = HashMap<String, CompletableFuture<Receipt>>()
    internal var placementTimeout: Duration = Duration.ofSeconds(30)
    internal var cashOutTimeout: Duration = Duration.ofSeconds(30)
    internal var archiveForce: (FileChannel) -> Unit = { it.force(true) }

    init {
        restoreFixtures()
        fixtures.values.filter { it.isOpen }.forEach { openById[it.id] = it }
    }

    fun fixtures(): List<Fixture> = fixtureLifecycle.withLock {
        fixtures.values.sortedBy { it.id.removePrefix("F").toInt() }
    }
    /** Current betting candidates, without scanning or sorting historical definitions. */
    fun openFixtures(): List<Fixture> = fixtureLifecycle.withLock { openById.values.filter { it.isOpen } }
    internal fun unfinishedFixtures(): List<Fixture> = synchronized(persistenceLock) {
        activeIds.map { fixtures.getValue(it) }.filterNot { it.settlementComplete }
    }
    fun fixture(id: String): Fixture = fixtures[id] ?: throw NotFoundException("fixture $id does not exist")
    fun openBetsForPlayer(playerId: String): List<ReadModel.BetView> {
        readModel.catchUp()
        return readModel.openBetsForPlayer(playerId)
    }

    /** Create a fictional market with a 6% margin; manual/chaos fixtures are unmanaged. */
    fun addFixture(
        random: Random = Random.Default,
        sport: Roster.Sport = Roster.Sport.SOCCER,
        managed: Boolean = false,
        clock: () -> Long = this.clock,
    ): Fixture = fixtureLifecycle.withLock {
        val used = openFixtures().filter { it.managed && it.sport == sport }.flatMap { f ->
            f.selections.filterNot { it.code == "DRAW" }.map { it.label }
        }.toSet()
        val participants = Roster.participants(sport, if (managed) used else emptySet(), random)
        val codes = when (sport) {
            Roster.Sport.SOCCER -> listOf("HOME", "DRAW", "AWAY")
            Roster.Sport.GOLF -> listOf("P1", "P2", "P3")
            else -> listOf("HOME", "AWAY")
        }
        val labels = if (sport == Roster.Sport.SOCCER) listOf(participants[0], "Draw", participants[1]) else participants
        val weights = codes.map { random.nextDouble(0.25, 0.6) }
        val total = weights.sum()
        fun price(p: Double) = (100 / (p / total * 1.06)).let { (it / 5).toInt() * 5 }.coerceAtLeast(105)
        val selections = codes.mapIndexed { i, code -> Selection(code, labels[i], price(weights[i])) }
        val start = if (managed) clock() else 0
        val f = Fixture("F${counter.incrementAndGet()}", participants.first(), participants.last(),
            selections.associate { it.code to it.odds }, sport, Roster.event(sport, random), selections,
            start, if (managed) start + random.nextLong(30_000, 120_001) else 0, managed)
        persistFixtures(additional = f) {
            fixtures[f.id] = f
            activeIds.add(f.id)
            openById[f.id] = f
        }
        f
    }

    /** Adopt legacy open/pending definitions without guessing any missing result. */
    internal fun adoptLegacy(clock: () -> Long, random: Random) = synchronized(persistenceLock) {
        val live = fixtures.values.filter { it.managed && it.isOpen }.groupingBy { it.sport }.eachCount().toMutableMap()
        fixtures.values.filter {
            !it.settlementComplete && it.result != LEGACY_UNRESOLVED && it.endsAt == 0L && it.event == "Demo legacy match"
        }.sortedBy { it.id.removePrefix("F").toInt() }.forEach {
            if (it.isOpen) {
                val count = live[it.sport] ?: 0
                // Surplus legacy markets retain their bets and remain available for manual settlement.
                if (count >= Roster.pool.getValue(it.sport)) return@forEach
                live[it.sport] = count + 1
            }
            it.startsAt = clock()
            it.endsAt = it.startsAt + random.nextLong(30_000, 120_001)
            it.managed = true
        }
        persistFixtures()
    }

    /**
     * Place a bet. [betId] is chosen by the client (one per bet slip), which makes the
     * request idempotent: a retried or double-tapped slip is recorded once.
     */
    fun placeBet(playerId: String, fixtureId: String, selection: String, stake: Long, betId: String): Receipt {
        return awaitOutcome(placementFuture(playerId, fixtureId, selection, stake, betId), "bet:$betId:place", placementTimeout)
    }

    /** Wait for the original placement outcome without misreporting a timeout as a refusal. */
    internal fun placeBetDefinitive(playerId: String, fixtureId: String, selection: String, stake: Long, betId: String): Receipt {
        return awaitOutcome(placementFuture(playerId, fixtureId, selection, stake, betId), "bet:$betId:place", null)
    }

    private fun placementFuture(playerId: String, fixtureId: String, selection: String, stake: Long, betId: String): CompletableFuture<Receipt> {
        val f = fixture(fixtureId)
        val odds = f.odds[selection] ?: throw IllegalArgumentException("selection is not in this fixture's market")
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
            // Queue admission can park, so do not hold ConcurrentHashMap's update lock while waiting.
            val pending = placementLock.withLock {
                val current = pendingByBetId[betId]
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
                    pendingByBetId[betId] = placement
                    f.pendingPlacements.add(submitted)
                    created = placement
                    placement
                }
            }
            created?.let { placement ->
                placement.future.whenComplete { _, _ ->
                    f.pendingPlacements.remove(placement.future)
                    placementLock.withLock { pendingByBetId.remove(betId, placement) }
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

    /** Accept an offer while the fixture is open, retaining its future for uncertain retries. */
    fun cashOut(betId: String): Receipt = awaitOutcome(cashOutFuture(betId), "bet:$betId:settle", cashOutTimeout)

    /** Simulator cash-outs use the same fixture guard and wait for a definitive wallet outcome. */
    internal fun cashOutDefinitive(betId: String): Receipt = awaitOutcome(cashOutFuture(betId), "bet:$betId:settle", null)

    private fun cashOutFuture(betId: String): CompletableFuture<Receipt> {
        val bet = readModel.bet(betId) ?: readModel.catchUp().let { readModel.bet(betId) }
            ?: throw NotFoundException("bet $betId not found")
        val f = fixture(bet.fixtureId)
        val offer = payout(bet.stake, 85)
        return f.lock.read {
            var created: CompletableFuture<Receipt>? = null
            val future = cashOutLock.withLock {
                // Publication can precede the original completion callback; do not resubmit while it is pending.
                pendingCashOuts[betId]?.takeUnless { it.isDone } ?: run {
                    // Completed retries still reach the ledger's content fingerprint check.
                    require(f.isOpen || wallet.isSettled(betId)) { "fixture ${f.id} has already been settled" }
                    wallet.cashOutAsync(betId, offer).also {
                        pendingCashOuts[betId] = it
                        created = it
                    }
                }
            }
            created?.whenComplete { _, _ ->
                cashOutLock.withLock { pendingCashOuts.remove(betId, future) }
            }
            // Waiting outside the fixture lock lets a result close admission immediately.
            if (created == null) future.thenApply { it.copy(replayed = true) } else future
        }
    }

    /** Publish one result under the placement lock before dispatching settlement. */
    internal fun publishResult(fixtureId: String, result: String, notify: Boolean = true): List<CompletableFuture<Receipt>> = fixtureLifecycle.withLock {
        val f = fixture(fixtureId)
        require(result in f.odds || result == "VOID") { "result is not in this fixture's market" }
        require(f.result != LEGACY_UNRESOLVED) { "fixture $fixtureId predates saved results; refusing to guess its outcome" }
        var changed = false
        val pending = f.lock.write {
            require(f.result == null || f.result == result) { "fixture $fixtureId was already settled as ${f.result}" }
            if (f.result == null) {
                persistFixtures(resultOverrides = mapOf(f.id to result)) {
                    f.result = result
                    openById.remove(f.id)
                }
                changed = true
            }
            f.pendingPlacements.toList()
        }
        if (changed && notify) onFixtureClosed()
        pending
    }

    /** Close the market with one of its selections or VOID and settle every admitted bet. */
    fun settle(fixtureId: String, result: String): Settlement {
        val f = fixture(fixtureId)
        return f.settlementLock.withLock {
            settling.add(f.id)
            try {
                val pending = publishResult(fixtureId, result)
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
                Settlement(fixtureId, result, won, lost, voided, already)
            } finally {
                settling.remove(f.id)
            }
        }
    }

    private fun restoreFixtures() {
        readModel.catchUp()
        // Per-fixture summaries, not every bet ever placed: restart cost follows fixtures, not history.
        val betsByFixture = readModel.fixtureSummaries()
        restoreArchive()
        val file = directory?.resolve(FIXTURES_FILE)?.takeIf { Files.exists(it) }
        val properties = Properties().apply { if (file != null) Files.newInputStream(file).use(::load) }
        val count = if (file == null) 0 else properties.getProperty("count")?.toIntOrNull()
            ?: throw IllegalStateException("invalid fixture state in $file")
        var changed = false
        repeat(count) { index ->
            val prefix = "fixture.$index."
            val id = properties.getProperty(prefix + "id") ?: throw IllegalStateException("missing fixture id in $file")
            val fixture = decodeFixture(properties, prefix)
            if ((fixture.result == null && fixture.settlementComplete) ||
                (fixture.result != null && fixture.result !in fixture.odds.keys + setOf("VOID", LEGACY_UNRESOLVED))) {
                fixture.result = LEGACY_UNRESOLVED
                fixture.settlementComplete = false
                changed = true
            } else if (fixture.result == LEGACY_UNRESOLVED && fixture.settlementComplete) {
                fixture.settlementComplete = false
                changed = true
            }
            if (id !in archivedIds) {
                fixtures[id] = fixture
                activeIds.add(id)
            }
            reserveFixtureId(id)
        }
        for ((id, bets) in betsByFixture) {
            val fixture = fixtures[id]
            if (fixture == null) {
                val odds = mutableMapOf("HOME" to 100, "DRAW" to 100, "AWAY" to 100)
                odds.putAll(bets.odds)
                fixtures[id] = Fixture(id, "Restored fixture", "result unavailable", odds).apply {
                    result = LEGACY_UNRESOLVED
                }
                activeIds.add(id)
                reserveFixtureId(id)
                changed = true
                continue
            }
            val oldResult = fixture.result
            val oldComplete = fixture.settlementComplete
            val hasSettledOutcome = bets.outcomes.values.any { outcomes -> outcomes.any { it != "CASHED_OUT" } }
            if (fixture.result == null && (hasSettledOutcome || fixture.settlementComplete)) {
                fixture.result = LEGACY_UNRESOLVED
                fixture.settlementComplete = false
            } else if (fixture.result != null && fixture.result != LEGACY_UNRESOLVED) {
                val knownResult = fixture.result in fixture.odds.keys + "VOID"
                val consistent = knownResult && bets.outcomes.all { (selection, outcomes) ->
                    val expected = when {
                        fixture.result == "VOID" -> "VOIDED"
                        selection == fixture.result -> "WON"
                        else -> "LOST"
                    }
                    outcomes.all { it == "CASHED_OUT" || it == expected }
                }
                if (!consistent) {
                    fixture.result = LEGACY_UNRESOLVED
                    fixture.settlementComplete = false
                } else {
                    val hasOpenBets = bets.openBets > 0
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
            archiveFailure?.let { throw IOException("Fixture catalogue append failed; reopen the book for recovery", it) }
            val dir = directory
            if (dir != null) {
                val properties = Properties()
                val candidates = (activeIds.map { fixtures.getValue(it) } + listOfNotNull(additional)).distinctBy { it.id }
                val completed = candidates.filter { completionOverrides[it.id] ?: it.settlementComplete }
                completed.filterNot { it.id in archivedIds }.forEach { fixture ->
                    val record = Properties()
                    encodeFixture(record, "", fixture, resultOverrides[fixture.id] ?: fixture.result, true)
                    val bytes = ByteArrayOutputStream().also { record.store(it, null) }.toByteArray()
                    val payload = Base64.getEncoder().encodeToString(bytes)
                    val checksum = CRC32C().apply { update(bytes) }.value.toString(16)
                    try {
                        FileChannel.open(dir.resolve(ARCHIVE_FILE), CREATE, WRITE, APPEND).use { channel ->
                            val buffer = ByteBuffer.wrap("$checksum $payload\n".toByteArray(Charsets.US_ASCII))
                            while (buffer.hasRemaining()) channel.write(buffer)
                            archiveForce(channel)
                        }
                    } catch (failure: IOException) {
                        // A failed force/close may have left either a partial or a complete unacknowledged frame.
                        // No caller may append again until recovery validates that prefix.
                        archiveFailure = failure
                        throw failure
                    }
                    archivedIds.add(fixture.id)
                }
                val ordered = candidates.filterNot { it.id in archivedIds }.sortedBy { it.id.removePrefix("F").toInt() }
                properties.setProperty("count", ordered.size.toString())
                ordered.forEachIndexed { index, fixture ->
                    val prefix = "fixture.$index."
                    encodeFixture(properties, prefix, fixture,
                        if (resultOverrides.containsKey(fixture.id)) resultOverrides[fixture.id] else fixture.result,
                        completionOverrides[fixture.id] ?: fixture.settlementComplete)
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
                    replaceFixtureState(temporary, target)
                    if (!System.getProperty("os.name").startsWith("Windows")) {
                        FileChannel.open(dir, READ).use { it.force(true) }
                    }
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            activeIds.removeAll { it in archivedIds }
            afterPersist()
        }
    }

    private fun encodeFixture(p: Properties, prefix: String, f: Fixture, result: String?, complete: Boolean) {
        fun put(key: String, value: Any) { p.setProperty(prefix + key, value.toString()) }
        put("id", f.id); put("home", f.home); put("away", f.away)
        put("sport", f.sport.name); put("event", f.event)
        put("selections", f.selections.joinToString(",") { it.code })
        f.selections.forEach { put("label.${it.code}", it.label); put("odds.${it.code}", it.odds) }
        put("startsAt", f.startsAt); put("endsAt", f.endsAt); put("managed", f.managed)
        put("result", result.orEmpty()); put("complete", complete)
    }

    private fun replaceFixtureState(temporary: Path, target: Path) {
        // Windows scanners/readers can briefly deny rename even after our own handles close.
        // Only this unsigned metadata replacement is retried, never a ledger journal write.
        var retries = 0
        while (true) {
            try {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
                return
            } catch (e: java.nio.file.AccessDeniedException) {
                if (!System.getProperty("os.name").startsWith("Windows") || retries++ >= 10) throw e
                if (retries == 1) System.err.println("Windows temporarily blocked demo fixture metadata replacement: $e")
                Thread.sleep(10)
            }
        }
    }

    private fun decodeFixture(p: Properties, prefix: String): Fixture {
        fun get(key: String) = p.getProperty(prefix + key) ?: error("missing fixture $key")
        val codes = p.getProperty(prefix + "selections", "HOME,DRAW,AWAY").split(",")
        val unresolved = p.getProperty(prefix + "result") == LEGACY_UNRESOLVED
        check((unresolved || codes.size in 2..3) && codes.distinct().size == codes.size &&
            codes.all { it.matches(Regex("[A-Z0-9]{1,8}")) }) { "invalid fixture selections" }
        val home = get("home"); val away = get("away")
        val selections = codes.map { code ->
            val label = p.getProperty(prefix + "label.$code") ?: when (code) { "HOME" -> home; "AWAY" -> away; else -> "Draw" }
            Selection(code, label, get("odds.$code").toInt().also { check(it >= 100) { "invalid fixture price" } })
        }
        return Fixture(get("id"), home, away, selections.associate { it.code to it.odds },
            Roster.Sport.valueOf(p.getProperty(prefix + "sport", "SOCCER")),
            p.getProperty(prefix + "event", "Demo legacy match"), selections,
            p.getProperty(prefix + "startsAt", "0").toLong(), p.getProperty(prefix + "endsAt", "0").toLong(),
            p.getProperty(prefix + "managed", "false").toBooleanStrict()).apply {
            result = p.getProperty(prefix + "result")?.ifEmpty { null }
            settlementComplete = p.getProperty(prefix + "complete", "false").toBooleanStrict()
        }
    }

    private fun restoreArchive() {
        val file = directory?.resolve(ARCHIVE_FILE)?.takeIf { Files.exists(it) } ?: return
        var validBytes = 0L
        Files.newInputStream(file).buffered().use { input ->
            val line = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next == -1) {
                    if (line.size() != 0) {
                        // Preserve the torn suffix for inspection before recovering the valid prefix.
                        Files.write(file.resolveSibling("$ARCHIVE_FILE.torn"), line.toByteArray())
                        FileChannel.open(file, WRITE).use { it.truncate(validBytes); it.force(true) }
                    }
                    break
                }
                if (next != 10) {
                    check(line.size() < 65_536) { "oversized fixture archive record" }
                    line.write(next)
                    continue
                }
                val text = line.toString(Charsets.US_ASCII)
                val parts = text.split(' ', limit = 2)
                check(parts.size == 2) { "corrupt fixture archive record" }
                val bytes = Base64.getDecoder().decode(parts[1])
                check(CRC32C().apply { update(bytes) }.value.toString(16) == parts[0]) { "fixture archive checksum mismatch" }
                val fixture = decodeFixture(Properties().apply { bytes.inputStream().use(::load) }, "")
                check(fixture.settlementComplete && fixture.result in fixture.odds.keys + "VOID") { "invalid archived result" }
                check(archivedIds.add(fixture.id)) { "duplicate archived fixture ${fixture.id}" }
                fixtures[fixture.id] = fixture
                reserveFixtureId(fixture.id)
                validBytes += line.size() + 1
                line.reset()
            }
        }
    }

    private fun awaitOutcome(future: CompletableFuture<Receipt>, idempotencyKey: String, timeout: Duration?): Receipt = try {
        if (timeout == null) future.join() else future.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
    } catch (e: CompletionException) {
        throw (e.cause as? RuntimeException ?: e)
    } catch (e: TimeoutException) {
        throw OutcomeUnknownException(idempotencyKey)
    } catch (e: ExecutionException) {
        throw (e.cause as? RuntimeException ?: e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    }

    companion object {
        internal const val FIXTURES_FILE = "demo-fixtures.properties"
        internal const val ARCHIVE_FILE = "demo-fixtures.archive"
        private const val LEGACY_UNRESOLVED = "LEGACY_UNRESOLVED"

    }
}
