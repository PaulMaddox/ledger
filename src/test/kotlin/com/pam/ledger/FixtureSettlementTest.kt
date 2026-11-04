package com.pam.ledger

import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import com.pam.ledger.web.Book
import com.pam.ledger.web.ReadModel
import com.pam.ledger.web.payout
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fixture settlement is pipelined through a bounded window of asynchronous wallet
 * submissions. These tests hold the window, the failure paths and the races to the
 * ledger's own replayed history: every bet resolves exactly once, escrow empties, and
 * every balance equals what the entries say it must be.
 */
class FixtureSettlementTest {

    private val stake = 100L
    private val funds = 10_000L
    private val selections = listOf("HOME", "DRAW", "AWAY")

    private class Placed(val betId: String, val playerId: String, val selection: String)

    /** One player per bet, so each balance is independently checkable. */
    private fun placeBets(book: Book, wallet: SportsbookWallet, fixtureId: String, count: Int, prefix: String = "b"): List<Placed> =
        (0 until count).map { i ->
            val player = "$prefix-player-$i"
            val selection = selections[i % selections.size]
            wallet.deposit(player, funds, "EUR", "$prefix-funds-$i")
            book.placeBet(player, fixtureId, selection, stake, "$prefix-$i")
            Placed("$prefix-$i", player, selection)
        }

    private fun settleEntries(ledger: Ledger): List<LedgerEntry> =
        (0 until ledger.head().size).map { ledger.entry(it).entry }.filter { it.idempotencyKey.endsWith(":settle") }

    /**
     * Checks, from the ledger's entries alone, that every bet resolved exactly once with the
     * money its own settlement entry implies, and that escrow is empty.
     */
    private fun assertResolvedOnce(ledger: Ledger, wallet: SportsbookWallet, fixture: Book.Fixture, bets: List<Placed>, allowCashOut: Boolean = false) {
        val settles = settleEntries(ledger)
        assertEquals(bets.size, settles.size, "exactly one settlement entry per bet")
        val byBet = settles.associateBy { it.metadata["betId"] }
        assertEquals(bets.map { it.betId }.toSet(), byBet.keys)
        val result = fixture.result
        for (bet in bets) {
            val entry = byBet.getValue(bet.betId)
            val expectedKind = when {
                result == "VOID" -> "BET_VOIDED"
                bet.selection == result -> "BET_WON"
                else -> "BET_LOST"
            }
            val expectedPayout = when (entry.kind) {
                "BET_CASHED_OUT" -> {
                    assertTrue(allowCashOut, "unexpected cash-out of ${bet.betId}")
                    payout(stake, 85)
                }
                expectedKind -> when (expectedKind) {
                    "BET_WON" -> payout(stake, fixture.odds.getValue(bet.selection))
                    "BET_VOIDED" -> stake
                    else -> 0L
                }
                else -> error("${bet.betId} settled as ${entry.kind}, expected $expectedKind")
            }
            assertEquals(funds - stake + expectedPayout, wallet.cashBalance(bet.playerId, "EUR"), bet.betId)
            assertEquals(0L, wallet.openBetsBalance(bet.playerId, "EUR"), "escrow must be released for ${bet.betId}")
        }
    }

    private fun assertJournalVerifies(directory: Path, signer: TreeHeadSigner) {
        assertTrue(LedgerVerifier.verify(directory, TestSupport.LEDGER_ID, listOf(signer.publicKey)).ok)
    }

    private fun awaitTrue(what: String, seconds: Long = 10, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue(condition(), what)
    }

    /** Completion callbacks held back, while the writer keeps committing: the window is then the only limit. */
    private class HeldCompletions {
        val delayed = AtomicBoolean(false)
        private val held = ConcurrentLinkedQueue<Runnable>()
        val executor = Executor { task -> if (delayed.get()) held.add(task) else task.run() }
        fun release(until: () -> Boolean = { true }) {
            delayed.set(false)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            do {
                while (true) (held.poll() ?: break).run()
                if (until()) return
                Thread.sleep(1)
            } while (System.nanoTime() < deadline)
            error("held completions were not released")
        }
    }

    @Test
    fun `window bounds admitted settlements and every bet resolves once in submission order`() {
        for (window in listOf(1, 8)) {
            val directory = tempDir()
            val signer = Ed25519TreeHeadSigner.generate()
            val completions = HeldCompletions()
            Ledger.open(config(directory, signer).copy(completionExecutor = completions.executor)).use { ledger ->
                val wallet = SportsbookWallet(ledger)
                ReadModel(ledger).use { model ->
                    val book = Book(wallet, model).apply { settlementWindow = window }
                    val fixture = book.addFixture(Random(window))
                    val bets = placeBets(book, wallet, fixture.id, 40)
                    model.catchUp()
                    val expectedOrder = model.openBetsOn(fixture.id).map { it.betId }
                    val before = ledger.head().size

                    completions.delayed.set(true)
                    val settlement = CompletableFuture.supplyAsync { book.settle(fixture.id, "HOME") }
                    awaitTrue("window $window must fill") { ledger.head().size - before >= window }
                    Thread.sleep(300)
                    assertEquals(window.toLong(), ledger.head().size - before, "nothing beyond the window is admitted while outcomes are pending")
                    assertFalse(settlement.isDone)
                    assertFalse(fixture.settlementComplete, "the fixture cannot complete while outcomes are unknown")

                    completions.release { settlement.isDone }
                    val result = settlement.get(10, TimeUnit.SECONDS)
                    assertEquals(bets.count { it.selection == "HOME" }, result.won)
                    assertEquals(bets.count { it.selection != "HOME" }, result.lost)
                    assertEquals(0, result.alreadySettled)
                    assertTrue(fixture.settlementComplete)
                    assertResolvedOnce(ledger, wallet, fixture, bets)
                    assertEquals(expectedOrder, settleEntries(ledger).map { it.metadata.getValue("betId") }, "submission order is ledger order")
                }
            }
            assertJournalVerifies(directory, signer)
        }
    }

    @Test
    fun `void results refund every stake through the pipeline`() {
        val directory = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(directory, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model).apply { settlementWindow = 4 }
                val fixture = book.addFixture(Random(2))
                val bets = placeBets(book, wallet, fixture.id, 25)
                assertEquals(25, book.settle(fixture.id, "VOID").voided)
                assertResolvedOnce(ledger, wallet, fixture, bets)
                assertTrue(fixture.settlementComplete)
            }
        }
        assertJournalVerifies(directory, signer)
    }

    @Test
    fun `an unknown outcome stops submission, accounts for the window, and a retry reconciles`() {
        val directory = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        val completions = HeldCompletions()
        Ledger.open(config(directory, signer).copy(completionExecutor = completions.executor)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, directory).apply {
                    settlementWindow = 4
                    settlementTimeout = Duration.ofMillis(50)
                }
                val fixture = book.addFixture(Random(4))
                val bets = placeBets(book, wallet, fixture.id, 10)
                val before = ledger.head().size

                completions.delayed.set(true)
                val failure = assertFailsWith<OutcomeUnknownException> { book.settle(fixture.id, "HOME") }
                assertTrue(failure.key.endsWith(":settle"))
                assertEquals(3, failure.suppressed.size, "every other admitted future is accounted for")
                assertTrue(failure.suppressed.all { it is OutcomeUnknownException })
                awaitTrue("the four admitted settlements still commit") { ledger.head().size - before == 4L }
                Thread.sleep(200)
                assertEquals(4L, ledger.head().size - before, "no submission follows an unknown outcome")
                assertFalse(fixture.settlementComplete, "an unknown outcome never completes the fixture")

                completions.release()
                book.settlementTimeout = Duration.ofSeconds(30)
                assertEquals(6, book.settle(fixture.id, "HOME").let { it.won + it.lost }, "only the unresolved bets remain")
                assertTrue(fixture.settlementComplete)
                assertResolvedOnce(ledger, wallet, fixture, bets)
            }
        }
        assertJournalVerifies(directory, signer)
    }

    @Test
    fun `back-pressure refusal is reported after the admitted settlement and a retry finishes the fixture`() {
        val directory = tempDir()
        val delegate = Ed25519TreeHeadSigner.generate()
        val blockSigning = AtomicBoolean(false)
        val signing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val signer = object : TreeHeadSigner by delegate {
            override fun sign(message: ByteArray): ByteArray {
                if (blockSigning.get()) {
                    signing.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "test did not release signing" }
                }
                return delegate.sign(message)
            }
        }
        Ledger.open(config(directory, signer).copy(queueCapacity = 1, submitTimeout = Duration.ofMillis(50))).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model).apply { settlementWindow = 4 }
                val fixture = book.addFixture(Random(5))
                val bets = placeBets(book, wallet, fixture.id, 6)
                model.catchUp()
                val before = ledger.head().size

                blockSigning.set(true)
                val checkpoint = CompletableFuture.supplyAsync { ledger.checkpoint() }
                assertTrue(signing.await(5, TimeUnit.SECONDS), "the writer must be held before settling")
                val settlement = CompletableFuture.supplyAsync { book.settle(fixture.id, "HOME") }
                awaitTrue("the fixture result must be published") { fixture.result == "HOME" }
                Thread.sleep(300) // the second submission has now timed out on the full queue
                assertFalse(settlement.isDone, "the admitted settlement is still awaited")
                release.countDown()
                checkpoint.get(5, TimeUnit.SECONDS)
                val failure = assertFailsWith<java.util.concurrent.ExecutionException> { settlement.get(10, TimeUnit.SECONDS) }
                assertTrue(failure.cause is OverloadedException, failure.cause.toString())
                assertFalse(fixture.settlementComplete)
                assertEquals(1L, ledger.head().size - before, "only the admitted settlement committed")

                blockSigning.set(false)
                val retry = book.settle(fixture.id, "HOME")
                assertEquals(5, retry.won + retry.lost)
                assertTrue(fixture.settlementComplete)
                assertResolvedOnce(ledger, wallet, fixture, bets)
            }
        }
        assertJournalVerifies(directory, delegate)
    }

    @Test
    fun `a failure after some submissions were admitted never completes the fixture and recovery retry pays once`() {
        val directory = tempDir()
        val delegate = Ed25519TreeHeadSigner.generate()
        val failSigning = AtomicBoolean(false)
        val signer = object : TreeHeadSigner by delegate {
            override fun sign(message: ByteArray): ByteArray {
                if (failSigning.get()) throw IOException("simulated checkpoint device failure")
                return delegate.sign(message)
            }
        }
        val count = 60
        var bets = emptyList<Placed>()
        var committedBeforeFailure = 0L
        lateinit var fixtureId: String
        Ledger.open(config(directory, signer, checkpointEvery = 40)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, directory).apply { settlementWindow = 8 }
                val fixture = book.addFixture(Random(6))
                fixtureId = fixture.id
                bets = placeBets(book, wallet, fixture.id, count)
                ledger.checkpoint()
                val before = ledger.head().size

                failSigning.set(true)
                assertFailsWith<LedgerFailedException> { book.settle(fixture.id, "AWAY") }
                assertTrue(ledger.isFailed)
                assertFalse(fixture.settlementComplete, "a failed fixture is never marked complete")
                assertEquals("AWAY", fixture.result, "the published result is retained for the retry")
                committedBeforeFailure = ledger.head().size - before
                assertTrue(committedBeforeFailure in 1 until count, "partial settlement expected, got $committedBeforeFailure")
                assertEquals(committedBeforeFailure, settleEntries(ledger).size.toLong())
                assertTrue(wallet.settleLossAsync(bets.last().betId).isCompletedExceptionally, "a failed ledger refuses further submissions")
            }
        }
        // Fail-stop: reopening re-derives state from disk, then the same result is retried.
        failSigning.set(false)
        Ledger.open(config(directory, signer, checkpointEvery = 40)).use { ledger ->
            assertEquals(committedBeforeFailure, settleEntries(ledger).size.toLong(), "committed settlements survive recovery")
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model, directory).apply { settlementWindow = 8 }
                val fixture = book.fixture(fixtureId)
                assertEquals("AWAY", fixture.result)
                assertFalse(fixture.settlementComplete)
                val retry = book.settle(fixtureId, "AWAY")
                assertEquals(count - committedBeforeFailure.toInt(), retry.won + retry.lost + retry.voided)
                assertTrue(fixture.settlementComplete)
                assertResolvedOnce(ledger, wallet, fixture, bets)
            }
        }
        assertJournalVerifies(directory, delegate)
    }

    @Test
    fun `placements racing result closure either settle exactly once or never commit`() {
        repeat(5) { round ->
            val directory = tempDir()
            val signer = Ed25519TreeHeadSigner.generate()
            Ledger.open(config(directory, signer)).use { ledger ->
                val wallet = SportsbookWallet(ledger)
                ReadModel(ledger).use { model ->
                    val book = Book(wallet, model).apply { settlementWindow = 8 }
                    val fixture = book.addFixture(Random(round))
                    val early = placeBets(book, wallet, fixture.id, 20, "early")
                    val racers = 24
                    for (i in 0 until racers) wallet.deposit("late-player-$i", funds, "EUR", "late-funds-$i")
                    val outcomes = race(racers + 1) { i ->
                        if (i == 0) {
                            book.settle(fixture.id, "HOME")
                            null
                        } else {
                            val n = i - 1
                            val bet = Placed("late-$n", "late-player-$n", selections[n % 3])
                            try {
                                book.placeBet(bet.playerId, fixture.id, bet.selection, stake, bet.betId)
                                bet
                            } catch (e: IllegalArgumentException) {
                                null // closed before admission: the bet must not exist at all
                            }
                        }
                    }
                    outcomes.forEach { it.getOrThrow() }
                    val accepted = outcomes.drop(1).mapNotNull { it.getOrThrow() }
                    for (i in 0 until racers) {
                        val exists = ledger.findByIdempotencyKey("bet:late-$i:place") != null
                        assertEquals(accepted.any { it.betId == "late-$i" }, exists, "late-$i")
                    }
                    assertTrue(fixture.settlementComplete, "placements registered before closure are settled by it")
                    model.catchUp()
                    assertTrue(model.openBetsOn(fixture.id).isEmpty())
                    assertResolvedOnce(ledger, wallet, fixture, early + accepted)
                }
            }
            assertJournalVerifies(directory, signer)
        }
    }

    @Test
    fun `cash-outs racing pipelined settlement resolve each bet once`() {
        repeat(5) { round ->
            val directory = tempDir()
            val signer = Ed25519TreeHeadSigner.generate()
            Ledger.open(config(directory, signer)).use { ledger ->
                val wallet = SportsbookWallet(ledger)
                ReadModel(ledger).use { model ->
                    val book = Book(wallet, model).apply { settlementWindow = 8 }
                    val fixture = book.addFixture(Random(round))
                    val count = 48
                    val bets = placeBets(book, wallet, fixture.id, count)
                    val outcomes = race(count + 1) { i ->
                        if (i == 0) book.settle(fixture.id, "DRAW") else {
                            try { book.cashOut(bets[i - 1].betId); "cashed" } catch (e: IllegalArgumentException) { "closed" }
                        }
                    }
                    outcomes.forEachIndexed { i, r ->
                        val e = r.exceptionOrNull()
                        assertTrue(e == null || (i > 0 && e is IdempotencyConflictException), "caller $i: $e")
                    }
                    val settlement = outcomes[0].getOrThrow() as Book.Settlement
                    val cashedOut = settleEntries(ledger).count { it.kind == "BET_CASHED_OUT" }
                    assertEquals(count, settleEntries(ledger).size)
                    assertTrue(settlement.won + settlement.lost + settlement.alreadySettled <= count)
                    assertTrue(settlement.alreadySettled <= cashedOut)
                    assertTrue(fixture.settlementComplete)
                    assertResolvedOnce(ledger, wallet, fixture, bets, allowCashOut = true)
                }
            }
            assertJournalVerifies(directory, signer)
        }
    }

    @Test
    fun `duplicate settlement callers and conflicting results settle each bet once`() {
        val directory = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(directory, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ReadModel(ledger).use { model ->
                val book = Book(wallet, model).apply { settlementWindow = 4 }
                val fixture = book.addFixture(Random(8))
                val bets = placeBets(book, wallet, fixture.id, 30)
                val outcomes = race(6) { i -> book.settle(fixture.id, if (i == 5) "AWAY" else "HOME") }
                val winner = fixture.result!!
                outcomes.forEachIndexed { i, r ->
                    val sought = if (i == 5) "AWAY" else "HOME"
                    if (sought == winner) assertTrue(r.isSuccess, "caller $i: ${r.exceptionOrNull()}")
                    else assertTrue(r.exceptionOrNull() is IllegalArgumentException, "caller $i: ${r.exceptionOrNull()}")
                }
                assertTrue(fixture.settlementComplete)
                assertResolvedOnce(ledger, wallet, fixture, bets)
                val size = ledger.head().size
                val again = book.settle(fixture.id, winner)
                assertEquals(0, again.won + again.lost + again.voided, "a committed retry moves no money")
                assertEquals(size, ledger.head().size)
            }
        }
        assertJournalVerifies(directory, signer)
    }

    @Test
    fun `window must be positive`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            ReadModel(ledger).use { model ->
                val book = Book(SportsbookWallet(ledger), model)
                assertFailsWith<IllegalArgumentException> { book.settlementWindow = 0 }
                assertEquals(Book.DEFAULT_SETTLEMENT_WINDOW, book.settlementWindow)
            }
        }
    }
}
