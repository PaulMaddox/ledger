package com.pam.ledger

import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import com.pam.ledger.web.Audit
import com.pam.ledger.web.Book
import com.pam.ledger.web.FixtureScheduler
import com.pam.ledger.web.ReadModel
import com.pam.ledger.web.Roster
import com.pam.ledger.web.Simulator
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

class FixtureSchedulerTest {
    private fun awaitSettled(fixtures: List<Book.Fixture>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (fixtures.any { !it.settlementComplete } && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(fixtures.all { it.settlementComplete }, "every dispatched fixture must finish")
    }

    private fun assertPool(book: Book) {
        val live = book.openFixtures().filter { it.managed }
        assertEquals(4, live.size)
        assertEquals(Roster.pool, live.groupingBy { it.sport }.eachCount())
        val participants = live.flatMap { it.selections.filterNot { s -> s.code == "DRAW" }.map { s -> s.label } }
        assertEquals(participants.size, participants.distinct().size)
        live.forEach {
            assertTrue(it.event.startsWith("Demo"))
            assertTrue(it.endsAt - it.startsAt in 30_000..120_000)
        }
    }

    @Test
    fun `stopped simulator never owns fixtures and each expiry replaces the same sport`() {
        val now = AtomicLong(1_000_000)
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, clock = now::get)
                Simulator(book, wallet).use { simulator ->
                    FixtureScheduler(book, { ledger.isFailed }, now::get, Random(69), automatic = false).use { scheduler ->
                        assertEquals(Simulator.State.STOPPED, simulator.state)
                        assertPool(book)
                        val initial = book.openFixtures()
                        assertTrue(initial.groupingBy { it.endsAt / 1000 }.eachCount().values.max() <= 3)
                        now.set(initial.minOf { it.endsAt })
                        val expired = initial.filter { it.endsAt <= now.get() }
                        scheduler.tick()
                        assertPool(book)
                        awaitSettled(expired)
                        assertTrue(expired.all { it.result in it.odds.keys + "VOID" })
                        val count = book.fixtures().size
                        repeat(10) { scheduler.tick() }
                        assertEquals(count, book.fixtures().size, "repeat ticks must not replace twice")
                        val early = book.openFixtures().first()
                        book.settle(early.id, early.selections.last().code)
                        assertPool(book)
                        assertEquals(early.selections.last().code, early.result)
                        assertEquals(Simulator.State.STOPPED, simulator.state)
                    }
                }
            }
        }
    }

    @Test
    fun `restart preserves deadlines catches up expired fixtures and leaves unmanaged fixtures alone`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        val now = AtomicLong(1_000_000)
        var deadlines = emptyMap<String, Long>()
        Ledger.open(config(dir, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model, dir, now::get)
                book.addFixture(Random(0))
                FixtureScheduler(book, { ledger.isFailed }, now::get, Random(5), false).use {
                    deadlines = book.openFixtures().filter { it.managed }.associate { it.id to it.endsAt }
                }
            }
        }
        now.set(deadlines.values.min())
        Ledger.open(config(dir, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model, dir, now::get)
                FixtureScheduler(book, { ledger.isFailed }, now::get, Random(6), false).use {
                    assertPool(book)
                    deadlines.filterValues { it > now.get() }.forEach { (id, end) -> assertEquals(end, book.fixture(id).endsAt) }
                    awaitSettled(deadlines.filterValues { it <= now.get() }.keys.map(book::fixture))
                    assertTrue(book.fixture("F1").isOpen)
                    assertFalse(book.fixture("F1").managed)
                }
            }
        }
    }

    @Test
    fun `a previously larger managed pool drains at its saved deadlines without cancelling bets`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        val now = AtomicLong(1_000_000)
        var deadlines = emptyMap<String, Long>()
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 10_000, "EUR", "larger-pool-funds")
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, dir, now::get)
                for (sport in Roster.Sport.entries) repeat(5) {
                    val f = book.addFixture(Random(sport.ordinal * 5 + it), sport, managed = true, clock = now::get)
                    book.placeBet("p", f.id, f.selections.first().code, 100, "old-${f.id}")
                }
                deadlines = book.openFixtures().associate { it.id to it.endsAt }
                assertEquals(20, deadlines.size)
            }
        }
        Ledger.open(config(dir, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model, dir, now::get)
                FixtureScheduler(book, { ledger.isFailed }, now::get, Random(69), false).use { scheduler ->
                    assertEquals(deadlines, book.openFixtures().associate { it.id to it.endsAt })
                    assertEquals(20, book.openFixtures().size)
                    now.set(deadlines.values.max())
                    scheduler.tick()
                    awaitSettled(deadlines.keys.map(book::fixture))
                    assertPool(book)
                    assertTrue(deadlines.keys.all { book.fixture(it).settlementComplete })
                    Audit(ledger, dir, TestSupport.LEDGER_ID, listOf(signer.publicKey)).use { audit ->
                        assertTrue(audit.fullAudit().report.ok)
                    }
                }
            }
        }
    }

    @Test
    fun `legacy open definitions receive durable timers`() {
        val dir = tempDir()
        val state = Properties().apply {
            setProperty("count", "1")
            mapOf("id" to "F1", "home" to "Old home", "away" to "Old away",
                "odds.HOME" to "200", "odds.DRAW" to "300", "odds.AWAY" to "250").forEach { (k, v) ->
                setProperty("fixture.0.$k", v)
            }
        }
        Files.newOutputStream(dir.resolve(Book.FIXTURES_FILE)).use { state.store(it, null) }
        Ledger.open(config(dir, Ed25519TreeHeadSigner.generate())).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model, dir)
                FixtureScheduler(book, { ledger.isFailed }, random = Random(1), automatic = false).use {
                    assertPool(book)
                    assertTrue(book.fixture("F1").managed)
                    val saved = Properties().apply { Files.newInputStream(dir.resolve(Book.FIXTURES_FILE)).use(::load) }
                    assertEquals("true", saved.getProperty("fixture.0.managed"))
                    assertTrue(saved.getProperty("fixture.0.endsAt").toLong() > 0)
                }
            }
        }
    }

    @Test
    fun `failed ledger pauses without opening fixtures or retrying each tick`() {
        val failed = AtomicBoolean(false)
        var checks = 0
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model)
                FixtureScheduler(book, { checks++; failed.get() }, random = Random(1), automatic = false).use { scheduler ->
                    val ids = book.fixtures().map { it.id }
                    failed.set(true)
                    scheduler.tick()
                    val stoppedChecks = checks
                    repeat(10) { scheduler.tick() }
                    assertEquals(stoppedChecks, checks)
                    assertEquals(ids, book.fixtures().map { it.id })
                    assertTrue(scheduler.reason!!.contains("failed-stopped"))
                }
            }
        }
    }

    @Test
    fun `legacy adoption preserves surplus bets without exceeding sport quotas on restart`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        val state = Properties().apply {
            setProperty("count", "7")
            repeat(7) { index ->
                mapOf("id" to "F${index + 1}", "home" to "Old home $index", "away" to "Old away $index",
                    "odds.HOME" to "200", "odds.DRAW" to "300", "odds.AWAY" to "250").forEach { (key, value) ->
                    setProperty("fixture.$index.$key", value)
                }
            }
        }
        Files.newOutputStream(dir.resolve(Book.FIXTURES_FILE)).use { state.store(it, null) }
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 1000, "EUR", "surplus-funds")
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, dir)
                book.placeBet("p", "F7", "HOME", 100, "surplus-bet")
                FixtureScheduler(book, { ledger.isFailed }, automatic = false).use {
                    assertPool(book)
                    assertTrue(book.fixture("F7").isOpen)
                    assertFalse(book.fixture("F7").managed)
                    assertEquals(0L, book.fixture("F7").endsAt)
                    model.catchUp()
                    assertTrue(model.bet("surplus-bet")!!.isOpen)
                }
            }
        }
        Ledger.open(config(dir, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model, dir)
                FixtureScheduler(book, { ledger.isFailed }, automatic = false).use {
                    assertPool(book)
                    assertFalse(book.fixture("F7").managed)
                    book.settle("F7", "VOID")
                    assertEquals("VOIDED", model.bet("surplus-bet")!!.outcome)
                    assertPool(book)
                }
            }
        }
    }

    @Test
    fun `missing golf definitions remain frozen and round trip across two restarts`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 1000, "EUR", "lost-golf-funds")
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, dir)
                val golf = book.addFixture(sport = Roster.Sport.GOLF)
                golf.selections.forEach { book.placeBet("p", golf.id, it.code, 100, "lost-${it.code}") }
            }
        }
        Files.delete(dir.resolve(Book.FIXTURES_FILE))
        repeat(2) {
            Ledger.open(config(dir, signer)).use { ledger ->
                ReadModel(ledger).use { model ->
                    val book = Book(SportsbookWallet(ledger), model, dir)
                    val frozen = book.fixture("F1")
                    assertEquals("LEGACY_UNRESOLVED", frozen.result)
                    assertEquals(setOf("HOME", "DRAW", "AWAY", "P1", "P2", "P3"), frozen.odds.keys)
                    assertFalse(frozen.isOpen)
                    assertTrue(model.allBets().all { it.isOpen })
                    assertFailsWith<IllegalArgumentException> { book.settle(frozen.id, "VOID") }
                }
            }
        }
        val state = Properties().apply {
            Files.newInputStream(dir.resolve(Book.FIXTURES_FILE)).use(::load)
            setProperty("fixture.0.result", "")
        }
        Files.newOutputStream(dir.resolve(Book.FIXTURES_FILE)).use { state.store(it, null) }
        Ledger.open(config(dir, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                assertFailsWith<IllegalStateException> { Book(SportsbookWallet(ledger), model, dir) }
            }
        }
    }

    @Test
    fun `simulator never bets on unmanaged fixtures and still bets on scheduled markets`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model)
                book.addFixture(managed = false)
                var scheduledId = ""
                Simulator(book, wallet).use { simulator ->
                    simulator.configure(10, 600)
                    simulator.start()
                    val trafficDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (simulator.succeeded < 20 && System.nanoTime() < trafficDeadline) Thread.sleep(10)
                    assertTrue(simulator.succeeded >= 20, "customers must actually generate traffic")
                    assertEquals(0L, simulator.bets.get(), "an unmanaged fixture must not receive simulated bets")
                    scheduledId = book.addFixture(sport = Roster.Sport.NFL, managed = true).id
                    val betDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (simulator.bets.get() == 0L && System.nanoTime() < betDeadline) Thread.sleep(10)
                    assertTrue(simulator.bets.get() > 0, "scheduled markets must still receive simulated bets")
                }
                model.catchUp()
                assertTrue(model.allBets().isNotEmpty())
                assertTrue(model.allBets().all { it.fixtureId == scheduledId })
            }
        }
    }

    @Test
    fun `close waits for admitted settlement completion while replacements remain available`() {
        val now = AtomicLong(1_000_000)
        val delayed = AtomicBoolean(false)
        val completions = ConcurrentLinkedQueue<Runnable>()
        val executor = java.util.concurrent.Executor { task -> if (delayed.get()) completions.add(task) else task.run() }
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate()).copy(completionExecutor = executor)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 1000, "EUR", "close-funds")
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, clock = now::get)
                val scheduler = FixtureScheduler(book, { ledger.isFailed }, now::get, Random(5), false)
                val fixture = book.openFixtures().first()
                book.placeBet("p", fixture.id, fixture.selections.first().code, 100, "close-bet")
                delayed.set(true)
                now.set(fixture.endsAt)
                scheduler.tick()
                assertPool(book)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (completions.isEmpty() && System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue(completions.isNotEmpty())
                val finished = CountDownLatch(1)
                val closer = Thread.ofVirtual().start { scheduler.close(); finished.countDown() }
                try {
                    assertFalse(finished.await(100, TimeUnit.MILLISECONDS), "close must drain the admitted future")
                } finally {
                    delayed.set(false)
                    while (true) (completions.poll() ?: break).run()
                    closer.join(10_000)
                }
                assertEquals(0, finished.count)
                assertTrue(fixture.settlementComplete)
                assertEquals(1, (0 until ledger.head().size).count { ledger.entry(it).entry.idempotencyKey == "bet:close-bet:settle" })
            }
        }
    }

    @Test
    fun `uncertain archive appends block concurrent catalogue writes until recovery`() {
        for (partial in listOf(true, false)) {
            val dir = tempDir()
            val signer = Ed25519TreeHeadSigner.generate()
            Ledger.open(config(dir, signer)).use { ledger ->
                val wallet = SportsbookWallet(ledger)
                wallet.deposit("p", 1000, "EUR", "append-funds")
                ReadModel(ledger).use { model ->
                    val book = Book(wallet, model, dir)
                    val first = book.addFixture()
                    val second = book.addFixture()
                    book.placeBet("p", first.id, "HOME", 100, "append-first")
                    book.placeBet("p", second.id, "HOME", 100, "append-second")
                    book.publishResult(first.id, "VOID")
                    book.publishResult(second.id, "VOID")
                    val forces = AtomicInteger()
                    book.archiveForce = { channel ->
                        forces.incrementAndGet()
                        if (partial) channel.truncate(channel.position() / 2)
                        channel.force(true)
                        throw IOException("uncertain archive append")
                    }
                    assertFailsWith<IOException> { book.settle(first.id, "VOID") }
                    val prefix = Files.readAllBytes(dir.resolve(Book.ARCHIVE_FILE))
                    race(4) { worker ->
                        when (worker) {
                            0 -> book.settle(first.id, "VOID")
                            1 -> book.settle(second.id, "VOID")
                            else -> book.addFixture()
                        }
                    }.forEach { assertTrue(it.exceptionOrNull() is IOException) }
                    assertEquals(1, forces.get(), "an uncertain append must prevent any later archive attempt")
                    assertTrue(prefix.contentEquals(Files.readAllBytes(dir.resolve(Book.ARCHIVE_FILE))))
                }
            }
            Ledger.open(config(dir, signer)).use { ledger ->
                ReadModel(ledger).use { model ->
                    val book = Book(SportsbookWallet(ledger), model, dir)
                    assertTrue(book.fixture("F1").settlementComplete)
                    book.settle("F1", "VOID")
                    book.settle("F2", "VOID")
                    assertEquals(2, Files.readAllLines(dir.resolve(Book.ARCHIVE_FILE)).size)
                    assertEquals("VOIDED", model.bet("append-first")!!.outcome)
                    assertEquals("VOIDED", model.bet("append-second")!!.outcome)
                }
            }
            Ledger.open(config(dir, signer)).use { ledger ->
                ReadModel(ledger).use { model ->
                    val book = Book(SportsbookWallet(ledger), model, dir)
                    assertEquals(2, book.fixtures().size)
                    assertTrue(book.fixtures().all { it.settlementComplete })
                }
            }
        }
    }

    @Test
    @Tag("soak")
    fun `accelerated 24 hour pool archives about 4600 fixtures with bounded active state and restores`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        val now = AtomicLong(1_000_000)
        var fixtureCount = 0
        Ledger.open(config(dir, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model, dir, now::get)
                FixtureScheduler(book, { ledger.isFailed }, now::get, Random(69), false).use { scheduler ->
                    repeat(86_400) { second ->
                        val live = book.openFixtures()
                        now.addAndGet(1000)
                        val expired = live.filter { it.endsAt <= now.get() }
                        scheduler.tick()
                        assertPool(book)
                        awaitSettled(expired)
                        if (second % 1000 == 0) {
                            val state = Properties().apply { Files.newInputStream(dir.resolve(Book.FIXTURES_FILE)).use(::load) }
                            assertTrue(state.getProperty("count").toInt() < 100)
                        }
                    }
                    fixtureCount = book.fixtures().size
                    assertTrue(fixtureCount in 4_400..4_800, "four markets with a 75-second mean should produce about 4,600 fixtures: $fixtureCount")
                }
            }
        }
        Ledger.open(config(dir, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model, dir, now::get)
                assertEquals(fixtureCount, book.fixtures().size)
                assertPool(book)
                val archived = Files.readAllLines(dir.resolve(Book.ARCHIVE_FILE)).size
                assertEquals(fixtureCount - 4, archived)
            }
        }
    }

    @Test
    fun `markets validate selections and odds implied results use their own codes`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 100_000, "EUR", "market-funds")
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model)
                FixtureScheduler(book, { ledger.isFailed }, random = Random(7), automatic = false).use { scheduler ->
                    for (sport in Roster.Sport.entries) {
                        val fixture = book.openFixtures().first { it.sport == sport }
                        assertEquals(if (sport in setOf(Roster.Sport.NFL, Roster.Sport.TENNIS)) 2 else 3, fixture.selections.size)
                        assertTrue(kotlin.math.abs(fixture.odds.values.sumOf { 100.0 / it } - 1.06) < 0.025)
                        assertFailsWith<IllegalArgumentException> { book.settle(fixture.id, "P9") }
                        if (sport != Roster.Sport.SOCCER)
                            assertFailsWith<IllegalArgumentException> { book.placeBet("p", fixture.id, "DRAW", 100, "bad-$sport") }
                        val results = (1..100_000).groupingBy { scheduler.weightedResult(fixture) }.eachCount()
                        val total = fixture.odds.values.sumOf { 1.0 / it }
                        for ((code, price) in fixture.odds) {
                            val expected = (1.0 / price / total) * if (sport == Roster.Sport.NFL) 0.99 else 1.0
                            assertTrue(kotlin.math.abs(results.getValue(code) / 100_000.0 - expected) < 0.01)
                        }
                        if (sport == Roster.Sport.NFL) assertTrue(results.getValue("VOID") in 800..1200)
                        val code = fixture.selections.first().code
                        book.placeBet("p", fixture.id, code, 100, "market-$sport")
                        book.settle(fixture.id, code)
                        assertEquals("WON", model.bet("market-$sport")!!.outcome)
                    }
                }
            }
        }
    }

    @Test
    fun `placements and cashouts racing expiry settle every accepted bet exactly once`() {
        val now = AtomicLong(1_000_000)
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 1_000_000, "EUR", "race-funds")
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, clock = now::get)
                FixtureScheduler(book, { ledger.isFailed }, now::get, Random(2), false).use { scheduler ->
                    val fixture = book.openFixtures().first()
                    book.placeBet("p", fixture.id, fixture.selections.first().code, 100, "cash-race")
                    val outcomes = race(18) { worker ->
                        when (worker) {
                            0 -> { now.set(fixture.endsAt); scheduler.tick() }
                            1 -> book.cashOut("cash-race")
                            else -> book.placeBet("p", fixture.id, fixture.selections.first().code, 100, "timer-race-$worker")
                        }
                    }
                    outcomes[0].getOrThrow()
                    awaitSettled(listOf(fixture))
                    model.catchUp()
                    assertTrue(model.openBetsOn(fixture.id).isEmpty())
                    val entries = (0 until ledger.head().size).map { ledger.entry(it).entry }
                    val placements = entries.filter { it.kind == "BET_PLACED" }
                    for (placement in placements) {
                        assertEquals(1, entries.count { it.idempotencyKey == "bet:${placement.metadata.getValue("betId")}:settle" })
                    }
                    assertPool(book)
                }
            }
        }
    }

    @Test
    fun `archive merges on restart keeps bet attribution and recovers only torn final records`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 1000, "EUR", "archive-funds")
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, dir)
                val golf = book.addFixture(Random(1), Roster.Sport.GOLF)
                book.placeBet("p", golf.id, "P2", 100, "archived-bet")
                book.settle(golf.id, "P2")
                book.settle(book.addFixture().id, "VOID")
                val active = Properties().apply { Files.newInputStream(dir.resolve(Book.FIXTURES_FILE)).use(::load) }
                assertEquals("0", active.getProperty("count"))
                assertEquals(2, Files.readAllLines(dir.resolve(Book.ARCHIVE_FILE)).size)
            }
        }
        Files.writeString(dir.resolve(Book.ARCHIVE_FILE), "torn partial record", APPEND)
        Ledger.open(config(dir, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model, dir)
                assertEquals("P2", book.fixture("F1").result)
                assertEquals("WON", model.bet("archived-bet")!!.outcome)
                book.settle(book.addFixture().id, "VOID")
                assertEquals(3, Files.readAllLines(dir.resolve(Book.ARCHIVE_FILE)).size)
            }
        }
        val archive = dir.resolve(Book.ARCHIVE_FILE)
        val bytes = Files.readAllBytes(archive)
        bytes[0] = if (bytes[0] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()
        Files.write(archive, bytes)
        Ledger.open(config(dir, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                assertFailsWith<IllegalStateException> { Book(SportsbookWallet(ledger), model, dir) }
            }
        }
    }

    @Test
    fun `five simulated minutes with timer settlements pass the full audit`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        val now = AtomicLong(1_000_000)
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, dir, now::get)
                FixtureScheduler(book, { ledger.isFailed }, now::get, Random(4), false).use { scheduler ->
                    Simulator(book, wallet).use { simulator ->
                        simulator.configure(20, 600)
                        simulator.start()
                        repeat(300) {
                            now.addAndGet(1000)
                            simulator.tick()
                            scheduler.tick()
                            assertPool(book)
                            awaitSettled(book.fixtures().filter { f -> f.result != null })
                        }
                    }
                }
                assertTrue(ledger.head().size > 100)
                Audit(ledger, dir, TestSupport.LEDGER_ID, listOf(signer.publicKey)).use { audit ->
                    assertTrue(audit.fullAudit().report.ok)
                }
            }
        }
    }
}
