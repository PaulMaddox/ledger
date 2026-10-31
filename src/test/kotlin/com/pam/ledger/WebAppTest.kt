package com.pam.ledger

import com.pam.ledger.web.Book
import com.pam.ledger.web.Audit
import com.pam.ledger.web.LedgerWebApp
import com.pam.ledger.web.ReadModel
import com.pam.ledger.web.WebOptions
import com.pam.ledger.web.Simulator
import com.pam.ledger.web.parseArgs
import com.pam.ledger.web.replayPlayerAudit
import com.pam.ledger.web.allowedRequestHosts
import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import java.net.URI
import java.net.Socket
import java.net.ServerSocket
import java.net.BindException
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
import java.util.Locale
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.CRC32C
import java.math.BigInteger
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

    private fun rawRequest(
        app: LedgerWebApp, host: String, method: String = "GET", origin: String? = null,
        path: String = if (method == "POST") "/api/players/alice/deposit" else "/api/overview",
        fetchSite: String? = null,
    ): String {
        val body = if (method == "POST") "amount=1&ref=allowed-host-test" else ""
        return Socket("127.0.0.1", app.port).use { socket ->
            socket.soTimeout = 5_000
            val request = buildString {
                append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n")
                if (origin != null) append("Origin: $origin\r\n")
                if (fetchSite != null) append("Sec-Fetch-Site: $fetchSite\r\n")
                append("Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n$body")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test
    fun `configured HTTPS proxy origin allows mutations without trusting forwarding headers`() {
        val app = LedgerWebApp.start(WebOptions(port = 0, dataDir = tempDir(), publicOrigin = "https://ledger.example"))
        try {
            val accepted = rawRequest(app, "ledger.example", "POST", "https://ledger.example")
            assertTrue(accepted.startsWith("HTTP/1.1 200"), accepted)
            assertEquals(200, call(app, "GET", "overview").first)
            for (origin in listOf("http://ledger.example", "https://attacker.example", "https://ledger.example:8443", "https://ledger.example/path")) {
                val refused = rawRequest(app, "ledger.example", "POST", origin)
                assertTrue(refused.startsWith("HTTP/1.1 403") && refused.contains("cross_origin"), refused)
            }
            for (host in listOf("attacker.example", "ledger.example:80", "ledger.example:8443")) {
                val refused = rawRequest(app, host)
                assertTrue(refused.startsWith("HTTP/1.1 403") && refused.contains("invalid_host"), refused)
            }
            val spoofed = call(app, "POST", "players/alice/deposit", mapOf("amount" to 1, "ref" to "spoof"),
                mapOf("Origin" to "https://ledger.example", "X-Forwarded-Host" to "ledger.example", "X-Forwarded-Proto" to "https"))
            assertEquals(403, spoofed.first)
            val missing = rawRequest(app, "ledger.example", "POST")
            assertTrue(missing.startsWith("HTTP/1.1 403"), missing)
        } finally { app.close() }
    }

    @Test
    fun `public origin options reject non HTTPS and non origin URLs`() {
        assertEquals("https://ledger.example:8443", parseArgs(arrayOf("--public-origin", "https://ledger.example:8443")).publicOrigin)
        for (invalid in listOf("http://ledger.example", "https://ledger.example/", "https://user@ledger.example",
            "https://ledger.example?x=1", "https://ledger.example#fragment", "https://*.example",
            "https://0.0.0.0", "https://ledger.example:0", "https://ledger.example:65536")) {
            assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("--public-origin", invalid)) }
        }
        val app = LedgerWebApp.start(WebOptions(port = 0, dataDir = tempDir(), publicOrigin = "https://ledger.example:8443"))
        try {
            val accepted = rawRequest(app, "ledger.example:8443", "POST", "https://ledger.example:8443")
            assertTrue(accepted.startsWith("HTTP/1.1 200"), accepted)
            assertTrue(rawRequest(app, "ledger.example").startsWith("HTTP/1.1 403"))
        } finally { app.close() }
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
        assertTrue(offer.contains("\"offer\":\"850\""), "85% cash-out offer on a 10.00 stake: $offer")
        assertEquals(400, call(app, "POST", "players/alice/bets", bet + ("selection" to "PINK") + ("betId" to "slip-2")).first)
    }

    @Test
    fun `money values in the API remain exact decimal strings`() = withApp { app ->
        val amount = 9_007_199_254_740_995L
        assertEquals(200, call(app, "POST", "players/alice/deposit", mapOf("amount" to amount, "ref" to "large-exact")).first)
        val bet = call(app, "POST", "players/alice/bets", mapOf(
            "fixture" to "F1", "selection" to "HOME", "stake" to amount, "betId" to "large-exact-bet",
        ))
        assertEquals(200, bet.first, bet.second)
        val detail = call(app, "GET", "players/alice").second
        assertTrue(detail.contains("\"cash\":\"10000\""), detail)
        assertTrue(detail.contains("\"stake\":\"$amount\""), detail)
        assertTrue(detail.contains("\"potentialPayout\":\""), detail)
        assertTrue(detail.contains("\"amount\":\"-$amount\""), detail)
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
    fun `cross-site page navigation is allowed without weakening host or mutation checks`() = withApp { app ->
        for (path in listOf("/", "/index.html")) {
            val page = rawRequest(app, "localhost:${app.port}", origin = "https://docs.example", path = path, fetchSite = "cross-site")
            assertTrue(page.startsWith("HTTP/1.1 200"), page)
            val refused = rawRequest(app, "attacker.example:${app.port}", path = path, fetchSite = "cross-site")
            assertTrue(refused.startsWith("HTTP/1.1 403"), refused)
        }
        val mutation = rawRequest(app, "localhost:${app.port}", "POST", "http://localhost:${app.port}", fetchSite = "cross-site")
        assertTrue(mutation.startsWith("HTTP/1.1 403"), mutation)
    }

    @Test
    fun `wildcard binding serves only explicitly allowed external hosts with same-origin mutations`() {
        val options = WebOptions(port = 0, host = "0.0.0.0", dataDir = tempDir(),
            allowedHosts = setOf("ledger.example", "192.0.2.10"))
        assertEquals(options.allowedRequestHosts(), options.copy(host = "::").allowedRequestHosts())
        LedgerWebApp.start(options).use { app ->
            for (host in listOf("ledger.example", "192.0.2.10", "localhost", "[::1]")) {
                val response = rawRequest(app, "$host:${app.port}")
                assertTrue(response.startsWith("HTTP/1.1 200"), response)
            }
            val allowed = rawRequest(app, "ledger.example:${app.port}", "POST", "http://ledger.example:${app.port}")
            assertTrue(allowed.startsWith("HTTP/1.1 200"), allowed)
            val wrongOrigin = rawRequest(app, "ledger.example:${app.port}", "POST", "http://attacker.example:${app.port}")
            assertTrue(wrongOrigin.startsWith("HTTP/1.1 403") && wrongOrigin.contains("cross_origin"), wrongOrigin)
            for (host in listOf("attacker.example:${app.port}", "ledger.example:1")) {
                val refused = rawRequest(app, host)
                assertTrue(refused.startsWith("HTTP/1.1 403") && refused.contains("invalid_host"), refused)
            }
        }
    }

    @Test
    fun `allowed host options accept exact hosts and reject origins ports and wildcards`() {
        val options = parseArgs(arrayOf("--host", "0.0.0.0", "--allowed-host", "Ledger.Example", "--allowed-host", "::1"))
        assertEquals("0.0.0.0", options.host)
        assertEquals(setOf("ledger.example", "::1"), options.allowedHosts)
        for (invalid in listOf("*", "0.0.0.0", "::", "https://ledger.example", "ledger.example:8080", "ledger.example/path", "user@ledger.example", "")) {
            assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("--allowed-host", invalid)) }
            assertFailsWith<IllegalArgumentException> { LedgerWebApp.start(WebOptions(allowedHosts = setOf(invalid))) }
        }
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
    fun `old unfinished fixtures remain visible after many fixtures are added`() = withApp { app ->
        assertEquals(200, call(app, "POST", "players/alice/bets", mapOf(
            "fixture" to "F1", "selection" to "HOME", "stake" to 100, "betId" to "old-fixture-open-bet",
        )).first)
        repeat(31) { assertEquals(200, call(app, "POST", "fixtures").first) }

        val fixtures = call(app, "GET", "fixtures").second
        assertTrue(fixtures.contains("\"id\":\"F1\"") && fixtures.contains("\"settlementComplete\":false"), fixtures)
        assertTrue(fixtures.contains("\"id\":\"F37\""), fixtures)
    }

    @Test
    fun `concurrent first startups preserve one signing key`() {
        val directory = tempDir()
        val attempts = race(2) { LedgerWebApp.start(WebOptions(port = 0, dataDir = directory)) }
        val started = attempts.mapNotNull { it.getOrNull() }.toMutableList()
        assertEquals(1, started.size, "the journal lock allows only one app to start")
        try {
            assertEquals(1, attempts.count { it.isFailure })
            val app = started.single()
            val firstKey = Regex("\"publicKey\":\"([^\"]+)\"")
                .find(call(app, "GET", "regulator/checkpoints").second)!!.groupValues[1]
            app.close()
            started.remove(app)

            LedgerWebApp.start(WebOptions(port = 0, dataDir = directory)).use { restarted ->
                val nextKey = Regex("\"publicKey\":\"([^\"]+)\"")
                    .find(call(restarted, "GET", "regulator/checkpoints").second)!!.groupValues[1]
                assertEquals(firstKey, nextKey)
            }
        } finally {
            started.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `player audit reconciles account snapshots while money movements commit`() = withApp { app ->
        val outcomes = race(2) { worker ->
            if (worker == 0) {
                repeat(30) { index ->
                    assertEquals(200, call(app, "POST", "players/alice/deposit", mapOf(
                        "amount" to 1, "ref" to "audit-race-$index",
                    )).first)
                    assertEquals(200, call(app, "POST", "players/alice/bets", mapOf(
                        "fixture" to "F1", "selection" to "HOME", "stake" to 1, "betId" to "audit-race-bet-$index",
                    )).first)
                    assertEquals(200, call(app, "POST", "bets/audit-race-bet-$index/cashout").first)
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
    fun `player audit reconciles engine snapshots at their own sequences and detects wrong balances`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 1_000, "EUR", "audit-funds")
            val cash = ledger.account(SportsbookWallet.Accounts.cash("p", "EUR"))!!
            wallet.placeBet("p", "audit-bet", 100, "EUR", "F1|HOME|200")
            val escrow = ledger.account(SportsbookWallet.Accounts.openBets("p", "EUR"))!!
            wallet.deposit("p", 10, "EUR", "audit-later")
            val committedSize = ledger.head().size
            val report = replayPlayerAudit(ledger, "p", cash, escrow, committedSize)
            assertEquals(true, report["reconciles"])
            assertEquals("1000", report["ledgerCash"])
            assertEquals("1000", report["replayedCash"])
            assertEquals("100", report["ledgerEscrow"])
            assertEquals("100", report["replayedEscrow"])
            assertEquals(cash.lastSeq, report["cashLastSeq"])
            assertEquals(escrow.lastSeq, report["escrowLastSeq"])
            assertEquals(false, replayPlayerAudit(ledger, "p", cash.copy(balance = 999), escrow, committedSize)["reconciles"])
            assertEquals(false, replayPlayerAudit(ledger, "p", cash, escrow.copy(balance = 101), committedSize)["reconciles"])
            assertEquals(false, replayPlayerAudit(ledger, "p", null, escrow, committedSize)["reconciles"])
            val missing = replayPlayerAudit(ledger, "missing", null, null, committedSize)
            assertEquals(true, missing["reconciles"])
            assertEquals(0, missing["entries"])
            wallet.deposit("missing", 1, "EUR", "audit-new-account")
            assertEquals(true, replayPlayerAudit(ledger, "missing", null, null, committedSize)["reconciles"])
        }
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

    @Test
    fun `failed checkpoint scans publish neither partial history nor a partial offset`() {
        val historyDir = tempDir()
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            SportsbookWallet(ledger).deposit("p", 1, "EUR", "history-stage")
            val checkpoint = ledger.checkpoint()
            Audit(ledger, historyDir, "test-ledger", listOf(ledger.publicKey)).use { audit ->
                val path = historyDir.resolve(Ledger.CHECKPOINTS_FILE)
                TestSupport.writeJournal(path, listOf(checkpoint.encode(), byteArrayOf(1)))
                repeat(5) { assertFailsWith<Exception> { audit.checkpoints() } }
                TestSupport.writeJournal(path, listOf(checkpoint.encode()))
                assertEquals(listOf(checkpoint.head.treeSize), audit.checkpoints().map { it.head.treeSize })
                assertEquals(1, audit.checkpoints().size)
            }
        }
    }

    @Test
    fun `audit rereads checkpoint signatures instead of trusting cached history`() {
        val directory = tempDir()
        Ledger.open(config(directory, Ed25519TreeHeadSigner.generate())).use { ledger ->
            SportsbookWallet(ledger).deposit("p", 1, "EUR", "signed")
            ledger.checkpoint()
            Audit(ledger, directory, "test-ledger", listOf(ledger.publicKey)).use { audit ->
                val path = directory.resolve(Ledger.CHECKPOINTS_FILE)
                val framed = Files.readAllBytes(path)
                val payloadLength = ByteBuffer.wrap(framed).int
                framed[Journal.HEADER + payloadLength - 1] = (framed[Journal.HEADER + payloadLength - 1].toInt() xor 1).toByte()
                val crc = CRC32C().apply { update(framed, Journal.HEADER, payloadLength) }
                ByteBuffer.wrap(framed).putInt(4, crc.value.toInt())
                Files.write(path, framed)

                assertFalse(audit.fullAudit().report.ok, "the fresh audit snapshot must include the modified signature")
            }
        }
    }

    @Test
    fun `tampered checkpoint sizes cannot filter themselves out of an audit`() {
        val directory = tempDir()
        Ledger.open(config(directory, Ed25519TreeHeadSigner.generate())).use { ledger ->
            SportsbookWallet(ledger).deposit("p", 1, "EUR", "size-tamper-funds")
            ledger.checkpoint()
            Audit(ledger, directory, "test-ledger", listOf(ledger.publicKey)).use { audit ->
                val path = directory.resolve(Ledger.CHECKPOINTS_FILE)
                val framed = Files.readAllBytes(path)
                val payloadLength = ByteBuffer.wrap(framed).int
                val original = SignedTreeHead.decode(framed.copyOfRange(Journal.HEADER, Journal.HEADER + payloadLength))
                val modified = SignedTreeHead(original.head.copy(treeSize = 1_000), original.keyId, original.signature()).encode()
                assertEquals(payloadLength, modified.size)
                modified.copyInto(framed, Journal.HEADER)
                val crc = CRC32C().apply { update(modified) }
                ByteBuffer.wrap(framed).putInt(4, crc.value.toInt())
                Files.write(path, framed)

                val report = audit.fullAudit().report
                assertFalse(report.ok)
                assertTrue(report.problems.any { it.contains("invalid signature") }, report.problems.toString())
            }
        }
    }

    @Test
    fun `open fixture index excludes completed history and rebuilds after restart`() {
        val directory = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(directory, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model, directory)
                book.addFixture(Random(1))
                book.addFixture(Random(2))
                book.settle("F1", "HOME")
                assertEquals(listOf("F2"), book.openFixtures().map { it.id })
            }
        }
        Ledger.open(config(directory, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model, directory)
                assertEquals(listOf("F2"), book.openFixtures().map { it.id })
            }
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model)
                repeat(256) { index ->
                    val fixture = book.addFixture(Random(index))
                    book.settle(fixture.id, "VOID")
                }
                val open = book.addFixture(Random(256))
                assertEquals(257, book.fixtures().size)
                assertEquals(listOf(open), book.openFixtures())
            }
        }
    }
    @Test
    fun `read model cumulative money totals do not overflow Long`() {
        val amount = 4_000_000_000_000_000_000L
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                repeat(3) { index ->
                    wallet.deposit("p", amount, "EUR", "totals-fund-$index")
                    wallet.withdraw("p", amount, "EUR", "totals-withdraw-$index")
                    wallet.deposit("p", amount, "EUR", "totals-bet-fund-$index")
                    wallet.placeBet("p", "totals-bet-$index", amount, "EUR", "F1|HOME|100")
                    wallet.voidBet("totals-bet-$index")
                    wallet.withdraw("p", amount, "EUR", "totals-bet-withdraw-$index")
                }
                model.catchUp()
                val totals = model.totals()
                val oneAmount = BigInteger.valueOf(amount)
                assertEquals(oneAmount.multiply(BigInteger.valueOf(6)), totals.deposits)
                assertEquals(oneAmount.multiply(BigInteger.valueOf(6)), totals.withdrawals)
                assertEquals(oneAmount.multiply(BigInteger.valueOf(3)), totals.staked)
                assertEquals(oneAmount.multiply(BigInteger.valueOf(3)), totals.returned)
            }
        }
    }

    @Test
    fun `restored fixture without its recorded result is frozen and its id stays reserved`() {
        val directory = tempDir()
        val first = LedgerWebApp.start(WebOptions(port = 0, dataDir = directory))
        assertEquals(200, call(first, "POST", "players/alice/bets", mapOf(
            "fixture" to "F1", "selection" to "HOME", "stake" to 100, "betId" to "reconcile-slip",
        )).first)
        assertEquals(200, call(first, "POST", "fixtures/F1/settle", mapOf("result" to "HOME")).first)
        first.close()

        val state = java.util.Properties().apply {
            Files.newInputStream(directory.resolve("demo-fixtures.properties")).use(::load)
            setProperty("fixture.0.result", "")
            setProperty("fixture.0.complete", "false")
        }
        Files.newOutputStream(directory.resolve("demo-fixtures.properties")).use { state.store(it, "simulated stale fixture state") }

        val restarted = LedgerWebApp.start(WebOptions(port = 0, dataDir = directory))
        try {
            val fixtures = call(restarted, "GET", "fixtures").second
            assertTrue(fixtures.contains("\"id\":\"F1\"") && fixtures.contains("\"result\":\"LEGACY_UNRESOLVED\""), fixtures)
            assertEquals(400, call(restarted, "POST", "fixtures/F1/settle", mapOf("result" to "HOME")).first)
            assertTrue(call(restarted, "POST", "fixtures").second.contains("\"id\":\"F7\""))
        } finally {
            restarted.close()
        }
    }

    @Test
    fun `simulator resumes persisted settlement results without duplicate fixture work`() {
        val directory = tempDir()
        val delayed = AtomicBoolean(false)
        val completions = ConcurrentLinkedQueue<Runnable>()
        val executor = java.util.concurrent.Executor { task -> if (delayed.get()) completions.add(task) else task.run() }
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(directory, signer).copy(completionExecutor = executor)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 1000, "EUR", "sim-round-funds")
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, directory)
                val fixture = book.addFixture(Random(0))
                book.placeBet("p", fixture.id, "HOME", 100, "sim-round-slip")
                java.util.Properties().apply {
                    Files.newInputStream(directory.resolve("demo-fixtures.properties")).use(::load)
                    setProperty("fixture.0.result", "HOME")
                    setProperty("fixture.0.complete", "false")
                    Files.newOutputStream(directory.resolve("demo-fixtures.properties")).use { store(it, "pending settlement") }
                }
            }
        }
        Ledger.open(config(directory, signer).copy(completionExecutor = executor)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, directory)
                val fixture = book.fixture("F1")
                assertEquals("HOME", fixture.result)
                assertFalse(fixture.settlementComplete)
                delayed.set(true)
                Simulator(book, wallet).use { simulator ->
                    simulator.scheduleFixtureRound()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (completions.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
                    assertTrue(completions.isNotEmpty(), "first settlement should be waiting for its completion callback")
                    simulator.scheduleFixtureRound()
                    assertEquals(1, completions.size, "the same fixture must not have a second settlement task")
                    delayed.set(false)
                    while (true) (completions.poll() ?: break).run()
                    val settledBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!fixture.settlementComplete && System.nanoTime() < settledBy) Thread.sleep(5)
                    assertTrue(fixture.settlementComplete)
                    assertEquals(1L, simulator.fixtureRounds.get())
                    model.catchUp()
                    assertTrue(model.openBetsOn(fixture.id).isEmpty())
                }
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
                    assertTrue(book.openFixtures().none { it.id == fixture.id }, "round $round: settled fixture left in open index")
                    // Every placement has exactly one settlement, and every winner was paid.
                    val placements = (0 until ledger.head().size).map { ledger.entry(it).entry }.count { it.kind == "BET_PLACED" }
                    val settlements = (0 until ledger.head().size).map { ledger.entry(it).entry }.count { it.kind == "BET_WON" }
                    assertEquals(placements, settlements, "round $round")
                    assertEquals(results.sumOf { it.getOrThrow() }, placements, "round $round")
                }
            }

        }
    }

    /** Failed startup must release the writer lock and all services acquired before binding. */
    @Test
    fun `failed HTTP binding releases resources for retry on the same ledger`() {
        val dir = tempDir()
        ServerSocket(0).use { occupied ->
            assertFailsWith<BindException> {
                LedgerWebApp.start(WebOptions(port = occupied.localPort, dataDir = dir))
            }
            LedgerWebApp.start(WebOptions(port = 0, dataDir = dir)).use { app ->
                assertEquals(200, call(app, "GET", "overview").first)
            }
        }
    }

    /** Browser tile IDs are ASCII regardless of the host's regional settings. */
    @Test
    fun `simulator customer identifiers are locale independent`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("sim-00001", Simulator.customerName(1))
            assertEquals("sim-20000", Simulator.customerName(20_000))
        } finally {
            Locale.setDefault(original)
        }
    }

    /** A delayed wallet response is pending, not a rejected money movement. */
    @Test
    fun `simulator waits beyond wallet timeout before publishing an outcome`() {
        val hold = AtomicBoolean(true)
        val queued = ConcurrentLinkedQueue<Runnable>()
        val waiting = CountDownLatch(1)
        val executor = Executor { task ->
            if (hold.get()) { queued.add(task); waiting.countDown() }
            else java.util.concurrent.ForkJoinPool.commonPool().execute(task)
        }
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate()).copy(completionExecutor = executor)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                Simulator(Book(wallet, model), wallet).use { simulator ->
                    simulator.configure(1, 600, false)
                    try {
                        race(2) { t ->
                            if (t == 0) simulator.start()
                            else {
                                check(waiting.await(5, TimeUnit.SECONDS))
                                simulator.pause()
                                Thread.sleep(31_000)
                                assertEquals(0L, simulator.rejectedOther.get())
                                assertTrue(simulator.eventPage(0).events.isEmpty())
                            }
                        }.forEach { it.getOrThrow() }
                    } finally {
                        hold.set(false)
                        while (true) java.util.concurrent.ForkJoinPool.commonPool().execute(queued.poll() ?: break)
                    }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (simulator.eventPage(0).events.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
                    assertTrue(simulator.eventPage(0).events.any { it.op == "SEED" && it.ok })
                    assertEquals(0L, simulator.rejectedOther.get())
                }
            }
        }
    }

    /** Old random seed payloads are recognized rather than regenerated after reopening. */
    @Test
    fun `simulator restart skips committed legacy seed deposits`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(dir, signer)).use { ledger ->
            SportsbookWallet(ledger).deposit("sim-00001", 1, "EUR", "sim-seed-sim-00001")
        }
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                Simulator(Book(wallet, model), wallet).use { simulator ->
                    simulator.configure(1, 600, false)
                    simulator.start()
                    Thread.sleep(1000)
                    simulator.stop()
                    assertEquals(0L, simulator.rejectedOther.get())
                    assertTrue(simulator.eventPage(0).events.none { it.op == "SEED" })
                    assertEquals(1L, ledger.findByIdempotencyKey("deposit:sim-seed-sim-00001")!!.entry.postings.first { it.amount > 0 }.amount)
                }
            }
        }
    }

    /** Delayed workers from a stopped run must not publish into the next run. */
    @Test
    fun `immediate simulator restart isolates late workers and events`() {
        val hold = AtomicBoolean(true)
        val queued = ConcurrentLinkedQueue<Runnable>()
        val waiting = CountDownLatch(1)
        val executor = Executor { task ->
            if (hold.get()) { queued.add(task); waiting.countDown() }
            else java.util.concurrent.ForkJoinPool.commonPool().execute(task)
        }
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate()).copy(completionExecutor = executor)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model)
                book.addFixture(Random(0))
                Simulator(book, wallet).use { simulator ->
                    simulator.configure(1, 600, false)
                    race(2) { t ->
                        if (t == 0) simulator.start()
                        else {
                            check(waiting.await(5, TimeUnit.SECONDS))
                            simulator.stop()
                            simulator.configure(1, 1, false)
                            simulator.start()
                            hold.set(false)
                            while (true) java.util.concurrent.ForkJoinPool.commonPool().execute(queued.poll() ?: break)
                        }
                    }.forEach { it.getOrThrow() }
                    Thread.sleep(1000)
                    assertEquals(0L, simulator.succeeded)
                    assertEquals(0L, simulator.rejectedOther.get())
                    assertTrue(simulator.eventPage(0).events.isEmpty())
                }
            }
        }
    }

    /** Settled history must leave the current-open indexes, including empty fixture buckets. */
    @Test
    fun `global open bets match current indexes after settlements and replay`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 1000, "EUR", "open-fund")
            repeat(100) { i ->
                wallet.placeBet("p", "historical-$i", 1, "EUR", "F$i|HOME|200")
                wallet.settleLoss("historical-$i")
            }
            repeat(4) { i -> wallet.placeBet("p", "open-$i", 1, "EUR", "F${i % 2}|HOME|200") }
            wallet.voidBet("open-1")
            ReadModel(ledger).use { model ->
                model.catchUp()
                assertEquals(listOf("open-0", "open-2", "open-3"), model.openBets().map { it.betId })
                assertEquals(model.allBets().filter { it.isOpen }, model.openBets())
            }
        }
        Ledger.open(config(dir, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                model.catchUp()
                assertEquals(listOf("open-0", "open-2", "open-3"), model.openBets().map { it.betId })
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
