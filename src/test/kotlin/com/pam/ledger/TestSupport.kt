package com.pam.ledger

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Shared helpers for the test suite. */
object TestSupport {
    const val LEDGER_ID = "test-ledger"

    fun tempDir(): Path = Files.createTempDirectory("pam-ledger-test")

    fun config(dir: Path, signer: TreeHeadSigner, checkpointEvery: Long = 1_000) =
        LedgerConfig(directory = dir, ledgerId = LEDGER_ID, signer = signer, checkpointEvery = checkpointEvery)

    /**
     * Run [threads] copies of [body] that all start at the SAME instant (via a
     * latch), which maximises contention. Returns each thread's outcome.
     */
    fun <T> race(threads: Int, body: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        try {
            val futures: List<Future<Result<T>>> = (0 until threads).map { i ->
                pool.submit<Result<T>> {
                    ready.countDown()
                    go.await()
                    runCatching { body(i) }
                }
            }
            ready.await()
            go.countDown()
            return futures.map { it.get(2, TimeUnit.MINUTES) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Re-write a journal from a list of raw records, with valid frames/CRCs (simulates a careful attacker). */
    fun writeJournal(path: Path, records: List<ByteArray>) {
        Files.deleteIfExists(path)
        val (j, _) = Journal.openForAppend(path) {}
        j.use { if (records.isNotEmpty()) it.appendBatch(records) }
    }

    fun readRecords(path: Path): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        Journal.scanReadOnly(path) { out += it }
        return out
    }
}
