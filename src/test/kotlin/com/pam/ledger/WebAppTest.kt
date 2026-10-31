package com.pam.ledger

import com.pam.ledger.web.Book
import com.pam.ledger.web.Audit
import com.pam.ledger.web.LedgerWebApp
import com.pam.ledger.web.ReadModel
import com.pam.ledger.web.WebOptions
import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import java.net.URI
import java.net.Socket
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the web console: the HTTP API end to end on a real server, and the one
 * concurrency rule the web layer owns (bets racing their fixture's settlement).
 */
class WebAppTest {
    private val http = HttpClient.newHttpClient()

    private fun call(
        app: LedgerWebApp,
        method: String,
        path: String,
        params: Map<String, Any> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        withOrigin: Boolean = true,
    ): Pair<Int, String> {
        val form = params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v.toString(), Charsets.UTF_8) }
        val uri = URI.create("http://127.0.0.1:${app.port}/api/$path" + if (method == "GET" && form.isNotEmpty()) "?$form" else "")
        val builder = HttpRequest.newBuilder(uri).method(method, if (method == "POST") HttpRequest.BodyPublishers.ofString(form) else HttpRequest.BodyPublishers.noBody())
            .header("Content-Type", "application/x-www-form-urlencoded")
        if (method == "POST" && withOrigin && headers.keys.none { it.equals("Origin", true) }) {
            builder.header("Origin", "http://127.0.0.1:${app.port}")
        }
        headers.forEach { (name, value) -> builder.header(name, value) }
        val req = builder.build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        return res.statusCode() to res.body()
    }

    private fun withApp(body: (LedgerWebApp) -> Unit) {
        val app = LedgerWebApp.start(WebOptions(port = 0, dataDir = tempDir()))
        try { body(app) } finally { app.close() }
    }

    @Test
    fun `customer API moves money, refuses overspending, and replays retried bets`() = withApp { app ->
        assertEquals(200, call(app, "POST", "players/alice/deposit", mapOf("amount" to 2500, "ref" to "t-dep")).first)

        val (status, body) = call(app, "POST", "players/alice/withdraw", mapOf("amount" to 99_999_999, "ref" to "t-wd"))
        assertEquals(409, status)
        assertTrue(body.contains("insufficient_funds"), body)

        val bet = mapOf("fixture" to "F1", "selection" to "HOME", "stake" to 1000, "betId" to "slip-1")
        val first = call(app, "POST", "players/alice/bets", bet)
        val retry = call(app, "POST", "players/alice/bets", bet)
        assertEquals(200, first.first)
        assertTrue(retry.second.contains("\"replayed\":true"), "a retried slip must replay, not bet twice: ${retry.second}")

        val (_, offer) = call(app, "GET", "bets/slip-1/cashout")
        assertTrue(offer.contains("\"offer\":850"), "85% cash-out offer on a 10.00 stake: $offer")
        assertEquals(400, call(app, "POST", "players/alice/bets", bet + ("selection" to "PINK") + ("betId" to "slip-2")).first)
    }

    @Test
    fun `regulator API audits cleanly and catches every tamper mode`() = withApp { app ->
        repeat(10) { call(app, "POST", "players/bob/deposit", mapOf("amount" to 100, "ref" to "r$it")) }
        val audit = call(app, "POST", "regulator/audit").second
        assertTrue(audit.contains("\"ok\":true"), audit)
        for (mode in listOf("edit", "rewrite", "truncate")) {
            val r = call(app, "POST", "regulator/tamper", mapOf("mode" to mode)).second
            assertTrue(r.contains("\"ok\":false"), "tamper mode '$mode' must be detected: $r")
        }
        // The live ledger was untouched by the tamper lab.
        assertTrue(call(app, "POST", "regulator/audit").second.contains("\"ok\":true"))
        val proof = call(app, "GET", "proof/inclusion", mapOf("seq" to 3))
        assertEquals(200, proof.first)
        assertTrue(proof.second.contains("\"verified\":true"), proof.second)
    }

    /** Tamper demonstrations must attack signed history even as new unsigned entries arrive. */
    @Test
    fun `every tamper mode is caught while the simulator appends`() = withApp { app ->
        repeat(10) { call(app, "POST", "players/bob/deposit", mapOf("amount" to 100, "ref" to "signed-$it")) }
        assertEquals(200, call(app, "POST", "simulator", mapOf("customers" to 20, "opsPerMinute" to 600, "autoFixtures" to "false", "action" to "start")).first)
        try {
            repeat(10) {
                for (mode in listOf("edit", "rewrite", "truncate")) {
                    val (status, body) = call(app, "POST", "regulator/tamper", mapOf("mode" to mode))
                    assertEquals(200, status, body)
                    assertTrue(body.contains("\"ok\":false"), "concurrent '$mode' must change signed history: $body")
                }
            }
        } finally {
            call(app, "POST", "simulator", mapOf("action" to "stop"))
        }
        assertTrue(call(app, "POST", "regulator/audit").second.contains("\"ok\":true"))
    }

    @Test
    fun `simulator starts, pauses, resumes and stops, and its customers appear in the ledger`() = withApp { app ->
        val start = call(app, "POST", "simulator", mapOf("customers" to 50, "opsPerMinute" to 600, "autoFixtures" to "true", "action" to "start"))
        assertTrue(start.second.contains("\"state\":\"RUNNING\""), start.second)
        Thread.sleep(1500)
        assertTrue(call(app, "POST", "simulator", mapOf("action" to "pause")).second.contains("\"state\":\"PAUSED\""))
        val paused = call(app, "GET", "simulator").second
        Thread.sleep(600)
        // Nothing new is launched while paused (operations already in flight may still finish).
        assertTrue(call(app, "GET", "simulator").second.contains("\"state\":\"PAUSED\""))
        assertTrue(call(app, "POST", "simulator", mapOf("action" to "resume")).second.contains("\"state\":\"RUNNING\""))
        assertTrue(call(app, "POST", "simulator", mapOf("action" to "stop")).second.contains("\"state\":\"STOPPED\""))

        assertTrue(Regex("\"succeeded\":(\\d+)").find(paused)!!.groupValues[1].toLong() > 0, "the simulator should have done work: $paused")
        val events = call(app, "GET", "simulator/events", mapOf("after" to 0)).second
        assertTrue(events.contains("sim-000"), "events should name automated customers")
        val eventIds = Regex("\"id\":(\\d+)").findAll(events).map { it.groupValues[1].toLong() }.toList()
        assertTrue(eventIds.zipWithNext().all { (a, b) -> a < b }, "event IDs must follow publication order: $events")
        val lastId = Regex("\"lastId\":(\\d+)").find(events)!!.groupValues[1].toLong()
        assertEquals(eventIds.last(), lastId)
        val emptyPage = call(app, "GET", "simulator/events", mapOf("after" to lastId)).second
        assertTrue(emptyPage.contains("\"events\":[]"), emptyPage)
        assertTrue(emptyPage.contains("\"lastId\":$lastId"), emptyPage)
        val resetPage = call(app, "GET", "simulator/events", mapOf("after" to lastId + 100)).second
        assertTrue(resetPage.contains("\"events\":[]"), resetPage)
        assertTrue(resetPage.contains("\"lastId\":$lastId"), resetPage)
        // Automated customers are ordinary players: visible to the customer API and auditable.
        val players = call(app, "GET", "players", mapOf("q" to "sim-")).second
        assertTrue(players.contains("\"automated\":true"), players)
        val audit = call(app, "POST", "regulator/audit").second
        assertTrue(audit.contains("\"ok\":true"), audit)
    }

    @Test
    fun `input errors are reported as 400 and unknown routes as 404`() = withApp { app ->
        assertEquals(400, call(app, "POST", "simulator", mapOf("customers" to 0, "opsPerMinute" to 60)).first)
        assertEquals(400, call(app, "POST", "regulator/tamper", mapOf("mode" to "explode")).first)
        assertEquals(404, call(app, "GET", "no/such/route").first)
        assertEquals(404, call(app, "POST", "fixtures/F999/settle", mapOf("result" to "HOME")).first)
    }

    @Test
    fun `cross-origin mutations are rejected and encoded path segments decode once`() = withApp { app ->
        val before = call(app, "GET", "players/alice").second
        val blocked = call(
            app, "POST", "players/alice/deposit", mapOf("amount" to 10, "ref" to "cross-site"),
            mapOf("Origin" to "https://attacker.example"),
        )
        assertEquals(403, blocked.first)
        assertTrue(blocked.second.contains("cross_origin"), blocked.second)
        assertEquals(before, call(app, "GET", "players/alice").second)
        assertEquals(403, call(app, "POST", "players/alice/deposit", mapOf("amount" to 10, "ref" to "no-origin"), withOrigin = false).first)

        assertTrue(call(app, "GET", "players/a%2Bb").second.contains("\"id\":\"a+b\""))
        assertTrue(call(app, "GET", "players/a%2Fb").second.contains("\"id\":\"a/b\""))

        val invalidHost = Socket("127.0.0.1", app.port).use { socket ->
            socket.getOutputStream().write(
                "GET /api/overview HTTP/1.1\r\nHost: attacker.example:${app.port}\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.UTF_8),
            )
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
        assertTrue(invalidHost.startsWith("HTTP/1.1 403"), invalidHost)
    }

    @Test
    fun `fixture definitions and results survive restart without reusing ids`() {
        val directory = tempDir()
        val first = LedgerWebApp.start(WebOptions(port = 0, dataDir = directory))
        val teams = Regex("\"id\":\"F1\",\"home\":\"([^\"]+)\",\"away\":\"([^\"]+)\"")
            .find(call(first, "GET", "fixtures").second)!!.groupValues.drop(1)
        assertEquals(200, call(first, "POST", "players/alice/bets", mapOf(
            "fixture" to "F1", "selection" to "HOME", "stake" to 100, "betId" to "restart-slip",
        )).first)
        first.close()

        val restarted = LedgerWebApp.start(WebOptions(port = 0, dataDir = directory))
        try {
            val fixtures = call(restarted, "GET", "fixtures").second
            assertTrue(fixtures.contains("\"id\":\"F1\",\"home\":\"${teams[0]}\",\"away\":\"${teams[1]}\""), fixtures)
            val newFixture = call(restarted, "POST", "fixtures").second
            assertTrue(newFixture.contains("\"id\":\"F7\""), newFixture)
            assertEquals(200, call(restarted, "POST", "fixtures/F7/settle", mapOf("result" to "AWAY")).first)
            assertTrue(call(restarted, "GET", "players/alice").second.contains("\"betId\":\"restart-slip\"") &&
                call(restarted, "GET", "players/alice").second.contains("\"open\":true"))
            assertEquals(200, call(restarted, "POST", "fixtures/F1/settle", mapOf("result" to "AWAY")).first)
            assertTrue(call(restarted, "GET", "players/alice").second.contains("\"outcome\":\"LOST\""))
            val retry = call(restarted, "POST", "players/alice/bets", mapOf(
                "fixture" to "F1", "selection" to "HOME", "stake" to 100, "betId" to "restart-slip",
            ))
            assertEquals(200, retry.first)
            assertTrue(retry.second.contains("\"replayed\":true"), retry.second)
        } finally {
            restarted.close()
        }
    }

    @Test
    fun `player audit compares balances from one captured ledger prefix`() = withApp { app ->
        val outcomes = race(2) { worker ->
            if (worker == 0) {
                repeat(30) { index ->
                    assertEquals(200, call(app, "POST", "players/alice/deposit", mapOf(
                        "amount" to 1, "ref" to "audit-race-$index",
                    )).first)
                }
            } else {
                repeat(30) {
                    val audit = call(app, "GET", "regulator/players/alice").second
                    assertTrue(audit.contains("\"reconciles\":true"), audit)
                }
            }
        }
        outcomes.forEach { it.getOrThrow() }
    }

    @Test
    fun `legacy fixture ids are frozen and not reused when state was never persisted`() {
        val directory = tempDir()
        val first = LedgerWebApp.start(WebOptions(port = 0, dataDir = directory))
        call(first, "POST", "players/alice/bets", mapOf(
            "fixture" to "F1", "selection" to "HOME", "stake" to 100, "betId" to "legacy-slip",
        ))
        first.close()
        java.nio.file.Files.deleteIfExists(directory.resolve("demo-fixtures.properties"))

        val restarted = LedgerWebApp.start(WebOptions(port = 0, dataDir = directory))
        try {
            val fixtures = call(restarted, "GET", "fixtures").second
            assertTrue(fixtures.contains("LEGACY_UNRESOLVED"), fixtures)
            assertEquals(400, call(restarted, "POST", "fixtures/F1/settle", mapOf("result" to "HOME")).first)
            assertTrue(call(restarted, "POST", "fixtures").second.contains("\"id\":\"F2\""))
            assertTrue(call(restarted, "GET", "players/alice").second.contains("\"open\":true"))
        } finally {
            restarted.close()
        }
    }

    @Test
    fun `payout arithmetic is exact and settlement retries are harmless`() {
        val large = ReadModel.BetView(
            betId = "large", playerId = "p", fixtureId = "F1", selection = "HOME",
            oddsHundredths = 210, stake = 100_000_000_000_000_000L, placedSeq = 0, placedAtMicros = 0,
        )
        assertEquals(210_000_000_000_000_000L, large.potentialPayout)

        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { readModel ->
                val book = Book(wallet, readModel)
                val fixture = book.addFixture(Random(1))
                wallet.deposit("large", 110_000_000_000_000_000L, "EUR", "large-funds")
                val betId = "large-cashout"
                book.placeBet("large", fixture.id, "HOME", 110_000_000_000_000_000L, betId)
                assertEquals(93_500_000_000_000_000L, book.cashOutOffer(betId))
                wallet.deposit("other", 1_000, "EUR", "other-funds")
                book.placeBet("other", fixture.id, "HOME", 100, "other-slip")
                assertEquals(listOf(betId), book.openBetsForPlayer("large").map { it.betId })

                wallet.deposit("overflow", 8_900_000_000_000_000_000L, "EUR", "overflow-funds")
                val before = ledger.head().size
                assertFailsWith<IllegalArgumentException> {
                    book.placeBet("overflow", fixture.id, "HOME", 8_900_000_000_000_000_000L, "overflow-slip")
                }
                assertEquals(before, ledger.head().size, "an unrepresentable payout must be rejected before commit")

                val first = book.settle(fixture.id, "HOME")
                assertTrue(fixture.settlementComplete)
                val afterSettlement = ledger.head().size
                val retry = book.settle(fixture.id, "HOME")
                assertEquals(afterSettlement, ledger.head().size)
                assertEquals(0, retry.won + retry.lost + retry.voided)
                assertTrue(first.won + first.lost > 0)
                assertTrue(book.openBetsForPlayer("large").isEmpty())
                assertFailsWith<IllegalArgumentException> { book.settle(fixture.id, "AWAY") }
            }
        }
    }

    @Test
    fun `settlement waits for a placement after its request times out`() {
        val directory = tempDir()
        val delayed = AtomicBoolean(false)
        val queuedCompletions = ConcurrentLinkedQueue<Runnable>()
        val executor = Executor { task -> if (delayed.get()) queuedCompletions.add(task) else task.run() }
        Ledger.open(config(directory, Ed25519TreeHeadSigner.generate()).copy(completionExecutor = executor)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { readModel ->
                val book = Book(wallet, readModel).apply { placementTimeout = java.time.Duration.ofMillis(25) }
                val fixture = book.addFixture(Random(3))
                wallet.deposit("p", 10_000, "EUR", "funds")
                delayed.set(true)
                assertFailsWith<OutcomeUnknownException> {
                    book.placeBet("p", fixture.id, "HOME", 100, "unknown-slip")
                }

                val settlement = CompletableFuture.supplyAsync { book.settle(fixture.id, "HOME") }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (fixture.result == null && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals("HOME", fixture.result)
                assertFalse(settlement.isDone, "settlement must await the still-pending ledger outcome")
                delayed.set(false)
                while (true) (queuedCompletions.poll() ?: break).run()
                assertEquals(1, settlement.get(5, TimeUnit.SECONDS).won)
                readModel.catchUp()
                assertTrue(readModel.openBetsOn(fixture.id).isEmpty())
            }
        }
    }

    @Test
    fun `audit history retains every checkpoint written between polls`() {
        val directory = tempDir()
        Ledger.open(config(directory, Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            Audit(ledger, directory, "test-ledger", listOf(ledger.publicKey)).use { audit ->
                repeat(3) { index ->
                    wallet.deposit("p", 1, "EUR", "checkpoint-$index")
                    ledger.checkpoint()
                }
                assertEquals(listOf(1L, 2L, 3L), audit.checkpoints().map { it.head.treeSize })
                assertTrue(audit.checkpointOfSize(2) != null)
            }
        }
    }

    /**
     * The race the per-fixture lock in Book exists for: bets keep arriving on a fixture
     * while it is settled. Afterwards no bet on it may remain open, every bet placed must
     * have been settled exactly once, and placements after the result are refused.
     */
    @Test
    fun `bets racing their fixture's settlement are never left open`() {
        repeat(5) { round ->
            Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
                val wallet = SportsbookWallet(ledger)
                ReadModel(ledger).use { readModel ->
                    val book = Book(wallet, readModel)
                    val fixture = book.addFixture(Random(round))
                    val players = (1..16).map { "p$it" }
                    players.forEach { wallet.deposit(it, 1_000_00, "EUR", "d-$it") }

                    val results = race(17) { t ->
                        if (t == 16) {
                            Thread.sleep(5)
                            book.settle(fixture.id, "HOME")
                            0
                        } else {
                            var placed = 0
                            repeat(40) {
                                try {
                                    book.placeBet(players[t], fixture.id, "HOME", 100, UUID.randomUUID().toString())
                                    placed++
                                } catch (e: IllegalArgumentException) {
                                    // refused: the fixture had already been settled
                                }
                            }
                            placed
                        }
                    }
                    results.forEach { it.getOrThrow() }
                    readModel.catchUp()
                    assertTrue(readModel.openBetsOn(fixture.id).isEmpty(), "round $round: bets left open on a settled fixture")
                    // Every placement has exactly one settlement, and every winner was paid.
                    val placements = (0 until ledger.head().size).map { ledger.entry(it).entry }.count { it.kind == "BET_PLACED" }
                    val settlements = (0 until ledger.head().size).map { ledger.entry(it).entry }.count { it.kind == "BET_WON" }
                    assertEquals(placements, settlements, "round $round")
                    assertEquals(results.sumOf { it.getOrThrow() }, placements, "round $round")
                }
            }

        }
    }

    /** Customer queries use only that customer's index and stop at the requested limit. */
    @Test
    fun `customer bet history returns the newest forty with current settlements`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("alice", 1000, "EUR", "alice-fund")
            wallet.deposit("bob", 1000, "EUR", "bob-fund")
            repeat(100) { i ->
                wallet.placeBet("bob", "bob-$i", 1, "EUR", "F1|HOME|200")
                if (i < 60) {
                    wallet.placeBet("alice", "alice-$i", 1, "EUR", "F1|HOME|200")
                    if (i % 2 == 0) wallet.settleLoss("alice-$i")
                }
            }
            ReadModel(ledger).use { model ->
                model.catchUp()
                val recent = model.betsForPlayer("alice", 40)
                assertEquals((20..59).map { "alice-$it" }, recent.map { it.betId })
                assertEquals(20, recent.count { !it.isOpen })
                assertEquals(60, model.betsForPlayer("alice").size)
                assertTrue(model.betsForPlayer("absent", 40).isEmpty())
                assertTrue(model.betsForPlayer("alice", 0).isEmpty())
            }
        }
    }

    /** A pending placement must retain its settlement guard past the normal 30-second timeout. */
    @Test
    fun `queue overload cannot release a pending placement before settlement`() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val settling = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant {
                if (armed.compareAndSet(true, false)) {
                    blocked.countDown()
                    check(release.await(45, TimeUnit.SECONDS)) { "test did not release the writer" }
                }
                return Instant.now()
            }
        }
        val cfg = config(tempDir(), Ed25519TreeHeadSigner.generate()).copy(
            clock = clock, queueCapacity = 1, maxBatchSize = 1, submitTimeout = Duration.ofMillis(100),
        )
        Ledger.open(cfg).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 1000, "EUR", "fund")
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model)
                val fixture = book.addFixture(Random(0))
                armed.set(true)
                val results = race(3) { t ->
                    when (t) {
                        0 -> assertFailsWith<OutcomeUnknownException> {
                            book.placeBet("p", fixture.id, "HOME", 100, "pending")
                        }
                        1 -> {
                            check(blocked.await(5, TimeUnit.SECONDS))
                            // Fill the only queue slot while the original bet is still uncommitted.
                            val queued = ledger.submit(TransactionRequest("queued", "DEPOSIT", listOf(
                                Posting(SportsbookWallet.Accounts.pspClearing("EUR"), "EUR", -1),
                                Posting(SportsbookWallet.Accounts.cash("p", "EUR"), "EUR", 1),
                            )))
                            assertFailsWith<OverloadedException> {
                                book.placeBet("p", fixture.id, "HOME", 100, "refused")
                            }
                            settling.countDown()
                            book.settle(fixture.id, "HOME")
                            queued.join()
                            assertTrue(fixture.settlementComplete)
                        }
                        else -> try {
                            check(settling.await(5, TimeUnit.SECONDS))
                            Thread.sleep(31_000)
                            assertFalse(fixture.settlementComplete, "settlement completed underneath a pending placement")
                            assertTrue(ledger.findByIdempotencyKey("bet:pending:place") == null)
                        } finally {
                            release.countDown()
                        }
                    }
                }
                results.forEach { it.getOrThrow() }
                model.catchUp()
                assertTrue(model.openBetsOn(fixture.id).isEmpty())
                assertTrue(wallet.isSettled("pending"))
            }
        }
    }
}
