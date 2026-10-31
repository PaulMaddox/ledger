package com.pam.ledger

import com.pam.ledger.SportsbookWallet.Accounts
import com.pam.ledger.TestSupport.LEDGER_ID
import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Concurrency tests. Each one stages the nastiest race we can think of and
 * checks that the ledger's invariants survive it.
 */
class ConcurrencyTest {
    private val signer = Ed25519TreeHeadSigner.generate()

    /** 64 threads try to stake the same last €1.00. Exactly one may succeed. */
    @Test
    fun `no double spend when many bets race for the same funds`() {
        val dir = tempDir()
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("alice", 100, "EUR", "psp-1")

            val results = race(64) { i -> wallet.placeBet("alice", "bet-$i", 100, "EUR") }

            assertEquals(1, results.count { it.isSuccess }, "exactly one bet should be accepted")
            assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is InsufficientFundsException })
            assertEquals(0, wallet.cashBalance("alice", "EUR"))
            assertEquals(100, wallet.openBetsBalance("alice", "EUR"))
        }
    }

    /** The settlement feed fires WON, LOST and VOID at the same bet simultaneously. One outcome wins. */
    @Test
    fun `a bet settles exactly once even under conflicting concurrent settlements`() {
        val dir = tempDir()
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("bob", 1_000, "EUR", "psp-bob")
            wallet.placeBet("bob", "B1", 500, "EUR")

            val results = race(48) { i ->
                when (i % 3) {
                    0 -> "WON" to wallet.settleWin("B1", 1_250)
                    1 -> "LOST" to wallet.settleLoss("B1")
                    else -> "VOID" to wallet.voidBet("B1")
                }
            }
            val winners = results.mapNotNull { it.getOrNull() }
            val outcomes = winners.map { it.first }.toSet()
            assertEquals(1, outcomes.size, "only one kind of outcome may succeed, got $outcomes")
            // All successful callers got the same entry; exactly one actually wrote it.
            assertEquals(1, winners.map { it.second.seq }.toSet().size)
            assertEquals(1, winners.count { !it.second.replayed })
            assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is IdempotencyConflictException })

            val expectedCash = when (outcomes.single()) {
                "WON" -> 500L + 1_250
                "LOST" -> 500L
                else -> 1_000L
            }
            assertEquals(expectedCash, wallet.cashBalance("bob", "EUR"))
            assertEquals(0, wallet.openBetsBalance("bob", "EUR"))
            assertEquals(3, ledger.head().size, "deposit + place + one settlement")
        }
    }

    /** A PSP webhook redelivered 50 times at once credits the player exactly once. */
    @Test
    fun `concurrent duplicate deposits are recorded once`() {
        val dir = tempDir()
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            val receipts = race(50) { wallet.deposit("carol", 2_500, "GBP", "psp-xyz") }.map { it.getOrThrow() }

            assertEquals(1, receipts.map { it.seq }.toSet().size)
            assertEquals(1, receipts.count { !it.replayed })
            assertEquals(2_500, wallet.cashBalance("carol", "GBP"))
            assertEquals(1, ledger.head().size)
        }
    }

    /** Reusing a key for different content is refused rather than silently ignored. */
    @Test
    fun `reusing an idempotency key with different content is rejected`() {
        Ledger.open(config(tempDir(), signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("dave", 100, "EUR", "psp-7")
            val e = runCatching { wallet.deposit("dave", 999, "EUR", "psp-7") }.exceptionOrNull()
            assertTrue(e is IdempotencyConflictException, "got $e")
            assertEquals(100, wallet.cashBalance("dave", "EUR"))
        }
    }

    /** Optimistic concurrency: a stale expected version is rejected. */
    @Test
    fun `expected version guard rejects stale writers`() {
        Ledger.open(config(tempDir(), signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("erin", 1_000, "EUR", "psp-e")
            val cash = Accounts.cash("erin", "EUR")
            val house = Accounts.revenue("EUR")
            val v = ledger.account(cash)!!.version

            fun bonusRequest(key: String) = TransactionRequest(
                key, "ADJUSTMENT",
                listOf(Posting(house, "EUR", -10), Posting(cash, "EUR", 10)),
                expectedVersions = mapOf(cash to v),
            )
            ledger.append(bonusRequest("adj-1")) // version v -> v+1
            val e = runCatching { ledger.append(bonusRequest("adj-2")) }.exceptionOrNull()
            assertTrue(e is VersionConflictException, "got $e")
        }
    }

    /**
     * The big one: 16 threads x 400 random operations over 40 players.
     * Afterwards: no player is negative, money is conserved, the independent
     * verifier passes, and a restart reproduces the exact same state.
     */
    @Test
    fun `stress - invariants hold under heavy concurrent load and survive restart`() {
        val dir = tempDir()
        val players = (1..40).map { "p$it" }
        val committed = ConcurrentLinkedQueue<Receipt>()
        val snapshot: Map<String, Long>
        val headBefore: LedgerHead

        Ledger.open(config(dir, signer, checkpointEvery = 250)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            players.forEach { committed += wallet.deposit(it, 50_00, "EUR", "dep-$it") }

            race(16) { t ->
                val rnd = Random(t * 7919)
                val myOpenBets = ArrayList<String>()
                repeat(400) { i ->
                    val player = players[rnd.nextInt(players.size)]
                    try {
                        when (rnd.nextInt(10)) {
                            in 0..4 -> {
                                val betId = "$player-t$t-$i"
                                committed += wallet.placeBet(player, betId, rnd.nextLong(1, 20_00), "EUR")
                                myOpenBets += betId
                            }
                            in 5..7 -> if (myOpenBets.isNotEmpty()) {
                                val bet = myOpenBets.removeAt(rnd.nextInt(myOpenBets.size))
                                committed += if (rnd.nextBoolean()) wallet.settleWin(bet, rnd.nextLong(1, 60_00)) else wallet.settleLoss(bet)
                            }
                            8 -> committed += wallet.withdraw(player, rnd.nextLong(1, 30_00), "EUR", "wd-t$t-$i")
                            else -> committed += wallet.deposit(player, rnd.nextLong(1, 10_00), "EUR", "dep-t$t-$i")
                        }
                    } catch (e: InsufficientFundsException) {
                        // expected business rejection
                    }
                }
            }

            val head = ledger.head()
            // Every successful receipt is a distinct, gap-free sequence number.
            val seqs = committed.map { it.seq }.sorted()
            assertEquals((0 until head.size).toList(), seqs, "receipts must map 1:1 onto ledger entries")

            for (p in players) {
                assertTrue(wallet.cashBalance(p, "EUR") >= 0, "$p cash went negative")
                assertTrue(wallet.openBetsBalance(p, "EUR") >= 0, "$p escrow went negative")
            }
            snapshot = players.associateWith { wallet.cashBalance(it, "EUR") }
            headBefore = head
        }

        val report = LedgerVerifier.verify(dir, LEDGER_ID, listOf(signer.publicKey))
        assertTrue(report.ok, report.toString())
        assertEquals(mapOf("EUR" to 0L), report.totalsByCurrency)

        // Restart: state is rebuilt purely from disk and must match exactly.
        Ledger.open(config(dir, signer)).use { reopened ->
            val wallet = SportsbookWallet(reopened)
            assertEquals(headBefore, reopened.head())
            players.forEach { assertEquals(snapshot[it], wallet.cashBalance(it, "EUR"), "balance of $it after restart") }
        }
    }

    @Test
    fun `submissions after close are refused`() {
        val ledger = Ledger.open(config(tempDir(), signer))
        ledger.close()
        val e = runCatching { SportsbookWallet(ledger).deposit("x", 1, "EUR", "r") }.exceptionOrNull()
        assertTrue(e is LedgerClosedException, "got $e")
    }

    @Test
    fun `a second writer cannot open the same ledger directory`() {
        val dir = tempDir()
        Ledger.open(config(dir, signer)).use {
            val e = runCatching { Ledger.open(config(dir, signer)) }.exceptionOrNull()
            assertTrue(e is IllegalStateException, "got $e")
        }
    }
}
