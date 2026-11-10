package com.pam.ledger

import com.pam.ledger.SportsbookWallet.Accounts
import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import com.pam.ledger.web.ReadModel
import com.pam.ledger.web.replayPlayerAudit
import org.junit.jupiter.api.Tag
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reads must not get slower as the ledger grows (issue #73). The fast tests pin the
 * BEHAVIOUR of the indexes that make that true; the soak test measures the growth.
 */
class ReadScalingTest {

    /** The bet page as it was computed before the placement index: walk the whole history, newest first. */
    private fun betsByWalkingHistory(ledger: Ledger, model: ReadModel, player: String, limit: Int): List<ReadModel.BetView> {
        val result = ArrayList<ReadModel.BetView>()
        for (seq in model.seqsForPlayer(player).asReversed()) {
            if (result.size == limit) break
            val entry = ledger.entry(seq).entry
            if (entry.kind == "BET_PLACED") entry.metadata["betId"]?.let(model::bet)?.let(result::add)
        }
        return result.asReversed()
    }

    @Test
    fun `bet page ignores a long tail of deposits and matches a walk of the whole history`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("tail", 10_000, "EUR", "tail-funds")
            repeat(45) { wallet.placeBet("tail", "tail-$it", 10, "EUR", "F${it % 3}|HOME|200") }
            repeat(20) { if (it % 2 == 0) wallet.settleLoss("tail-$it") else wallet.cashOut("tail-$it", 8) }
            repeat(300) { wallet.deposit("tail", 1, "EUR", "tail-top-up-$it") }
            // A placement without a three-part selection has no bet view and must not occupy a page slot.
            wallet.placeBet("tail", "tail-plain", 10, "EUR", "no-market")
            ReadModel(ledger).use { model ->
                model.catchUp()
                for (limit in listOf(0, 1, 40, 45, 500)) {
                    assertEquals(betsByWalkingHistory(ledger, model, "tail", limit), model.betsForPlayer("tail", limit), "limit $limit")
                }
                assertEquals((5 until 45).map { "tail-$it" }, model.betsForPlayer("tail", 40).map { it.betId })
                assertTrue(model.betsForPlayer("nobody", 40).isEmpty())
            }
        }
    }

    @Test
    fun `player count and sorted visit agree with the player set`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            val ids = listOf("zoe", "sim-00002", "adam", "sim-00001", "Mia")
            ids.forEachIndexed { i, id -> wallet.deposit(id, 100, "EUR", "count-$i") }
            wallet.deposit("adam", 100, "EUR", "count-again")
            ReadModel(ledger).use { model ->
                model.catchUp()
                assertEquals(ids.size, model.playerCount())
                val visited = ArrayList<String>()
                model.forEachPlayer(visited::add)
                assertEquals(ids.sorted(), visited)
                assertEquals(model.players(), visited.toSet())
            }
        }
    }

    @Test
    fun `indexed player audit equals the full scan and refuses an incomplete index`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            val players = listOf("ann", "ben", "cy")
            players.forEachIndexed { i, p -> wallet.deposit(p, 5_000, "EUR", "audit-funds-$i") }
            repeat(30) { i ->
                val p = players[i % 3]
                wallet.placeBet(p, "audit-$i", 50, "EUR", "F1|HOME|200")
                when (i % 4) {
                    0 -> wallet.settleWin("audit-$i", 100)
                    1 -> wallet.settleLoss("audit-$i")
                    2 -> wallet.cashOut("audit-$i", 40)
                }
                if (i % 5 == 0) wallet.withdraw(p, 10, "EUR", "audit-out-$i")
            }
            ReadModel(ledger).use { model ->
                model.catchUp()
                val size = ledger.head().size
                for (p in players + "nobody") {
                    val cash = ledger.account(Accounts.cash(p, "EUR"))
                    val escrow = ledger.account(Accounts.openBets(p, "EUR"))
                    val index = model.seqsForAccount(Accounts.cash(p, "EUR")) to model.seqsForAccount(Accounts.openBets(p, "EUR"))
                    val scanned = replayPlayerAudit(ledger, p, cash, escrow, size)
                    val indexed = replayPlayerAudit(ledger, p, cash, escrow, size, index)
                    assertEquals(true, scanned["reconciles"], p)
                    assertEquals(scanned, indexed, p)
                }
                val cash = ledger.account(Accounts.cash("ann", "EUR"))!!
                val escrow = ledger.account(Accounts.openBets("ann", "EUR"))!!
                val cashSeqs = model.seqsForAccount(Accounts.cash("ann", "EUR"))
                val escrowSeqs = model.seqsForAccount(Accounts.openBets("ann", "EUR"))
                // The index names exactly as many entries as the ledger's own version counter.
                assertEquals(cash.version, cashSeqs.size.toLong())
                assertEquals(escrow.version, escrowSeqs.size.toLong())
                fun reconciles(c: LongArray, e: LongArray) = replayPlayerAudit(ledger, "ann", cash, escrow, size, c to e)["reconciles"]
                assertEquals(true, reconciles(cashSeqs, escrowSeqs))
                // An index that lost a row must fail, even where the missing entries net to zero.
                assertEquals(false, reconciles(cashSeqs.copyOfRange(1, cashSeqs.size), escrowSeqs))
                assertEquals(false, reconciles(cashSeqs, escrowSeqs.copyOfRange(0, escrowSeqs.size - 1)))
                assertEquals(false, reconciles(LongArray(0), LongArray(0)))
                // An index naming an entry that does not post to the account must fail too.
                val foreign = model.seqsForAccount(Accounts.cash("ben", "EUR")).first()
                assertEquals(false, reconciles(cashSeqs + foreign, escrowSeqs))
                // Lengths prove nothing: missing rows padded out to the right count, with a repeated
                // entry or with an entry of another account, must fail as well.
                val repeated = LongArray(cashSeqs.size) { cashSeqs[0] }
                assertEquals(false, reconciles(repeated, escrowSeqs))
                val padded = cashSeqs.copyOfRange(1, cashSeqs.size) + foreign
                assertEquals(false, reconciles(padded, escrowSeqs))
            }
        }
    }

    @Test
    fun `an audit index repeating one entry fails even when the balance happens to agree`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            // Three entries for one account, balance 100: the first alone also replays to 100.
            val first = wallet.deposit("q", 100, "EUR", "pad-in-1").seq
            wallet.withdraw("q", 10, "EUR", "pad-out")
            wallet.deposit("q", 10, "EUR", "pad-in-2")
            val cash = ledger.account(Accounts.cash("q", "EUR"))!!
            assertEquals(3L, cash.version)
            assertEquals(100L, cash.balance)
            val size = ledger.head().size
            val forged = LongArray(3) { first } to LongArray(0)
            val report = replayPlayerAudit(ledger, "q", cash, null, size, forged)
            assertEquals("100", report["replayedCash"], "the forged index replays to the right balance...")
            assertEquals(false, report["reconciles"], "...and must still not reconcile")
        }
    }

    @Test
    fun `account index follows state captured at an earlier sequence`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("p", 1_000, "EUR", "early-funds")
            val cash = ledger.account(Accounts.cash("p", "EUR"))
            val escrow = ledger.account(Accounts.openBets("p", "EUR"))
            val size = ledger.head().size
            wallet.placeBet("p", "early-bet", 100, "EUR", "F1|HOME|200")
            wallet.deposit("p", 10, "EUR", "early-later")
            ReadModel(ledger).use { model ->
                model.catchUp()
                val index = model.seqsForAccount(Accounts.cash("p", "EUR")) to model.seqsForAccount(Accounts.openBets("p", "EUR"))
                val report = replayPlayerAudit(ledger, "p", cash, escrow, size, index)
                assertEquals(true, report["reconciles"])
                assertEquals(replayPlayerAudit(ledger, "p", cash, escrow, size), report)
            }
        }
    }

    @Test
    fun `fixture summaries hold odds outcomes and open counts without the bets`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("s", 10_000, "EUR", "summary-funds")
            wallet.placeBet("s", "s-win", 100, "EUR", "F1|HOME|200")
            wallet.placeBet("s", "s-lose", 100, "EUR", "F1|AWAY|350")
            wallet.placeBet("s", "s-cash", 100, "EUR", "F1|AWAY|360")
            wallet.placeBet("s", "s-open", 100, "EUR", "F1|DRAW|300")
            wallet.placeBet("s", "s-other", 100, "EUR", "F2|HOME|150")
            wallet.placeBet("s", "s-plain", 100, "EUR", "no-market")
            wallet.settleWin("s-win", 200)
            wallet.settleLoss("s-lose")
            wallet.cashOut("s-cash", 85)
            ReadModel(ledger).use { model ->
                model.catchUp()
                val summaries = model.fixtureSummaries()
                assertEquals(setOf("F1", "F2"), summaries.keys)
                val f1 = summaries.getValue("F1")
                assertEquals(mapOf("HOME" to 200, "AWAY" to 360, "DRAW" to 300), f1.odds)
                assertEquals(mapOf("HOME" to setOf("WON"), "AWAY" to setOf("LOST", "CASHED_OUT")), f1.outcomes)
                assertEquals(1, f1.openBets)
                assertEquals(ReadModel.FixtureSummary(mapOf("HOME" to 150), emptyMap(), 1), summaries.getValue("F2"))
                // The summaries say exactly what the full set of bets says.
                for ((fixture, bets) in model.allBets().groupBy { it.fixtureId }) {
                    val summary = summaries.getValue(fixture)
                    assertEquals(bets.count { it.isOpen }, summary.openBets)
                    assertEquals(bets.filter { it.outcome != null }.groupBy({ it.selection }, { it.outcome!! }).mapValues { it.value.toSet() },
                        summary.outcomes)
                }
            }
        }
    }

    @Test
    fun `first proof after a restart equals the proof before it`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        val before = Ledger.open(config(dir, signer, checkpointEvery = 50)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            repeat(333) { wallet.deposit("proof-$it", 1, "EUR", "proof-$it") }
            ledger.checkpoint()
            listOf(0L, 17L, 255L, 256L, 332L).map { ledger.inclusionProof(it) } to ledger.consistencyProof(100, 333)
        }
        Ledger.open(config(dir, signer, checkpointEvery = 50)).use { ledger ->
            val after = listOf(0L, 17L, 255L, 256L, 332L).map { ledger.inclusionProof(it) } to ledger.consistencyProof(100, 333)
            assertEquals(before, after)
            assertTrue(after.first.all { it.verify() && it.rootHash == ledger.head().merkleRoot })
        }
    }

    @Test
    fun `concurrent queries see only committed consistent views while bets are placed and settled`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            val players = (0 until 4).map { "racer-$it" }
            players.forEach { wallet.deposit(it, 1_000_000, "EUR", "race-funds-$it") }
            ReadModel(ledger).use { model ->
                val outcomes = race(8) { worker ->
                    if (worker < 4) {
                        val p = players[worker]
                        repeat(150) { i ->
                            wallet.placeBet(p, "race-$worker-$i", 10, "EUR", "F${i % 5}|HOME|200")
                            if (i % 3 != 0) wallet.cashOut("race-$worker-$i", 8)
                        }
                    } else repeat(300) {
                        val p = players[(worker + it) % 4]
                        model.catchUp()
                        val committed = ledger.head().size
                        for (bet in model.betsForPlayer(p, 40)) {
                            assertEquals(p, bet.playerId)
                            assertTrue(bet.placedSeq < committed || bet.placedSeq < ledger.head().size)
                            assertEquals(bet.betId, ledger.entry(bet.placedSeq).entry.metadata["betId"])
                            bet.settledSeq?.let { assertEquals("bet:${bet.betId}:settle", ledger.entry(it).entry.idempotencyKey) }
                        }
                        for (bet in model.openBetsForPlayer(p)) assertTrue(bet.isOpen && bet.playerId == p)
                        val summaries = model.fixtureSummaries()
                        assertTrue(summaries.values.all { it.openBets >= 0 })
                        val cash = ledger.account(Accounts.cash(p, "EUR"))
                        val escrow = ledger.account(Accounts.openBets(p, "EUR"))
                        val size = ledger.head().size
                        // Balances are published before the head: read as far as the captured states reach.
                        model.catchUp(maxOf(size, (cash?.lastSeq ?: -1) + 1, (escrow?.lastSeq ?: -1) + 1))
                        val index = model.seqsForAccount(Accounts.cash(p, "EUR")) to model.seqsForAccount(Accounts.openBets(p, "EUR"))
                        assertEquals(true, replayPlayerAudit(ledger, p, cash, escrow, size, index)["reconciles"])
                        assertTrue(model.playerCount() <= players.size)
                    }
                }
                outcomes.forEach { it.getOrThrow() }
                model.catchUp()
                // 50 of each player's 150 bets were left open.
                assertEquals(4 * 50, model.totals().openBets)
                assertEquals(4 * 50, model.fixtureSummaries().values.sumOf { it.openBets })
                players.forEach { p -> assertEquals(50, model.openBetsForPlayer(p).size) }
            }
        }
    }

    // ---- Growth measurement (opt-in: ./gradlew test -PincludeSoak) ----

    private class Measured(val entries: Long, val heapPerEntry: Long, val writesPerSecond: Long, val micros: Map<String, Double>)

    private fun usedHeap(): Long {
        repeat(3) { System.gc(); Thread.sleep(100) }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    /** Best of five rounds, so one GC pause or scheduler hiccup cannot fail the comparison. */
    private fun micros(iterations: Int, body: (Int) -> Unit): Double {
        repeat(minOf(iterations, 2_000)) { body(it) }
        return (0 until 5).minOf {
            val start = System.nanoTime()
            repeat(iterations) { i -> body(i) }
            (System.nanoTime() - start) / 1_000.0 / iterations
        }
    }

    /** About 50 entries per player, plus one "depositor" whose single bet is followed by entries/20 deposits. */
    private fun measure(players: Int): Measured {
        val baseline = usedHeap()
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate(), checkpointEvery = 10_000)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            val workers = 256
            val pool = Executors.newFixedThreadPool(workers)
            val start = System.nanoTime()
            try {
                (0 until workers).map { w ->
                    pool.submit {
                        var p = w
                        while (p < players) {
                            wallet.deposit("p$p", 1_000_000, "EUR", "pay-$p")
                            for (b in 0 until 25) {
                                wallet.placeBet("p$p", "b$p-$b", 100, "EUR", "F${(p + b) % 500}|HOME|200")
                                if (b < 24) { if (b % 2 == 0) wallet.settleLoss("b$p-$b") else wallet.cashOut("b$p-$b", 85) }
                            }
                            p += workers
                        }
                    }
                }.forEach { it.get() }
                val writesPerSecond = (ledger.head().size * 1_000_000_000.0 / (System.nanoTime() - start)).toLong()
                wallet.deposit("depositor", 1_000, "EUR", "depositor-0")
                wallet.placeBet("depositor", "depositor-bet", 100, "EUR", "F1|HOME|200")
                val tail = players * 50 / 20
                (0 until workers).map { w ->
                    pool.submit { var i = w; while (i < tail) { wallet.deposit("depositor", 1, "EUR", "depositor-top-up-$i"); i += workers } }
                }.forEach { it.get() }
                val entries = ledger.head().size
                ReadModel(ledger).use { model ->
                    model.catchUp()
                    val heap = usedHeap() - baseline
                    val audited = "p${players - 1}" // among the last to finish, so the unindexed audit would scan everything
                    val cash = ledger.account(Accounts.cash(audited, "EUR"))
                    val escrow = ledger.account(Accounts.openBets(audited, "EUR"))
                    val timings = linkedMapOf(
                        "balance" to micros(200_000) { ledger.balance(Accounts.cash("p${it % players}", "EUR")) },
                        "open bets" to micros(200_000) { model.openBetsForPlayer("p${it % players}") },
                        "history page" to micros(20_000) { model.historyForPlayer("p${it % players}", 40) },
                        "bet page" to micros(5_000) { model.betsForPlayer("p${it % players}", 40) },
                        "bet page after deposits" to micros(5_000) { model.betsForPlayer("depositor", 40) },
                        "player count" to micros(200_000) { model.playerCount() },
                        "player audit" to micros(500) {
                            val index = model.seqsForAccount(Accounts.cash(audited, "EUR")) to model.seqsForAccount(Accounts.openBets(audited, "EUR"))
                            replayPlayerAudit(ledger, audited, cash, escrow, entries, index)
                        },
                        "inclusion proof" to micros(2_000) { ledger.inclusionProof((it * 7919L) % entries, entries) },
                    )
                    return Measured(entries, heap / entries, writesPerSecond, timings)
                }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    @Tag("soak")
    fun `customer reads stay flat when the ledger quadruples`() {
        val small = measure(1_000)
        val large = measure(4_000)
        for (m in listOf(small, large)) {
            println("read scaling: ${m.entries} entries, ${m.heapPerEntry} heap bytes/entry, ${m.writesPerSecond} writes/s, " +
                m.micros.entries.joinToString { "${it.key} %.2f us".format(it.value) })
        }
        assertTrue(large.entries >= small.entries * 39 / 10)
        for ((name, before) in small.micros) {
            val after = large.micros.getValue(name)
            // A linear read would cost four times as much. Allow two for cache misses over a larger
            // working set, and ignore differences below a quarter of a microsecond as timer noise.
            assertTrue(after <= before * 2 + 0.25, "$name grew from %.2f to %.2f us across a fourfold ledger".format(before, after))
        }
    }
}
