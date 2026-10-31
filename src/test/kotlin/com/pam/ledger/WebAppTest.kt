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

    private fun call(app: LedgerWebApp, method: String, path: String, params: Map<String, Any> = emptyMap()): Pair<Int, String> {
        val form = params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v.toString(), Charsets.UTF_8) }
        val uri = URI.create("http://127.0.0.1:${app.port}/api/$path" + if (method == "GET" && form.isNotEmpty()) "?$form" else "")
        val req = HttpRequest.newBuilder(uri).method(method, if (method == "POST") HttpRequest.BodyPublishers.ofString(form) else HttpRequest.BodyPublishers.noBody())
            .header("Content-Type", "application/x-www-form-urlencoded").build()
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
