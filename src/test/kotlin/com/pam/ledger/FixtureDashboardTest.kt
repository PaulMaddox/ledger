package com.pam.ledger

import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import com.pam.ledger.web.Book
import com.pam.ledger.web.LedgerWebApp
import com.pam.ledger.web.ReadModel
import com.pam.ledger.web.WebOptions
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The fixture dashboard is served from incremental read-model aggregates and bounded fixture indexes.
 * Every aggregate is checked against an independent reconstruction from the ledger's own committed prefix.
 */
class FixtureDashboardTest {
    private val http = HttpClient.newHttpClient()
    private val selections = listOf("HOME", "DRAW", "AWAY")

    /** What a fixture's open bets must sum to, rebuilt from raw ledger entries and nothing else. */
    private data class Expected(val bets: Int, val staked: BigInteger, val liability: Map<String, BigInteger>)

    /** Replay the first [prefix] entries. Payouts are rounded per bet, as the wallet and Book round them. */
    private fun reconstruct(ledger: Ledger, prefix: Long, fixtureId: String): Expected {
        class Open(val fixture: String, val selection: String, val stake: BigInteger, val payout: BigInteger)
        val open = LinkedHashMap<String, Open>()
        for (seq in 0 until prefix) {
            val entry = ledger.entry(seq).entry
            when (entry.kind) {
                "BET_PLACED" -> {
                    val parts = entry.metadata["selection"]?.split("|") ?: continue
                    if (parts.size != 3) continue
                    val stake = BigInteger.valueOf(entry.postings.first { it.amount > 0 }.amount)
                    val hundredths = BigInteger(parts[2])
                    val (whole, rest) = stake.divideAndRemainder(BigInteger.valueOf(100))
                    val payout = whole.multiply(hundredths).add(rest.multiply(hundredths).divide(BigInteger.valueOf(100)))
                    open[entry.metadata.getValue("betId")] = Open(parts[0], parts[1], stake, payout)
                }
                "BET_WON", "BET_LOST", "BET_VOIDED", "BET_CASHED_OUT" -> open.remove(entry.metadata.getValue("betId"))
            }
        }
        val mine = open.values.filter { it.fixture == fixtureId }
        return Expected(
            mine.size, mine.fold(BigInteger.ZERO) { sum, bet -> sum.add(bet.stake) },
            selections.associateWith { s -> mine.filter { it.selection == s }.fold(BigInteger.ZERO) { sum, bet -> sum.add(bet.payout) } },
        )
    }

    private fun assertSummary(ledger: Ledger, model: ReadModel, fixtureId: String): ReadModel.FixtureSummary {
        model.catchUp()
        val summary = model.fixtureSummary(fixtureId, selections)
        val expected = reconstruct(ledger, summary.indexedEntries, fixtureId)
        assertEquals(expected.bets, summary.openBets, "open bets on $fixtureId at ${summary.indexedEntries}")
        assertEquals(expected.staked, summary.staked, "stake on $fixtureId at ${summary.indexedEntries}")
        assertEquals(expected.liability, summary.liability, "liability on $fixtureId at ${summary.indexedEntries}")
        return summary
    }

    @Test
    fun `fixture aggregates follow placements and every outcome and rebuild after recovery`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                assertEquals(0, assertSummary(ledger, model, "F1").openBets)
                wallet.deposit("p", 100_000, "EUR", "dash-fund")
                // 101 x 2.05 = 207.05 and 99 x 1.07 = 105.93: each bet rounds down before the sum.
                wallet.placeBet("p", "a", 101, "EUR", "F1|HOME|205")
                wallet.placeBet("p", "b", 101, "EUR", "F1|HOME|205")
                wallet.placeBet("p", "c", 99, "EUR", "F1|DRAW|107")
                wallet.placeBet("p", "d", 1, "EUR", "F1|AWAY|105")
                wallet.placeBet("p", "e", 250, "EUR", "F2|HOME|333")
                // Standalone wallet selections are not console fixtures and never appear in any fixture.
                wallet.placeBet("p", "standalone", 100, "EUR", "HOME")
                val placed = assertSummary(ledger, model, "F1")
                assertEquals(4, placed.openBets)
                assertEquals(BigInteger.valueOf(302), placed.staked)
                assertEquals(BigInteger.valueOf(414), placed.liability.getValue("HOME"), "rounded per bet: 207 + 207")
                assertEquals(BigInteger.valueOf(105), placed.liability.getValue("DRAW"))
                assertEquals(BigInteger.ONE, placed.liability.getValue("AWAY"), "1 x 1.05 rounds down to 1")
                assertSummary(ledger, model, "F2")

                assertTrue(wallet.placeBet("p", "a", 101, "EUR", "F1|HOME|205").replayed)
                assertEquals(placed, assertSummary(ledger, model, "F1"))
                wallet.voidBet("standalone")
                assertEquals(placed.openBets, assertSummary(ledger, model, "F1").openBets)
                wallet.settleWin("a", 207)
                assertEquals(3, assertSummary(ledger, model, "F1").openBets)
                assertTrue(wallet.settleWin("a", 207).replayed)
                assertFailsWith<IdempotencyConflictException> { wallet.voidBet("a") }
                assertFailsWith<IdempotencyConflictException> { wallet.settleLoss("a") }
                assertEquals(3, assertSummary(ledger, model, "F1").openBets, "retries must not decrement again")
                wallet.settleLoss("b")
                wallet.voidBet("c")
                assertEquals(1, assertSummary(ledger, model, "F1").openBets)
                wallet.cashOut("d", 1)
                val empty = assertSummary(ledger, model, "F1")
                assertEquals(0, empty.openBets)
                assertEquals(BigInteger.ZERO, empty.staked)
                assertEquals(selections.associateWith { BigInteger.ZERO }, empty.liability)
                assertEquals(4, placed.openBets, "previous snapshots must remain immutable")
                assertEquals(BigInteger.valueOf(414), placed.liability.getValue("HOME"))
                assertFailsWith<UnsupportedOperationException> {
                    @Suppress("UNCHECKED_CAST")
                    (placed.liability as MutableMap<String, BigInteger>)["HOME"] = BigInteger.ONE
                }
                assertSummary(ledger, model, "F2")
            }
        }
        Ledger.open(config(dir, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                assertEquals(0, assertSummary(ledger, model, "F1").openBets)
                assertEquals(1, assertSummary(ledger, model, "F2").openBets)
                SportsbookWallet(ledger).voidBet("e")
                assertEquals(0, assertSummary(ledger, model, "F2").openBets)
            }
        }
    }

    @Test
    fun `fixture aggregates stay exact beyond Long and across unknown selections`() {
        val amount = 4_000_000_000_000_000_000L
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                repeat(3) { i ->
                    // Separate clearing accounts keep every individual ledger balance representable.
                    ledger.append(TransactionRequest("dash-fund-$i", "DEPOSIT", listOf(
                        Posting(AccountId("house:clearing-$i:EUR"), "EUR", -amount),
                        Posting(SportsbookWallet.Accounts.cash("p$i", "EUR"), "EUR", amount),
                    ), mapOf("playerId" to "p$i")))
                    wallet.placeBet("p$i", "large-$i", amount, "EUR", "F1|HOME|200")
                }
                val large = assertSummary(ledger, model, "F1")
                val sum = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(3))
                assertEquals(sum, large.staked)
                assertEquals(sum.multiply(BigInteger.TWO), large.liability.getValue("HOME"))
                assertTrue(large.liability.getValue("HOME") > BigInteger.valueOf(Long.MAX_VALUE))
                repeat(3) { i ->
                    wallet.voidBet("large-$i")
                    assertSummary(ledger, model, "F1")
                }
                // The generic wallet has no Book payout-range guard; catch-up must still be exact.
                wallet.placeBet("p0", "generic-large", amount, "EUR", "F1|HOME|300")
                val generic = assertSummary(ledger, model, "F1")
                assertEquals(BigInteger.valueOf(amount).multiply(BigInteger.valueOf(3)), generic.liability.getValue("HOME"))
                wallet.voidBet("generic-large")
                // A selection outside HOME/DRAW/AWAY still counts toward open bets and stake, like the old per-request scan.
                wallet.placeBet("p1", "odd", 10, "EUR", "F1|OTHER|150")
                model.catchUp()
                val odd = model.fixtureSummary("F1", selections)
                assertEquals(1, odd.openBets)
                assertEquals(BigInteger.TEN, odd.staked)
                assertEquals(BigInteger.ZERO, odd.liability.getValue("HOME"))
                assertEquals(BigInteger.valueOf(15), model.fixtureSummary("F1", listOf("OTHER")).liability.getValue("OTHER"))
                wallet.voidBet("odd")
                assertEquals(0, assertSummary(ledger, model, "F1").openBets)
                assertEquals(BigInteger.ZERO, model.fixtureSummary("never-seen", selections).staked)
            }
        }
    }

    /** Catch-up, dashboard sampling, placements and settlement all at once; each sample matches its own prefix. */
    @Test
    fun `fixture snapshots stay consistent while placements and settlement race`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 1_000_000, "EUR", "dash-race-fund")
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model)
                val fixture = book.addFixture(Random(0))
                repeat(60) { i -> book.placeBet("p", fixture.id, selections[i % 3], 101, "dash-race-seed-$i") }
                val samples = ConcurrentLinkedQueue<Pair<ReadModel.FixtureSummary, ReadModel.FixtureSummary>>()
                race(6) { t ->
                    when (t) {
                        0 -> repeat(60) { i -> book.placeBet("p", fixture.id, "HOME", 101, "dash-race-live-$i") }
                        1 -> repeat(60) { i ->
                            try { wallet.cashOut("dash-race-seed-$i", 85) } catch (e: IdempotencyConflictException) { /* settlement won */ }
                        }
                        2 -> repeat(40) { model.catchUp() }
                        3 -> runCatching { Thread.sleep(5); book.settle(fixture.id, "DRAW") }
                        else -> repeat(80) {
                            val snapshot = model.fixtureSummary(fixture.id, selections)
                            samples += snapshot to snapshot.copy(liability = snapshot.liability.toMap())
                        }
                    }
                }.forEach { result -> result.onFailure { if (it !is IllegalArgumentException) throw it } }
                for ((snapshot, copy) in samples) {
                    val expected = reconstruct(ledger, snapshot.indexedEntries, fixture.id)
                    assertEquals(expected.bets, snapshot.openBets)
                    assertEquals(expected.staked, snapshot.staked)
                    assertEquals(expected.liability, snapshot.liability)
                    assertEquals(copy, snapshot, "a returned snapshot changed after later catch-up")
                }
                book.settle(fixture.id, "DRAW")
                assertEquals(0, assertSummary(ledger, model, fixture.id).openBets)
            }
        }
    }

    @Test
    fun `dashboard fixtures use numeric order and keep every unfinished fixture`() {
        val directory = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(directory, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model, directory)
                repeat(12) { book.addFixture(Random(it)) }
                assertEquals((12 downTo 1).map { "F$it" }, book.dashboardFixtures().map { it.id }, "F10 sorts after F9, not after F1")
                assertEquals((1..12).map { "F$it" }, book.fixtures().map { it.id })

                // F1 and F2 stay unfinished while 43 newer fixtures complete, out of numeric order.
                repeat(33) { book.addFixture(Random(100 + it)) }
                for (n in listOf(10, 3) + (4..9) + (11..45)) book.settle("F$n", "VOID")
                assertEquals((45 downTo 16).map { "F$it" } + listOf("F2", "F1"), book.dashboardFixtures().map { it.id },
                    "newest 30 completed plus every unfinished fixture, newest first")
                assertEquals(45, book.fixtures().size)

                // F2 completing last is still older than the window, so only F1 remains as extra work.
                book.settle("F2", "VOID")
                assertEquals((45 downTo 16).map { "F$it" } + "F1", book.dashboardFixtures().map { it.id })
                assertEquals(45, book.dashboardFixtures(recentCompleted = 100).size, "no fixture is ever dropped from the index")
            }
        }
        Ledger.open(config(directory, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                val restored = Book(SportsbookWallet(ledger), model, directory)
                assertEquals((45 downTo 16).map { "F$it" } + "F1", restored.dashboardFixtures().map { it.id })
                assertEquals((1..45).map { "F$it" }, restored.fixtures().map { it.id })
                assertEquals("F46", restored.addFixture(Random(1)).id)
                assertEquals("F46", restored.dashboardFixtures().first().id)
            }
        }
    }

    /** A fixture moving from unfinished to completed is never missing or duplicated in a concurrent dashboard sample. */
    @Test
    fun `dashboard index never hides or duplicates a fixture during completion`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model)
                repeat(60) { book.addFixture(Random(it)) }
                race(4) { t ->
                    if (t == 0) {
                        for (n in (1..60).shuffled(Random(7))) book.settle("F$n", "VOID")
                    } else {
                        repeat(300) {
                            val ids = book.dashboardFixtures(recentCompleted = 100).map { it.id }
                            assertEquals((60 downTo 1).map { "F$it" }, ids)
                        }
                    }
                }.forEach { it.getOrThrow() }
                assertEquals(30, book.dashboardFixtures().size)
            }
        }
    }

    @Test
    fun `unresolved restored fixtures older than the display window stay visible`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 10_000, "EUR", "legacy-fund")
            // No fixture file: bets alone force restoration, with the result unknown and settlement frozen.
            wallet.placeBet("p", "legacy-1", 100, "EUR", "F1|HOME|200")
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, dir)
                repeat(40) { index ->
                    val fixture = book.addFixture(Random(index))
                    book.settle(fixture.id, "VOID")
                }
                assertEquals("F1", book.dashboardFixtures().last().id)
                assertEquals(31, book.dashboardFixtures().size)
                assertEquals(1, model.fixtureSummary("F1", selections).openBets)
            }
        }
    }

    private fun call(app: LedgerWebApp, method: String, path: String, params: Map<String, Any> = emptyMap()): Pair<Int, String> {
        val form = params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v.toString(), Charsets.UTF_8) }
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port}/api/$path"))
            .method(method, if (method == "POST") HttpRequest.BodyPublishers.ofString(form) else HttpRequest.BodyPublishers.noBody())
            .header("Content-Type", "application/x-www-form-urlencoded")
        if (method == "POST") builder.header("Origin", "http://127.0.0.1:${app.port}")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    /** The API rows keep their fields; liabilities and stakes come from aggregates, and reset starts a new generation. */
    @Test
    fun `fixture API reports aggregates and rebuilds them after reset`() {
        val app = LedgerWebApp.start(WebOptions(port = 0, dataDir = tempDir()))
        try {
            // Alice starts with 100.00 EUR, so these bets fit; 101 x F1's HOME price rounds per bet.
            val odds = Regex("\"id\":\"F1\".*?\"odds\":\\{[^}]*\"HOME\":(\\d+)").find(call(app, "GET", "fixtures").second)!!.groupValues[1].toLong()
            repeat(2) { i ->
                assertEquals(200, call(app, "POST", "players/alice/bets", mapOf(
                    "fixture" to "F1", "selection" to "HOME", "stake" to 101, "betId" to "api-$i")).first)
            }
            val each = payoutOf(101, odds)
            val rowPattern = Regex("\\{\"id\":\"F1\".*?\"liability\":\\{[^}]*\\}\\}")
            // The read model trails the ledger by at most its 100 ms poll; the dashboard never forces catch-up.
            val deadline = System.nanoTime() + 10_000_000_000L
            var row = rowPattern.find(call(app, "GET", "fixtures").second)!!.value
            while (!row.contains("\"openBets\":2") && System.nanoTime() < deadline) {
                Thread.sleep(20)
                row = rowPattern.find(call(app, "GET", "fixtures").second)!!.value
            }
            assertTrue(row.contains("\"openBets\":2") && row.contains("\"staked\":\"202\""), row)
            assertTrue(row.contains("\"HOME\":\"${each * 2}\""), row)
            assertEquals(200, call(app, "POST", "fixtures/F1/settle", mapOf("result" to "AWAY")).first)
            var settled = call(app, "GET", "fixtures").second
            while (settled.contains("\"openBets\":2") && System.nanoTime() < deadline + 10_000_000_000L) {
                Thread.sleep(20)
                settled = call(app, "GET", "fixtures").second
            }
            assertTrue(settled.contains("\"id\":\"F1\"") && settled.contains("\"openBets\":0,\"staked\":\"0\""), settled)

            assertEquals(200, call(app, "POST", "administrator/clear", mapOf("confirmation" to "CLEAR DEMO STORAGE")).first)
            val fresh = call(app, "GET", "fixtures").second
            assertEquals((6 downTo 1).map { "F$it" }, Regex("\"id\":\"(F\\d+)\"").findAll(fresh).map { it.groupValues[1] }.toList())
            assertTrue(!fresh.contains("\"openBets\":2") && fresh.contains("\"result\":null"), fresh)
        } finally {
            app.close()
        }
    }

    private fun payoutOf(stake: Long, hundredths: Long) = stake / 100 * hundredths + stake % 100 * hundredths / 100
}
