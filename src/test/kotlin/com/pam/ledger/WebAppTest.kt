package com.pam.ledger

import com.pam.ledger.web.Book
import com.pam.ledger.web.LedgerWebApp
import com.pam.ledger.web.ReadModel
import com.pam.ledger.web.WebOptions
import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the web console: the HTTP API end to end on a real server, and the one
 * concurrency rule the web layer owns (bets racing their fixture's settlement).
 */
class WebAppTest {
    private val http = HttpClient.newHttpClient()

    private fun call(
        app: LedgerWebApp, method: String, path: String, params: Map<String, Any> = emptyMap(),
        headers: Map<String, String> = mapOf("X-Ledger-Console" to "1"),
    ): Pair<Int, String> {
        val form = params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v.toString(), Charsets.UTF_8) }
        val uri = URI.create("http://127.0.0.1:${app.port}/api/$path" + if (method == "GET" && form.isNotEmpty()) "?$form" else "")
        val builder = HttpRequest.newBuilder(uri).method(method, if (method == "POST") HttpRequest.BodyPublishers.ofString(form) else HttpRequest.BodyPublishers.noBody())
            .header("Content-Type", "application/x-www-form-urlencoded")
        headers.forEach { (k, v) -> builder.header(k, v) }
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
        // Automated customers are ordinary players: visible to the customer API and auditable.
        val players = call(app, "GET", "players", mapOf("q" to "sim-")).second
        assertTrue(players.contains("\"automated\":true"), players)
        val audit = call(app, "POST", "regulator/audit").second
        assertTrue(audit.contains("\"ok\":true"), audit)
    }

    /** Checkpoints signed faster than any polling interval must all appear in the regulator's history. */
    @Test
    fun `checkpoint history contains every signed head`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(dir, signer, checkpointEvery = 10)).use { ledger ->
            val audit = com.pam.ledger.web.Audit(ledger, dir, TestSupport.LEDGER_ID, listOf(signer.publicKey))
            audit.use {
                val wallet = SportsbookWallet(ledger)
                repeat(200) { wallet.deposit("p", 1, "EUR", "burst-$it") } // 20 checkpoints in a burst
                val persisted = ArrayList<Long>()
                Journal.scanReadOnly(dir.resolve(Ledger.CHECKPOINTS_FILE)) { persisted += SignedTreeHead.decode(it).head.treeSize }
                assertTrue(persisted.size >= 20, "expected at least 20 checkpoints, found ${persisted.size}")
                assertEquals(persisted, audit.checkpoints().map { it.head.treeSize }, "history must hold every persisted head, in order")
                for (size in persisted) assertTrue(audit.checkpointOfSize(size) != null, "no checkpoint found for size $size")
            }
        }
    }

    /** The player audit must reconcile even while the customer is being changed concurrently. */
    @Test
    fun `player audit reconciles while the simulator is running`() = withApp { app ->
        // Two customers at the maximum rate (10 operations a second each), audited repeatedly.
        // A regression check: the race it guards against is microseconds wide and could not be
        // reproduced on demand; the fix (comparing at each account's lastSeq) removes it by design.
        call(app, "POST", "simulator", mapOf("customers" to 2, "opsPerMinute" to 600, "autoFixtures" to "false", "action" to "start"))
        Thread.sleep(800)
        repeat(200) { i ->
            val body = call(app, "GET", "regulator/players/sim-0000${i % 2 + 1}").second
            assertTrue(body.contains("\"reconciles\":true"), "audit #$i did not reconcile: ${body.take(400)}")
        }
        call(app, "POST", "simulator", mapOf("action" to "stop"))
    }

    /** Another website can make the user's browser send requests here; such requests must be refused. */
    @Test
    fun `cross-site requests cannot move money`() = withApp { app ->
        val deposit = mapOf("amount" to 100, "ref" to "csrf-1")
        // A plain HTML form on another site cannot add custom headers.
        assertEquals(403, call(app, "POST", "players/alice/deposit", deposit, headers = emptyMap()).first)
        // A script on another site gets an Origin header that is not ours.
        assertEquals(403, call(app, "POST", "players/alice/deposit", deposit, headers = mapOf("X-Ledger-Console" to "1", "Origin" to "https://evil.example")).first)
        // The console page itself, same origin, is allowed.
        assertEquals(200, call(app, "POST", "players/alice/deposit", deposit, headers = mapOf("X-Ledger-Console" to "1", "Origin" to "http://127.0.0.1:${app.port}")).first)
    }

    @Test
    fun `path segments are decoded exactly once`() = withApp { app ->
        // "a+b" is a literal plus in a path; it must not turn into "a b".
        call(app, "POST", "players/a+b/deposit", mapOf("amount" to 100, "ref" to "plus-1"))
        val (_, body) = call(app, "GET", "players/a%2Bb")
        assertTrue(body.contains("\"id\":\"a+b\"") && body.contains("\"cash\":100"), body)
    }

    @Test
    fun `input errors are reported as 400 and unknown routes as 404`() = withApp { app ->
        assertEquals(400, call(app, "POST", "simulator", mapOf("customers" to 0, "opsPerMinute" to 60)).first)
        assertEquals(400, call(app, "POST", "regulator/tamper", mapOf("mode" to "explode")).first)
        assertEquals(404, call(app, "GET", "no/such/route").first)
        assertEquals(404, call(app, "POST", "fixtures/F999/settle", mapOf("result" to "HOME")).first)
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
                    val book = Book(ledger, wallet, readModel)
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
}
