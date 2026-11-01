package com.pam.ledger

import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import com.pam.ledger.web.Audit
import com.pam.ledger.web.Book
import com.pam.ledger.web.Json
import com.pam.ledger.web.ReadModel
import com.pam.ledger.web.RegulatorReports
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegulatorReportsTest {
    private class ReportClock : Clock() {
        val now = AtomicReference(Instant.parse("2026-10-30T23:59:59Z"))
        override fun instant(): Instant = now.get()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        fun at(value: String) { now.set(Instant.parse(value)) }
    }

    private class Context : AutoCloseable {
        val directory = tempDir()
        val clock = ReportClock()
        val signer = Ed25519TreeHeadSigner.generate()
        val ledger = Ledger.open(LedgerConfig(directory, "sportsbook-web", signer, clock = clock))
        val wallet = SportsbookWallet(ledger)
        val readModel = ReadModel(ledger)
        val book = Book(wallet, readModel, directory)
        val audit = Audit(ledger, directory, "sportsbook-web", listOf(signer.publicKey))
        val reports = RegulatorReports(ledger, book, audit)
        override fun close() {
            audit.close()
            readModel.close()
            ledger.close()
        }
    }

    private fun map(value: Any?): Map<*, *> = value as? Map<*, *> ?: error("expected report object")
    private fun rows(value: Any?): List<Map<*, *>> =
        (value as? List<*> ?: error("expected report rows")).map(::map)
    private fun amount(value: Any?) = (value as? String ?: error("expected exact amount string")).toBigInteger()
    private fun evidenceParams(report: Map<String, Any?>): Map<String, String> {
        val anchor = map(map(report["manifest"])["anchor"])
        val root = anchor["rootHash"] as? Digest ?: error("expected checkpoint digest")
        return mapOf("size" to anchor["treeSize"].toString(), "root" to root.toHex())
    }

    @Test
    fun `period revenue follows settlement not placement and liabilities replay one prefix`() {
        Context().use { c ->
            val fixture = c.book.addFixture()
            fun place(id: String, stake: Long) = c.wallet.placeBet("alice", id, stake, "EUR", "${fixture.id}|HOME|200")
            c.wallet.deposit("alice", 1_000, "EUR", "opening")
            place("old", 200)
            c.clock.at("2026-10-31T01:00:00Z")
            c.wallet.deposit("alice", 500, "EUR", "period")
            c.wallet.withdraw("alice", 50, "EUR", "withdraw")
            c.wallet.settleWin("old", 300)
            place("void", 100); c.wallet.voidBet("void")
            place("cashout", 100); c.wallet.cashOut("cashout", 85)
            place("loss", 100); c.wallet.settleLoss("loss")
            place("open", 100)
            c.clock.at("2026-11-01T00:00:01Z")
            val report = c.reports.create(mapOf("fromDate" to "2026-10-31", "toDate" to "2026-11-01"))
            val f = map(report["financial"])
            val expected = mapOf("openingLiability" to "1000", "deposits" to "500", "withdrawals" to "50",
                "stakes" to "400", "payouts" to "485", "revenue" to "15", "closingLiability" to "1435",
                "cashLiability" to "1335", "escrowLiability" to "100", "settledStakes" to "500",
                "winPayouts" to "300", "voidRefunds" to "100", "cashOuts" to "85", "otherMovement" to "0",
                "conservation" to "0", "openStakes" to "100")
            expected.forEach { (key, value) -> assertEquals(value, f[key], key) }
            assertEquals(true, f["escrowReconciles"])
            assertEquals(mapOf("WON" to 1, "VOIDED" to 1, "CASHED_OUT" to 1, "LOST" to 1), f["outcomes"])
            assertNull(f["bankReconciliation"])
            val fixtureRow = rows(report["fixtures"]).single()
            assertEquals("400", fixtureRow["stakes"])
            assertEquals("15", fixtureRow["revenue"])
            assertEquals("200", fixtureRow["worstWinningPayout"])
            assertEquals("1335", rows(report["customers"]).single()["cash"])
            c.wallet.settleLoss("open")
            assertEquals("100", f["openStakes"], "a frozen report must not read live balances")
            val evidence = c.reports.evidence(evidenceParams(report))
            assertEquals(map(map(report["manifest"])["anchor"])["treeSize"], rows(evidence["entries"]).size.toLong())
            assertFalse(rows(evidence["entries"]).any { it["key"] == "bet:open:settle" })
        }
    }

    @Test
    fun `aggregate turnover exceeds Long and JavaScript ranges without double counting retries`() {
        Context().use { c ->
            val big = 9_007_199_254_740_993L
            c.wallet.deposit("alice", big, "EUR", "big")
            c.wallet.deposit("alice", big, "EUR", "big")
            c.wallet.placeBet("alice", "large", big, "EUR", "F1|HOME|100")
            c.wallet.voidBet("large")
            c.wallet.withdraw("alice", big, "EUR", "all")
            repeat(2) { n ->
                c.wallet.deposit("bob", Long.MAX_VALUE, "EUR", "max-$n")
                c.wallet.withdraw("bob", Long.MAX_VALUE, "EUR", "max-withdraw-$n")
            }
            c.clock.at("2026-10-31T01:00:00Z")
            val report = c.reports.create(emptyMap())
            val f = map(report["financial"])
            val total = Long.MAX_VALUE.toBigInteger() * BigInteger.TWO + big.toBigInteger()
            assertEquals(total.toString(), f["deposits"])
            assertEquals(total.toString(), f["withdrawals"])
            assertEquals(big.toString(), f["stakes"])
            assertEquals(big.toString(), f["voidRefunds"])
            assertEquals("0", f["closingLiability"])
            assertEquals("0", f["conservation"])
            assertEquals(8, f["periodEntries"])
        }
    }

    @Test
    fun `date boundaries use the selected timezone and currency and population never mix`() {
        Context().use { c ->
            c.clock.at("2026-10-30T19:59:59Z")
            c.wallet.deposit("alice", 100, "EUR", "before")
            c.clock.at("2026-10-30T20:00:00Z")
            c.wallet.deposit("alice", 200, "EUR", "start")
            c.wallet.deposit("sim-00001", 400, "EUR", "automated")
            c.wallet.deposit("alice", 800, "USD", "dollars")
            c.clock.at("2026-10-31T20:00:00Z")
            c.wallet.deposit("alice", 1_600, "EUR", "end")
            val params = mapOf("fromDate" to "2026-10-31", "toDate" to "2026-11-01",
                "timezone" to "Asia/Dubai", "population" to "manual")
            val report = c.reports.create(params)
            val f = map(report["financial"])
            assertEquals("200", f["deposits"])
            assertEquals("100", f["openingLiability"])
            assertEquals("300", f["closingLiability"])
            assertEquals(listOf("alice"), rows(report["customers"]).map { it["id"] })
            assertEquals("2026-10-30T20:00:00Z", map(report["manifest"])["from"])
            assertEquals("2026-10-31", rows(report["daily"]).single()["day"])
            val dollars = c.reports.create(params + ("currency" to "USD"))
            assertEquals("800", map(dollars["financial"])["deposits"])
            val automated = c.reports.create(params + ("population" to "automated"))
            assertEquals("400", map(automated["financial"])["deposits"])
            val dst = c.reports.create(mapOf("fromDate" to "2026-11-01", "toDate" to "2026-11-02", "timezone" to "America/New_York"))
            assertEquals("2026-11-01T04:00:00Z", map(dst["manifest"])["from"])
            assertEquals("2026-11-02T05:00:00Z", map(dst["manifest"])["to"])
            assertEquals("Partial period", map(dst["manifest"])["coverage"])
        }
    }

    @Test
    fun `unattributed bets and missing compliance data stay visible instead of becoming zero passes`() {
        Context().use { c ->
            c.wallet.deposit("alice", 100, "EUR", "seed")
            c.wallet.placeBet("alice", "generic", 20, "EUR")
            c.ledger.append(TransactionRequest("misc", "ADJUSTMENT", listOf(
                Posting(AccountId("house:misc:EUR"), "EUR", 1),
                Posting(AccountId("house:other:EUR"), "EUR", -1))))
            val report = c.reports.create(emptyMap())
            val f = map(report["financial"])
            assertEquals(1, f["openBets"])
            assertEquals("20", f["openStakes"])
            assertEquals(1, f["unclassifiedEntries"])
            val fixture = rows(report["fixtures"]).single()
            assertEquals("UNATTRIBUTED", fixture["id"])
            assertNull(fixture["worstWinningPayout"])
            assertNull(fixture["definition"])
            val evidence = c.reports.evidence(evidenceParams(report) + ("fixture" to "UNATTRIBUTED"))
            assertEquals(listOf("BET_PLACED"), rows(evidence["entries"]).map { it["kind"] })
            val summary = map(report["customerSummary"])
            listOf("registrations", "verified", "excluded").forEach { assertNull(summary[it]) }
            assertTrue(rows(report["coverage"]).any { it["area"] == "Player protection" && it["status"] == "Not collected" })
            val examples = map(report["compliance"])
            assertEquals("synthetic-compliance-v1", examples["version"])
            assertTrue(rows(examples["cases"]).all { it["source"] == "Synthetic example" })
            assertEquals(examples, c.reports.create(emptyMap())["compliance"], "examples must regenerate deterministically")
        }
    }

    @Test
    fun `evidence paging includes all entries and rejects invalid bounds and old roots`() {
        Context().use { c ->
            repeat(70) { n -> c.wallet.deposit("alice", 1, "EUR", "row-$n") }
            repeat(40) { c.book.addFixture() }
            val report = c.reports.create(emptyMap())
            assertEquals(40, rows(report["fixtures"]).size, "reports must not inherit recent-fixture caps")
            val params = evidenceParams(report)
            val first = c.reports.evidence(params + ("limit" to "25"))
            val second = c.reports.evidence(params + mapOf("offset" to "25", "limit" to "25"))
            val third = c.reports.evidence(params + mapOf("offset" to "50", "limit" to "25"))
            val combined = listOf(first, second, third).flatMap { rows(it["entries"]) }
            assertEquals((0L until 70L).toList(), combined.map { it["seq"] })
            assertEquals(true, third["complete"])
            assertEquals(false, first["complete"])
            assertTrue(combined.all { it["entryBytes"] is ByteArray })
            val proof = c.reports.proof(params + ("seq" to "20"))
            assertEquals(70L, proof["treeSize"])
            assertEquals(c.ledger.entry(20).encodedBytes().toList(), (proof["entryBytes"] as ByteArray).toList())
            assertFailsWith<IllegalArgumentException> { c.reports.proof(params + ("seq" to "70")) }
            assertFailsWith<IllegalArgumentException> { c.reports.evidence(params + ("limit" to "1001")) }
            assertFailsWith<IllegalArgumentException> { c.reports.evidence(params + ("offset" to "-1")) }
            assertFailsWith<IllegalArgumentException> { c.reports.evidence(params + ("offset" to "invalid")) }
            assertFailsWith<NotFoundException> { c.reports.evidence(params + ("root" to "0".repeat(64))) }
            assertFailsWith<IllegalArgumentException> { c.reports.create(mapOf("from" to "tomorrow")) }
            assertFailsWith<IllegalArgumentException> { c.reports.create(mapOf("timezone" to "unknown")) }
            assertFailsWith<IllegalArgumentException> { c.reports.create(mapOf("population" to "customers")) }
            assertFailsWith<IllegalArgumentException> { c.reports.create(mapOf("fromDate" to "2026-11-02", "toDate" to "2026-11-01")) }
            assertFailsWith<IllegalArgumentException> { c.reports.create(mapOf("fromDate" to "2026-11-01", "from" to "2026-11-01T00:00:00Z")) }
        }
    }

    @Test
    fun `repeated unchanged freezes do not retain new checkpoints and filtered evidence scans are bounded`() {
        Context().use { c ->
            repeat(1_050) { n -> c.wallet.deposit("alice", 1, "EUR", "bounded-$n") }
            val report = c.reports.create(emptyMap())
            val checkpoints = c.audit.checkpoints().size
            repeat(10) {
                assertEquals(Json.of(map(report["manifest"])["anchor"]),
                    Json.of(map(c.reports.create(emptyMap())["manifest"])["anchor"]))
            }
            assertEquals(checkpoints, c.audit.checkpoints().size)
            val page = c.reports.evidence(evidenceParams(report) + ("player" to "absent"))
            assertTrue(rows(page["entries"]).isEmpty())
            assertEquals(1_000L, page["nextOffset"])
            assertEquals(false, page["complete"])
            val last = c.reports.evidence(evidenceParams(report) + mapOf("player" to "absent", "offset" to "1000"))
            assertEquals(1_050L, last["nextOffset"])
            assertEquals(true, last["complete"])
        }
    }

    @Test
    fun `reports racing settlement reconcile only their captured durable prefix`() {
        Context().use { c ->
            c.wallet.deposit("alice", 10_000, "EUR", "race")
            repeat(20) { n -> c.wallet.placeBet("alice", "race-$n", 100, "EUR", "F1|HOME|200") }
            val results = race(8) { n ->
                if (n < 4) {
                    (n until 20 step 4).forEach { c.wallet.settleWin("race-$it", 200) }
                    null
                } else c.reports.create(emptyMap())
            }.map { it.getOrThrow() }
            results.filterNotNull().forEach { report ->
                val f = map(report["financial"])
                assertEquals(amount(f["openingLiability"]) + amount(f["deposits"]) - amount(f["withdrawals"]) -
                    amount(f["revenue"]) + amount(f["otherMovement"]), amount(f["closingLiability"]))
                assertEquals(true, f["escrowReconciles"])
                assertEquals("0", f["conservation"])
                val entries = rows(c.reports.evidence(evidenceParams(report))["entries"])
                val wins = entries.count { it["kind"] == "BET_WON" }
                assertEquals((wins * -100).toString(), f["revenue"])
                assertEquals(20 - wins, f["openBets"])
                assertEquals((entries.size - 1).toLong(), entries.last()["seq"])
            }
        }
    }
}
