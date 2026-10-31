package com.pam.ledger.web

import com.pam.ledger.Ed25519TreeHeadSigner
import com.pam.ledger.IdempotencyConflictException
import com.pam.ledger.InsufficientFundsException
import com.pam.ledger.Journal
import com.pam.ledger.Ledger
import com.pam.ledger.LedgerConfig
import com.pam.ledger.LedgerFailedException
import com.pam.ledger.SportsbookWallet
import com.pam.ledger.TreeHeadSigner
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Bounded failure experiments on disposable ledgers or signed copies, never the live journal. */
class ChaosLab(private val audit: Audit) {
    private val lock = ReentrantLock()
    private val history = ArrayDeque<Map<String, Any?>>()
    @Volatile private var running = false

    companion object {
        val SCENARIOS = linkedMapOf(
            "torn-write" to ("Torn journal write" to "Append an incomplete frame to a signed copy; recover its durable prefix."),
            "checksum" to ("Storage corruption" to "Flip a journal payload byte; expect CRC verification to reject it."),
            "rollback" to ("Storage rollback" to "Remove five signed entries from a copy; expect checkpoint verification to fail."),
            "retries" to ("Retry storm" to "Send 16 concurrent duplicate deposits; expect exactly one credit."),
            "double-spend" to ("Multi-device double spend" to "Race 16 full-balance bets; expect one acceptance and 15 refusals."),
            "settlement" to ("Conflicting settlement" to "Race win and loss notifications; expect one durable outcome."),
            "signer-outage" to ("Signing service outage" to "Make the sandbox signer throw an I/O error; expect fail-stop until reopen."),
        )
    }

    fun view(): Map<String, Any?> = mapOf(
        "running" to running,
        "scenarios" to SCENARIOS.map { (id, text) -> mapOf("id" to id, "name" to text.first, "description" to text.second) },
        "history" to synchronized(history) { history.toList().asReversed() },
    )

    /** Execute one experiment at a time; report the measured outcome, not a simulated verdict. */
    fun run(scenario: String): Map<String, Any?> {
        require(scenario in SCENARIOS) { "unknown chaos scenario" }
        require(lock.tryLock()) { "another chaos experiment is running" }
        running = true
        val started = System.nanoTime()
        try {
            val (passed, evidence) = when (scenario) {
                "rollback" -> audit.tamper("truncate").let { !it.report.ok to it.report.problems.joinToString("; ") }
                "torn-write", "checksum" -> audit.withSnapshot { dir, anchor ->
                    val path = dir.resolve(Ledger.ENTRIES_FILE)
                    if (scenario == "torn-write") {
                        Files.write(path, byteArrayOf(0, 0, 1), APPEND)
                        val (journal, scan) = Journal.openForAppend(path) {}
                        journal.close()
                        val recovered = Journal.scanReadOnly(path) {}
                        val report = audit.verifySnapshot(dir)
                        (scan.tornTailBytes == 3L && recovered.records.toLong() == anchor.head.treeSize &&
                            recovered.tornTailBytes == 0L && report.ok) to
                            "Quarantined ${scan.tornTailBytes} bytes; recovered ${recovered.records} signed-prefix records."
                    } else {
                        FileChannel.open(path, READ, WRITE).use { channel ->
                            val byte = ByteBuffer.allocate(1)
                            check(channel.read(byte, Journal.HEADER.toLong()) == 1)
                            byte.flip()
                            val altered = ByteBuffer.wrap(byteArrayOf((byte.get().toInt() xor 1).toByte()))
                            check(channel.write(altered, Journal.HEADER.toLong()) == 1)
                            channel.force(true)
                        }
                        try {
                            Journal.scanReadOnly(path) {}
                            false to "Corrupted storage was unexpectedly accepted."
                        } catch (e: com.pam.ledger.TamperDetectedException) {
                            true to (e.message ?: "CRC corruption detected.")
                        }
                    }
                }
                else -> sandbox(scenario)
            }
            val result = mapOf("scenario" to scenario, "name" to SCENARIOS.getValue(scenario).first,
                "passed" to passed, "evidence" to evidence, "t" to System.currentTimeMillis(),
                "millis" to (System.nanoTime() - started) / 1_000_000,
                "scope" to "isolated sandbox; live balances unchanged")
            synchronized(history) {
                history.addLast(result)
                if (history.size > 30) history.removeFirst()
            }
            return result
        } finally {
            running = false
            lock.unlock()
        }
    }

    private fun sandbox(scenario: String): Pair<Boolean, String> {
        val dir = Files.createTempDirectory("pam-ledger-chaos")
        val signer = Ed25519TreeHeadSigner.generate()
        val unavailable = object : TreeHeadSigner {
            override val publicKey = signer.publicKey
            override val keyId = signer.keyId
            override fun sign(message: ByteArray): ByteArray = throw IOException("chaos: signing service unavailable")
        }
        val config = LedgerConfig(directory = dir, ledgerId = "chaos-sandbox",
            signer = if (scenario == "signer-outage") unavailable else signer)
        try {
            val result = Ledger.open(config).use { ledger ->
                val wallet = SportsbookWallet(ledger)
                wallet.deposit("test", 10_000, "EUR", "seed")
                when (scenario) {
                    "retries" -> {
                        val receipts = race(16) { wallet.deposit("test", 100, "EUR", "duplicate") }
                        val size = ledger.head().size
                        (receipts.map { it.seq }.distinct().size == 1 && size == 2L &&
                            wallet.cashBalance("test", "EUR") == 10_100L) to
                            "${receipts.size} replies, ${receipts.count { it.replayed }} replays, $size entries, 10100 minor units."
                    }
                    "double-spend" -> {
                        val accepted = race(16) { i ->
                            try {
                                wallet.placeBet("test", "device-$i", 10_000, "EUR")
                                true
                            } catch (e: InsufficientFundsException) { false }
                        }.count { it }
                        (accepted == 1 && ledger.head().size == 2L && wallet.cashBalance("test", "EUR") == 0L &&
                            wallet.openBetsBalance("test", "EUR") == 10_000L) to
                            "$accepted accepted, ${16 - accepted} refused; cash ${wallet.cashBalance("test", "EUR")}, escrow ${wallet.openBetsBalance("test", "EUR")} minor units."
                    }
                    "settlement" -> {
                        wallet.placeBet("test", "bet", 100, "EUR")
                        val accepted = race(2) { i ->
                            try {
                                if (i == 0) wallet.settleWin("bet", 200) else wallet.settleLoss("bet")
                                true
                            } catch (e: IdempotencyConflictException) { false }
                        }.count { it }
                        (accepted == 1 && ledger.head().size == 3L && wallet.openBetsBalance("test", "EUR") == 0L) to
                            "$accepted settlement accepted, ${2 - accepted} conflicting outcome refused; escrow released once."
                    }
                    else -> {
                        var checkpointFailed = false
                        var writeRefused = false
                        try { ledger.checkpoint() } catch (e: LedgerFailedException) { checkpointFailed = true }
                        try { wallet.deposit("test", 100, "EUR", "after-outage") }
                        catch (e: LedgerFailedException) { writeRefused = true }
                        (checkpointFailed && writeRefused && ledger.head().size == 1L) to
                            "Checkpoint failed: $checkpointFailed; subsequent write refused: $writeRefused."
                    }
                }
            }
            // Recovery verifies every committed record again, using the same rulebook.
            Ledger.open(config.copy(signer = signer)).use { recovered ->
                recovered.checkpoint()
                val report = com.pam.ledger.LedgerVerifier.verify(dir, config.ledgerId, listOf(signer.publicKey))
                check(report.ok) { "sandbox recovery audit failed: ${report.problems}" }
            }
            return result.first to "${result.second} Sandbox reopened and signed successfully."
        } finally {
            check(dir.toFile().deleteRecursively()) { "could not remove chaos sandbox $dir" }
        }
    }

    private fun <T> race(count: Int, action: (Int) -> T): List<T> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val ready = CountDownLatch(count)
            val go = CountDownLatch(1)
            val futures = (0 until count).map { i ->
                executor.submit(Callable {
                    ready.countDown()
                    check(go.await(5, TimeUnit.SECONDS)) { "chaos race start timed out" }
                    action(i)
                })
            }
            check(ready.await(5, TimeUnit.SECONDS)) { "chaos workers did not become ready" }
            go.countDown()
            futures.map { it.get(15, TimeUnit.SECONDS) }
        }
}
