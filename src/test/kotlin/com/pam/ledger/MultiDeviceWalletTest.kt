package com.pam.ledger

import com.pam.ledger.SportsbookWallet.Accounts
import com.pam.ledger.TestSupport.LEDGER_ID
import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * ============================================================================
 *  REGULATORY TEST SUITE: one player, many devices, all at once
 * ============================================================================
 *
 *  Scenario: a player is logged in on a phone, a tablet, a laptop and a
 *  smart TV simultaneously. Every device is placing bets, checking balances,
 *  cashing out and withdrawing, while the settlement feed resolves bets and
 *  redelivers results. A regulator wants assurance on these properties:
 *
 *   R1  NO DOUBLE SPEND. The same money can never fund two things
 *       (two bets, a bet and a withdrawal, two withdrawals).
 *   R2  NO NEGATIVE BALANCE, at any instant, not just at the end.
 *   R3  NO PHANTOM BALANCE. No device is ever shown a balance the ledger did
 *       not actually hold at some committed point in time (no dirty reads).
 *   R4  EXACTLY-ONCE. Retries, double-taps and duplicate feed messages never
 *       move money twice; a bet is resolved exactly once.
 *   R5  FULL RECONCILIATION. The player's balance equals what an independent
 *       model built purely from the CLIENTS' successful receipts predicts.
 *   R6  PROVABILITY. Every transaction is provably in the signed ledger
 *       (inclusion proofs), and the ledger provably only grew while all this
 *       was happening (consistency proofs between signed checkpoints taken
 *       mid-storm).
 *
 *  Crucially, the evidence comes FROM THE LEDGER: we replay the player's
 *  history entry by entry and check every intermediate balance, rather than
 *  trusting the wallet's own final numbers.
 */
class MultiDeviceWalletTest {
    private val signer = Ed25519TreeHeadSigner.generate()
    private val cur = "EUR"

    // ---------------------------------------------------------------------
    //  Ledger-derived evidence helpers
    // ---------------------------------------------------------------------

    /** Every balance [account] has EVER held, in commit order, recomputed from the ledger itself. */
    private fun balanceHistory(ledger: Ledger, account: AccountId): List<Long> {
        val history = mutableListOf(0L)
        var balance = 0L
        for (seq in 0 until ledger.head().size) {
            val posting = ledger.entry(seq).entry.postings.firstOrNull { it.account == account } ?: continue
            balance += posting.amount
            history += balance
        }
        return history
    }

    /** Every entry touching [account]. */
    private fun entriesTouching(ledger: Ledger, account: AccountId): List<SealedEntry> =
        (0 until ledger.head().size).map(ledger::entry).filter { s -> s.entry.postings.any { it.account == account } }

    // ---------------------------------------------------------------------
    //  R1: targeted double-spend races
    // ---------------------------------------------------------------------

    /** Phone withdraws €100 while tablet bets €100, with only €100 available. One must lose. */
    @Test
    fun `R1 - withdrawal and bet racing for the same funds cannot both succeed`() {
        repeat(25) { round -> // repeat to give the scheduler many chances to interleave badly
            Ledger.open(config(tempDir(), signer)).use { ledger ->
                val wallet = SportsbookWallet(ledger)
                wallet.deposit("alice", 100_00, cur, "dep-$round")

                val results = race(2) { device ->
                    if (device == 0) wallet.withdraw("alice", 100_00, cur, "wd-$round")
                    else wallet.placeBet("alice", "bet-$round", 100_00, cur)
                }

                assertEquals(1, results.count { it.isSuccess }, "round $round: exactly one may succeed")
                assertTrue(results.single { it.isFailure }.exceptionOrNull() is InsufficientFundsException)
                assertEquals(0L, wallet.cashBalance("alice", cur))
                // Ledger evidence: cash never dipped below zero at any committed point.
                assertTrue(balanceHistory(ledger, Accounts.cash("alice", cur)).all { it >= 0 })
            }
        }
    }

    /** Eight devices each try to withdraw the whole balance at the same instant. */
    @Test
    fun `R1 - simultaneous full-balance withdrawals from many devices pay out once`() {
        Ledger.open(config(tempDir(), signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("bob", 250_00, cur, "dep-bob")

            val results = race(8) { device -> wallet.withdraw("bob", 250_00, cur, "wd-device-$device") }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(0L, wallet.cashBalance("bob", cur))
            // Ledger evidence: exactly ONE withdrawal entry exists for bob.
            val withdrawals = entriesTouching(ledger, Accounts.cash("bob", cur)).filter { it.entry.kind == "WITHDRAWAL" }
            assertEquals(1, withdrawals.size)
            assertEquals(-250_00L, withdrawals.single().entry.postings.single { it.account == Accounts.cash("bob", cur) }.amount)
        }
    }

    /** Player cashes out on the phone at the exact moment the result comes in from the feed. */
    @Test
    fun `R1 - cash-out racing settlement resolves the bet exactly once`() {
        repeat(25) { round ->
            Ledger.open(config(tempDir(), signer)).use { ledger ->
                val wallet = SportsbookWallet(ledger)
                wallet.deposit("cara", 50_00, cur, "dep")
                wallet.placeBet("cara", "acca", 10_00, cur)

                val results = race(2) { who -> if (who == 0) wallet.cashOut("acca", 18_00) else wallet.settleWin("acca", 40_00) }

                assertEquals(1, results.count { it.isSuccess }, "round $round")
                val kinds = entriesTouching(ledger, Accounts.openBets("cara", cur)).map { it.entry.kind }
                assertEquals(1, kinds.count { it == "BET_CASHED_OUT" || it == "BET_WON" }, "one resolution recorded")
                assertEquals(0L, wallet.openBetsBalance("cara", cur))
                val expected = 40_00L + if (kinds.contains("BET_WON")) 40_00 else 18_00
                assertEquals(expected, wallet.cashBalance("cara", cur))
            }
        }
    }

    /** Winnings cannot be withdrawn before the win is committed (no spending credit that does not exist yet). */
    @Test
    fun `R1 - withdrawing winnings only succeeds against committed settlement`() {
        repeat(25) { round ->
            Ledger.open(config(tempDir(), signer)).use { ledger ->
                val wallet = SportsbookWallet(ledger)
                wallet.deposit("dan", 10_00, cur, "dep")
                wallet.placeBet("dan", "b", 10_00, cur) // cash now 0

                val results = race(2) { who -> if (who == 0) wallet.settleWin("b", 30_00) else wallet.withdraw("dan", 30_00, cur, "wd") }

                val withdrew = results[1].isSuccess
                // If the withdrawal succeeded, the ledger must show the win committed BEFORE it.
                if (withdrew) {
                    val seqWin = ledger.findByIdempotencyKey("bet:b:settle")!!.entry.seq
                    val seqWd = ledger.findByIdempotencyKey("withdrawal:wd")!!.entry.seq
                    assertTrue(seqWin < seqWd, "round $round: withdrawal committed before the win that funded it")
                } else {
                    assertTrue(results[1].exceptionOrNull() is InsufficientFundsException)
                }
                assertTrue(balanceHistory(ledger, Accounts.cash("dan", cur)).all { it >= 0 })
            }
        }
    }

    // ---------------------------------------------------------------------
    //  R4: duplicates from flaky networks and double taps
    // ---------------------------------------------------------------------

    /** The "Place bet" button double-tapped on two devices, with retries: one bet, one debit. */
    @Test
    fun `R4 - double-tapped and retried bet placement debits once`() {
        Ledger.open(config(tempDir(), signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("eve", 100_00, cur, "dep")

            // 4 devices x 5 retries, all with the same client-generated betId.
            val receipts = race(20) { wallet.placeBet("eve", "slip-123", 25_00, cur) }.map { it.getOrThrow() }

            assertEquals(1, receipts.map { it.seq }.toSet().size, "every caller sees the same entry")
            assertEquals(1, receipts.count { !it.replayed }, "exactly one actually wrote")
            assertEquals(75_00L, wallet.cashBalance("eve", cur))
            assertEquals(1, entriesTouching(ledger, Accounts.cash("eve", cur)).count { it.entry.kind == "BET_PLACED" })
        }
    }

    /** The same bet slip id reused for a DIFFERENT stake is refused, not silently merged. */
    @Test
    fun `R4 - reusing a bet id with a different stake is refused`() {
        Ledger.open(config(tempDir(), signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("fay", 100_00, cur, "dep")
            wallet.placeBet("fay", "slip-9", 10_00, cur)
            val e = runCatching { wallet.placeBet("fay", "slip-9", 90_00, cur) }.exceptionOrNull()
            assertTrue(e is IdempotencyConflictException, "got $e")
            assertEquals(90_00L, wallet.cashBalance("fay", cur))
        }
    }

    /** Settling a bet that was never placed cannot conjure money. */
    @Test
    fun `R4 - settling an unknown bet moves no money`() {
        Ledger.open(config(tempDir(), signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            val e = runCatching { wallet.settleWin("ghost-bet", 1_000_00) }.exceptionOrNull()
            assertTrue(e is NotFoundException, "got $e")
            assertEquals(0L, ledger.head().size)
        }
    }

    // ---------------------------------------------------------------------
    //  THE STORM: all properties at once
    // ---------------------------------------------------------------------

    /**
     * One player, 6 devices, a duplicating settlement feed, balance-polling
     * screens, and a regulator taking signed checkpoints, all concurrently.
     * Then every regulatory property R1-R6 is checked from ledger evidence.
     */
    @Test
    fun `STORM - one player on many devices with live settlement satisfies R1 to R6`() {
        val dir = tempDir()
        val player = "whale"
        val cash = Accounts.cash(player, cur)
        val escrow = Accounts.openBets(player, cur)

        // ---- Independent client-side model (R5): built ONLY from successful, non-replayed receipts. ----
        val modelCash = AtomicLong()
        val stakes = ConcurrentHashMap<String, Long>()         // betId -> stake, for bets the devices placed
        val placedQueue = ConcurrentLinkedQueue<String>()       // bets awaiting settlement
        val settledBets = ConcurrentHashMap.newKeySet<String>()
        val allReceipts = CopyOnWriteArrayList<Receipt>()
        val observedCash = Collections.synchronizedList(ArrayList<Long>())
        val checkpoints = CopyOnWriteArrayList<SignedTreeHead>()
        val rejectedForFunds = AtomicLong()

        fun record(r: Receipt, cashDelta: Long) {
            allReceipts += r
            if (!r.replayed) modelCash.addAndGet(cashDelta) // a replay moved no money
        }

        Ledger.open(config(dir, signer, checkpointEvery = 1_000_000)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            record(wallet.deposit(player, 500_00, cur, "initial"), 500_00)

            val stop = AtomicBoolean(false)
            val pool = Executors.newFixedThreadPool(12)
            val background = ArrayList<java.util.concurrent.Future<*>>()

            // Balance screens: 3 devices polling the balance as fast as they can (R2, R3).
            repeat(3) {
                background += pool.submit {
                    while (!stop.get()) observedCash += wallet.cashBalance(player, cur)
                }
            }

            // Regulator: signs checkpoints mid-storm (R6).
            background += pool.submit {
                while (!stop.get()) {
                    checkpoints += ledger.checkpoint()
                    Thread.sleep(5)
                }
            }

            // Settlement feed: 2 workers, each result sent TWICE and sometimes with a conflicting result (R4).
            repeat(2) { w ->
                background += pool.submit {
                    val rnd = Random(1000 + w)
                    while (!stop.get() || placedQueue.isNotEmpty()) {
                        val bet = placedQueue.poll() ?: continue
                        val stake = stakes.getValue(bet)
                        val (kind, payout) = when (rnd.nextInt(4)) {
                            0 -> "WIN" to stake * rnd.nextLong(2, 6)
                            1 -> "VOID" to stake
                            else -> "LOSS" to 0L
                        }
                        try {
                            repeat(2) { // duplicate delivery
                                val r = when (kind) {
                                    "WIN" -> wallet.settleWin(bet, payout)
                                    "VOID" -> wallet.voidBet(bet)
                                    else -> wallet.settleLoss(bet)
                                }
                                record(r, payout)
                            }
                        } catch (e: IdempotencyConflictException) {
                            // A device cashed this bet out first. Correct behaviour: the feed loses.
                            check(ledger.findByIdempotencyKey("bet:$bet:settle")!!.entry.kind == "BET_CASHED_OUT")
                            continue
                        }
                        // A late, CONFLICTING message must be refused.
                        val conflict = runCatching { if (kind == "LOSS") wallet.voidBet(bet) else wallet.settleLoss(bet) }
                        check(conflict.exceptionOrNull() is IdempotencyConflictException) { "conflicting settlement accepted for $bet" }
                        settledBets += bet
                    }
                }
            }

            // Player devices: 6 devices placing bets, cashing out, depositing and withdrawing.
            val devices = (0 until 6).map { d ->
                pool.submit {
                    val rnd = Random(d)
                    repeat(300) { i ->
                        try {
                            when (rnd.nextInt(20)) {
                                in 0..10 -> {
                                    val bet = "d$d-$i"
                                    val stake = rnd.nextLong(1_00, 60_00)
                                    stakes[bet] = stake
                                    // Each placement is sent twice, as a flaky mobile network would.
                                    repeat(2) { record(wallet.placeBet(player, bet, stake, cur), -stake) }
                                    placedQueue += bet
                                }
                                in 11..13 -> { // cash out someone's open bet, racing the feed
                                    val bet = stakes.keys.randomOrNull(rnd) ?: return@repeat
                                    val offer = stakes.getValue(bet) / 2
                                    runCatching { wallet.cashOut(bet, offer) }.onSuccess { record(it, offer) }
                                }
                                in 14..16 -> {
                                    val amt = rnd.nextLong(1_00, 120_00)
                                    record(wallet.withdraw(player, amt, cur, "wd-d$d-$i"), -amt)
                                }
                                else -> {
                                    val amt = rnd.nextLong(1_00, 40_00)
                                    record(wallet.deposit(player, amt, cur, "dep-d$d-$i"), amt)
                                }
                            }
                        } catch (e: InsufficientFundsException) {
                            rejectedForFunds.incrementAndGet() // nothing to undo: rejected means nothing was written
                        }
                    }
                }
            }
            devices.forEach { it.get(3, TimeUnit.MINUTES) } // rethrows any device failure
            stop.set(true)
            pool.shutdown()
            assertTrue(pool.awaitTermination(3, TimeUnit.MINUTES), "workers did not finish")
            // Background workers (feed, screens, regulator) must also have finished cleanly.
            background.forEach { it.get() }

            // Bets that failed placement were put in `stakes` but never queued. Remove them from the model.
            val placedInLedger = stakes.keys.filter { ledger.findByIdempotencyKey("bet:$it:place") != null }.toSet()
            val openBets = placedInLedger.filter { ledger.findByIdempotencyKey("bet:$it:settle") == null }

            // ================= REGULATORY ASSERTIONS =================
            val cashHistory = balanceHistory(ledger, cash)
            val escrowHistory = balanceHistory(ledger, escrow)

            // R1 + R2: at no committed point did cash or escrow go negative.
            assertTrue(cashHistory.all { it >= 0 }, "R2: cash went negative at some point")
            assertTrue(escrowHistory.all { it >= 0 }, "R2: escrow went negative at some point")

            // R1: every bet placed exactly once, resolved at most once, and escrow equals open stakes exactly.
            val entries = entriesTouching(ledger, escrow)
            val placementsPerBet = entries.filter { it.entry.kind == "BET_PLACED" }.groupingBy { it.entry.metadata["betId"] }.eachCount()
            val settlementsPerBet = entries.filter { it.entry.kind != "BET_PLACED" }.groupingBy { it.entry.metadata["betId"] }.eachCount()
            assertTrue(placementsPerBet.values.all { it == 1 }, "R1/R4: a bet was placed twice")
            assertTrue(settlementsPerBet.values.all { it == 1 }, "R4: a bet was settled twice")
            assertEquals(openBets.sumOf { stakes.getValue(it) }, ledger.balance(escrow), "R1: escrow != sum of open stakes")

            // R3: every balance any screen ever displayed is one the ledger really held.
            val legitimate = cashHistory.toHashSet()
            val phantoms = observedCash.filterNot { it in legitimate }
            assertTrue(phantoms.isEmpty(), "R3: devices saw balances the ledger never committed: ${phantoms.take(5)}")

            // R5: independent client-side model agrees with the ledger to the cent.
            assertEquals(modelCash.get(), wallet.cashBalance(player, cur), "R5: cash does not reconcile with client receipts")
            assertEquals(ledger.head().size, allReceipts.filter { !it.replayed }.size.toLong(), "R5: every entry has exactly one originating receipt")

            // R6: every receipt any device holds is provably in the final signed tree...
            val finalSth = ledger.checkpoint()
            assertTrue(finalSth.verify(ledger.publicKey))
            for (r in allReceipts) {
                val proof = ledger.inclusionProof(r.seq, finalSth.head.treeSize)
                assertEquals(r.leafHash, proof.leafHash, "R6: receipt does not match ledger entry")
                assertTrue(MerkleVerify.inclusion(r.leafHash, r.seq, finalSth.head.treeSize, proof.auditPath, finalSth.head.rootHash))
            }
            // ...and each mid-storm checkpoint is a provable prefix of the next (append-only, no rewrites).
            val sths = (checkpoints + finalSth).filter { it.head.treeSize > 0 }
            assertTrue(sths.size >= 2, "regulator should have taken several checkpoints")
            for ((a, b) in sths.zipWithNext()) {
                assertTrue(a.verify(ledger.publicKey) && b.verify(ledger.publicKey))
                val proof = ledger.consistencyProof(a.head.treeSize, b.head.treeSize)
                assertTrue(
                    MerkleVerify.consistency(a.head.treeSize, b.head.treeSize, a.head.rootHash, b.head.rootHash, proof),
                    "R6: ledger was not append-only between checkpoints ${a.head.treeSize} and ${b.head.treeSize}",
                )
            }

            println("STORM: ${ledger.head().size} entries, ${placedInLedger.size} bets, ${settledBets.size} feed-settled, " +
                "${rejectedForFunds.get()} rejected for funds, ${observedCash.size} balance reads, ${sths.size} checkpoints")
        }

        // Finally, an independent auditor reaches the same verdict from the files alone.
        val report = LedgerVerifier.verify(dir, LEDGER_ID, listOf(signer.publicKey))
        assertTrue(report.ok, report.toString())
    }
}
