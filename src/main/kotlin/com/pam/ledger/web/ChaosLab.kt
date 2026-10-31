package com.pam.ledger.web

import com.pam.ledger.DirectoryLock
import com.pam.ledger.IdempotencyConflictException
import com.pam.ledger.InsufficientFundsException
import com.pam.ledger.Journal
import com.pam.ledger.Ledger
import com.pam.ledger.LedgerConfig
import com.pam.ledger.LedgerFailedException
import com.pam.ledger.LedgerVerifier
import com.pam.ledger.SportsbookWallet
import com.pam.ledger.TamperDetectedException
import com.pam.ledger.TreeHeadSigner
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Demo-only failures against the main store. The app owns exclusive service-lifetime coordination. */
class ChaosLab {
    private val history = ArrayDeque<Map<String, Any?>>()

    companion object {
        val LIVE_SCENARIOS = setOf("retries", "double-spend", "settlement")
        val SCENARIOS = linkedMapOf(
            "torn-write" to ("Torn journal write" to "Append an incomplete frame to the main journal; drain services, then reopen and recover the durable prefix."),
            "checksum" to ("Storage corruption" to "Flip a main journal byte; drain services, then expect recovery to refuse the damaged store."),
            "rollback" to ("Storage rollback" to "Remove five signed entries from the main journal; drain services, then expect recovery to refuse it."),
            "retries" to ("Retry storm" to "Send 16 concurrent deposits for one player with one key; expect exactly one credit while the simulator keeps running."),
            "double-spend" to ("Multi-device double spend" to "Race 16 full-balance bets for one player; expect one acceptance and 15 refusals while the simulator keeps running."),
            "settlement" to ("Conflicting settlement" to "Race win and loss for one bet; expect one durable outcome while the simulator keeps running."),
            "signer-outage" to ("Signing service outage" to "Fail a ledger instance's signer; observe fail-stop, then verify the main store recovers."),
            "edit" to ("Edit in place" to "Inflate a main-store deposit tenfold without updating hashes."),
            "rewrite" to ("Rewrite history" to "Inflate a main-store deposit and recompute the chain; expect signed checkpoints to reject it."),
            "truncate" to ("Roll back" to "Delete the newest five main-store entries covered by a signed checkpoint."),
        )

        fun validate(scenario: String) {
            require(scenario in SCENARIOS) { "unknown chaos scenario" }
        }

        fun isLiveScenario(scenario: String) = scenario in LIVE_SCENARIOS
    }

    fun view(available: Boolean, reason: String?): Map<String, Any?> = mapOf(
        "available" to available, "reason" to reason,
        "scenarios" to SCENARIOS.filterKeys { it !in setOf("edit", "rewrite", "truncate") }
            .map { (id, text) -> mapOf("id" to id, "name" to text.first, "description" to text.second,
                "live" to isLiveScenario(id)) },
        "history" to synchronized(history) { history.toList().asReversed() },
    )

    fun clearHistory() = synchronized(history) { history.clear() }

    /** Caller has stopped and drained every service before any main-store file mutation. */
    fun run(scenario: String, directory: Path, signer: TreeHeadSigner): Map<String, Any?> {
        require(scenario in SCENARIOS) { "unknown chaos scenario" }
        val started = System.nanoTime()
        val config = LedgerConfig(directory = directory, ledgerId = LedgerWebApp.LEDGER_ID, signer = signer)
        val baseline = LedgerVerifier.verify(directory, config.ledgerId, listOf(signer.publicKey))
        val baselineBytes = Files.size(directory.resolve(Ledger.ENTRIES_FILE))
        check(baseline.ok) { "main store must verify before an experiment: ${baseline.problems}" }
        val observed: Pair<Boolean, String> = when (scenario) {
            "checksum", "torn-write", "rollback", "edit", "rewrite", "truncate" ->
                DirectoryLock.acquire(directory.resolve(Ledger.LOCK_FILE)).use {
                    val path = directory.resolve(Ledger.ENTRIES_FILE)
                    when (scenario) {
                        "torn-write" -> {
                            FileChannel.open(path, APPEND).use { channel ->
                                val bytes = ByteBuffer.wrap(byteArrayOf(0, 0, 1))
                                while (bytes.hasRemaining()) channel.write(bytes)
                                channel.force(true)
                            }
                            true to "Appended an incomplete 3-byte frame to the main journal."
                        }
                        "checksum" -> {
                            FileChannel.open(path, READ, WRITE).use { channel ->
                                val byte = ByteBuffer.allocate(1)
                                check(channel.read(byte, Journal.HEADER.toLong()) == 1)
                                byte.flip()
                                val altered = ByteBuffer.wrap(byteArrayOf((byte.get().toInt() xor 1).toByte()))
                                check(channel.write(altered, Journal.HEADER.toLong()) == 1)
                                channel.force(true)
                            }
                            true to "Flipped a payload byte in the first main-journal record."
                        }
                        else -> true to Audit.tamperStorage(directory, config.ledgerId,
                            if (scenario == "rollback") "truncate" else scenario)
                    }
                }
            else -> moneyExperiment(scenario, config)
        }

        var available = false
        var passed = observed.first
        val recovery = if (scenario == "signer-outage") {
            try {
                Ledger.open(config).use { it.checkpoint() }
                check(LedgerVerifier.verify(directory, config.ledgerId, listOf(signer.publicKey)).ok) {
                    "main-store verification failed after signer outage"
                }
                available = true
                "The injected signer failed and its ledger refused a later write; the main store reopened and verified."
            } catch (e: TamperDetectedException) {
                "Main-store recovery refused the ledger: ${e.message}. Files retained; use Administrator reset."
            }
        } else {
            try {
                Ledger.open(config).use { recovered ->
                    recovered.checkpoint()
                    val report = LedgerVerifier.verify(directory, config.ledgerId, listOf(signer.publicKey))
                    check(report.ok) { "main-store recovery audit failed: ${report.problems}" }
                    if (scenario == "torn-write") {
                        check(report.entries == baseline.entries) { "torn-write recovery lost committed entries" }
                        check(Files.size(directory.resolve(Ledger.ENTRIES_FILE)) == baselineBytes) {
                            "recovered journal still has a torn tail"
                        }
                    }
                }
                available = true
                if (scenario in setOf("checksum", "rollback", "edit", "rewrite", "truncate")) passed = false
                "Main store reopened and independently verified."
            } catch (e: TamperDetectedException) {
                passed = passed && scenario in setOf("checksum", "rollback", "edit", "rewrite", "truncate")
                "Recovery refused the main store: ${e.message}. Files retained; use Administrator reset."
            }
        }
        return remember(mapOf("scenario" to scenario, "name" to SCENARIOS.getValue(scenario).first,
            "passed" to passed, "evidence" to "${observed.second} $recovery",
            "t" to System.currentTimeMillis(), "millis" to (System.nanoTime() - started) / 1_000_000,
            "available" to available, "scope" to "MAIN DATA STORE",
            "problems" to if (available) emptyList<String>() else listOf(recovery)))
    }

    /** Run business-operation races on the live ledger without interrupting simulator traffic. */
    @Synchronized
    fun runLive(scenario: String, ledger: Ledger, wallet: SportsbookWallet, book: Book): Map<String, Any?> {
        require(isLiveScenario(scenario)) { "scenario is not a live transaction experiment" }
        val started = System.nanoTime()
        val (passed, evidence) = liveMoneyExperiment(scenario, wallet, book)
        val headSize = ledger.head().size
        return remember(mapOf(
            "scenario" to scenario,
            "name" to SCENARIOS.getValue(scenario).first,
            "passed" to passed,
            "evidence" to "$evidence Ledger head: $headSize entries.",
            "t" to System.currentTimeMillis(),
            "millis" to (System.nanoTime() - started) / 1_000_000,
            "available" to true,
            "scope" to "MAIN DATA STORE · LIVE",
            "problems" to emptyList<String>(),
        ))
    }

    private fun remember(result: Map<String, Any?>): Map<String, Any?> {
        synchronized(history) {
            history.addLast(result)
            if (history.size > 30) history.removeFirst()
        }
        return result
    }

    private fun liveMoneyExperiment(
        scenario: String,
        wallet: SportsbookWallet,
        book: Book,
    ): Pair<Boolean, String> {
        val player = "chaos-${UUID.randomUUID()}"
        wallet.deposit(player, 10_000, "EUR", "$player-seed")
        return when (scenario) {
            "retries" -> {
                val receipts = race(16) { wallet.deposit(player, 100, "EUR", "$player-duplicate") }
                (receipts.map { it.seq }.distinct().size == 1 &&
                    receipts.count { !it.replayed } == 1 &&
                    wallet.cashBalance(player, "EUR") == 10_100L) to
                    "${receipts.size} replies, ${receipts.count { it.replayed }} replays; $player credited once, cash 10100 minor units."
            }
            "double-spend" -> {
                val fixture = book.addFixture()
                val accepted = race(16) { i ->
                    try {
                        book.placeBet(player, fixture.id, "HOME", 10_000, "$player-device-$i")
                        true
                    } catch (e: InsufficientFundsException) { false }
                }.count { it }
                (accepted == 1 && wallet.cashBalance(player, "EUR") == 0L &&
                    wallet.openBetsBalance(player, "EUR") == 10_000L) to
                    "$accepted accepted, ${16 - accepted} refused for $player; cash 0, escrow 10000 minor units."
            }
            else -> {
                val fixture = book.addFixture()
                val bet = "$player-bet"
                book.placeBet(player, fixture.id, "HOME", 100, bet)
                val accepted = race(2) { i ->
                    try {
                        if (i == 0) wallet.settleWin(bet, 200) else wallet.settleLoss(bet)
                        true
                    } catch (e: IdempotencyConflictException) { false }
                }.count { it }
                (accepted == 1 && wallet.openBetsBalance(player, "EUR") == 0L) to
                    "$accepted settlement accepted, ${2 - accepted} conflicting outcome refused for $player; escrow released once."
            }
        }
    }

    private fun moneyExperiment(scenario: String, config: LedgerConfig): Pair<Boolean, String> {
        val unavailable = object : TreeHeadSigner {
            override val publicKey = config.signer.publicKey
            override val keyId = config.signer.keyId
            override fun sign(message: ByteArray): ByteArray = throw IOException("chaos: main signing service unavailable")
        }
        return Ledger.open(if (scenario == "signer-outage") config.copy(signer = unavailable) else config).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            val before = ledger.head().size
            if (scenario == "signer-outage") {
                var checkpointFailed = false
                var writeRefused = false
                try { ledger.checkpoint() } catch (e: LedgerFailedException) { checkpointFailed = true }
                try { wallet.deposit("chaos-outage", 100, "EUR", "after-outage-${UUID.randomUUID()}") }
                catch (e: LedgerFailedException) { writeRefused = true }
                return@use (checkpointFailed && writeRefused && ledger.head().size == before) to
                    "Main checkpoint failed: $checkpointFailed; subsequent write refused: $writeRefused; no new entries committed."
            }
            val player = "chaos-${UUID.randomUUID()}"
            wallet.deposit(player, 10_000, "EUR", "$player-seed")
            when (scenario) {
                "retries" -> {
                    val receipts = race(16) { wallet.deposit(player, 100, "EUR", "$player-duplicate") }
                    (receipts.map { it.seq }.distinct().size == 1 && ledger.head().size == before + 2 &&
                        wallet.cashBalance(player, "EUR") == 10_100L) to
                        "${receipts.size} replies, ${receipts.count { it.replayed }} replays; $player credited once, cash 10100 minor units."
                }
                else -> ReadModel(ledger).use { readModel ->
                    val book = Book(wallet, readModel, config.directory)
                    val fixture = book.addFixture()
                    if (scenario == "double-spend") {
                        val accepted = race(16) { i ->
                            try {
                                book.placeBet(player, fixture.id, "HOME", 10_000, "$player-device-$i")
                                true
                            } catch (e: InsufficientFundsException) { false }
                        }.count { it }
                        (accepted == 1 && ledger.head().size == before + 2 && wallet.cashBalance(player, "EUR") == 0L &&
                            wallet.openBetsBalance(player, "EUR") == 10_000L) to
                            "$accepted accepted, ${16 - accepted} refused on the main store; $player cash 0, escrow 10000 minor units."
                    } else {
                        val bet = "$player-bet"
                        book.placeBet(player, fixture.id, "HOME", 100, bet)
                        val accepted = race(2) { i ->
                            try {
                                if (i == 0) wallet.settleWin(bet, 200) else wallet.settleLoss(bet)
                                true
                            } catch (e: IdempotencyConflictException) { false }
                        }.count { it }
                        (accepted == 1 && ledger.head().size == before + 3 && wallet.openBetsBalance(player, "EUR") == 0L) to
                            "$accepted main-store settlement accepted, ${2 - accepted} conflicting outcome refused; escrow released once."
                    }
                }
            }
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
