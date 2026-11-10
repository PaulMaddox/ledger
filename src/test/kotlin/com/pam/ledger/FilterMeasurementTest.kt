package com.pam.ledger

import java.nio.file.Path
import java.util.concurrent.atomic.LongAdder
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in, reproducible local measurements, not storage-independent performance promises. */
@Tag("soak")
class FilterMeasurementTest {
    @TempDir lateinit var directory: Path

    private fun heap(): Long {
        System.gc()
        Thread.sleep(200)
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    }

    @Test
    fun `measure index heap and absent lookups`() {
        val counts = System.getProperty("ledger.measure.counts", "10000000,50000000").split(",").map(String::toInt)
        val budget = java.lang.Long.getLong("ledger.measure.budget", 4L * 1024 * 1024)
        DerivedStorage(directory.resolve("derived"), 256, budget) { throw it }.use { storage ->
            HashIndex(storage, "measure", 65_536, 12).use { index ->
                index.mayWait = true
                var previous = 0
                var firstHeap = 0L
                for (count in counts) {
                    val start = System.nanoTime()
                    for (i in previous until count) index.put(KeyIndex.hashOf("key-$i"), i.toLong())
                    Thread.sleep(500)
                    val used = heap()
                    if (firstHeap == 0L) firstHeap = used
                    assertTrue(storage.filters.usedBytes <= budget)
                    // Fixed tables/backlog and GC noise may vary, but history must not
                    // add a growing bit array between the 10M and 50M checkpoints.
                    assertTrue(used <= firstHeap + 32L * 1024 * 1024, "heap grew from $firstHeap to $used")
                    val reads = LongAdder()
                    val searches = LongAdder()
                    storage.onPageRead = { _, _ -> reads.increment() }
                    index.onRunSearch = { searches.increment() }
                    val latencies = LongArray(10_000) {
                        val begin = System.nanoTime()
                        val hash = KeyIndex.hashOf("absent-$it")
                        index.prefetch(hash) { error("absent key yielded a candidate") }
                        System.nanoTime() - begin
                    }.sorted()
                    storage.onPageRead = null
                    index.onRunSearch = null
                    println("MEASURE index keys=$count budget=$budget residentBytes=${storage.filters.usedBytes} runs=${index.runCount} heap=$used buildSeconds=${(System.nanoTime() - start) / 1e9} readsPerKey=${reads.sum() / 10000.0} falseSearchesPerKey=${searches.sum() / 10000.0} p50ns=${latencies[5000]} p99ns=${latencies[9900]}")
                    previous = count
                }
            }
        }
    }

    @Test
    fun `measure ledger throughput heap and restart`() {
        val count = Integer.getInteger("ledger.measure.entries", 4_200_000)
        val budget = java.lang.Long.getLong("ledger.measure.budget", 4L * 1024 * 1024)
        val config = TestSupport.config(directory, Ed25519TreeHeadSigner.generate()).copy(filterMemoryBytes = budget)
        val postings = listOf(Posting(AccountId("house:a"), "EUR", -1), Posting(AccountId("house:b"), "EUR", 1))
        val start = System.nanoTime()
        Ledger.open(config).use { ledger ->
            var n = 0
            val writerReads = LongAdder()
            val callerReads = LongAdder()
            ledger.derived.onPageRead = { _, _ ->
                if (Thread.currentThread().name.startsWith("pam-ledger-writer")) writerReads.increment()
                else if (!Thread.currentThread().name.startsWith("ledger-index")) callerReads.increment()
            }
            val latencies = ArrayList<Long>()
            while (n < count) {
                val batch = (n until minOf(n + 1024, count)).map {
                    val begin = System.nanoTime()
                    ledger.submit(TransactionRequest("measure-$it", "MEASURE", postings)).also {
                        if (n >= count - 100_000) latencies.add(System.nanoTime() - begin)
                    }
                }
                batch.forEach { it.join() }
                n += batch.size
            }
            latencies.sort()
            assertEquals(0, writerReads.sum())
            println("MEASURE ledger entries=$count budget=$budget heap=${heap()} entriesPerSecond=${count / ((System.nanoTime() - start) / 1e9)} callerReadsPerKey=${callerReads.sum() / count.toDouble()} writerReads=${writerReads.sum()} submitP50ns=${latencies[latencies.size / 2]} submitP99ns=${latencies[latencies.size * 99 / 100]}")
        }
        val restart = System.nanoTime()
        Ledger.open(config).use {
            println("MEASURE restart entries=${it.head().size} seconds=${(System.nanoTime() - restart) / 1e9} heap=${heap()}")
        }
    }
}
