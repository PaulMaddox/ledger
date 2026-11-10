package com.pam.ledger

import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RunFilterTest {
    private fun storage(budget: Long = PAGE_SIZE.toLong(), failure: AtomicReference<Throwable?> = AtomicReference()) =
        DerivedStorage(tempDir().resolve("derived"), 1, budget) { failure.compareAndSet(null, it) }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(2)
        }
    }

    @Test
    fun `blocked filters have no false negatives and a measured per-run false positive rate`() {
        storage(0).use { storage ->
            val random = Random(76)
            val hashes = List(100_000) { random.nextLong() }.sortedWith(java.lang.Long::compareUnsigned)
            val writer = RunFilter.Writer(storage.create("test-filter"), hashes.size.toLong(), 12)
            hashes.forEach(writer::add)
            val filter = writer.finish()
            hashes.forEach { assertTrue(filter.mightContain(it)) }
            var falsePositives = 0
            repeat(100_000) { if (filter.mightContain(random.nextLong())) falsePositives++ }
            assertTrue(falsePositives in 200..2_000, "12-bit blocked filter: $falsePositives false positives in 100,000")
            println("FILTER falsePositiveRate=${falsePositives / 100000.0}")
        }
    }

    @Test
    fun `block addressing remains monotonic beyond the old seven-billion-key ceiling`() {
        val blocks = RunFilter.blocksFor(50_000_000_000, 12)
        assertTrue(blocks > Int.MAX_VALUE / 64)
        val hashes = listOf(0L, 1L, Long.MAX_VALUE, Long.MIN_VALUE, -2L, -1L)
        val positions = hashes.map { Math.unsignedMultiplyHigh(it, blocks) }
        assertEquals(positions.sorted(), positions)
        assertEquals(blocks - 1, positions.last())
        assertEquals(1L, RunFilter.blocksFor(0, 12))
        assertFailsWith<ArithmeticException> { RunFilter.blocksFor(Long.MAX_VALUE, 64) }
    }

    @Test
    fun `filters and collision candidates remain equivalent across shared budget boundaries`() {
        for (budget in listOf(0L, PAGE_SIZE.toLong(), 64L * PAGE_SIZE)) {
            val failures = AtomicReference<Throwable?>()
            storage(budget, failures).use { storage ->
                HashIndex(storage, "keys", 512, 12).use { keys ->
                    HashIndex(storage, "bets", 512, 12, retainedGenerations = 0).use { bets ->
                        keys.mayWait = true
                        bets.mayWait = true
                        val random = Random(76)
                        val expected = HashMap<Long, MutableSet<Long>>()
                        repeat(12_000) { i ->
                            val hash = if (i % 7 == 0) 1L + i % 13 else random.nextLong().let { if (it == 0L) 1 else it }
                            expected.getOrPut(hash) { HashSet() }.add(i.toLong())
                            keys.put(hash, i.toLong())
                            bets.put(hash, i.toLong())
                            assertTrue(storage.filters.usedBytes <= budget)
                        }
                        for (index in listOf(keys, bets)) {
                            fun check() {
                                for ((hash, seqs) in expected) {
                                    assertTrue(index.mightContain(hash))
                                    val found = HashSet<Long>()
                                    index.anyCandidate(hash) { found.add(it); false }
                                    assertEquals(seqs, found)
                                }
                            }
                            check()
                            index.finishRecovery()
                            assertEquals(1, index.runCount)
                            check()
                        }
                        assertTrue(storage.filters.usedBytes <= budget)
                        assertEquals(null, failures.get())
                    }
                }
                assertEquals(0L, storage.filters.usedBytes)
            }
        }
    }

    @Test
    fun `budget prefers smaller and then newer filters and is shared rather than per index`() {
        storage(2L * PAGE_SIZE).use { storage ->
            fun filter(keys: Long): RunFilter =
                RunFilter.Writer(storage.create("filter"), keys, 12).finish().also {
                    storage.filters.replace(emptyList(), it)
                }
            val large = filter(4096)
            assertTrue(large.resident)
            val first = filter(100)
            assertTrue(first.resident)
            assertFalse(large.resident)
            val second = filter(100)
            assertTrue(first.resident && second.resident)
            val third = filter(100)
            assertFalse(first.resident)
            assertTrue(second.resident && third.resident)
            assertEquals(2L * PAGE_SIZE, storage.filters.usedBytes)
            storage.filters.replace(listOf(first, second, third))
            assertTrue(large.resident)
        }
    }

    @Test
    fun `disk filter probes overlap on virtual threads`() {
        storage(0).use { storage ->
            HashIndex(storage, "parallel", 4096, 12).use { index ->
                index.mayWait = true
                repeat(3 * 4096) { index.put(KeyIndex.hashOf("parallel-$it"), it.toLong()) }
                await("three complete runs") { index.runCount == 3 }
                storage.create("evict").append(ByteArray(PAGE_SIZE))
                val arrived = CountDownLatch(2)
                val threads = ConcurrentHashMap.newKeySet<Thread>()
                storage.onPageRead = { file, _ ->
                    if (file.startsWith("parallel-filter")) {
                        threads.add(Thread.currentThread())
                        arrived.countDown()
                        assertTrue(arrived.await(10, TimeUnit.SECONDS), "disk probes must overlap")
                    }
                }
                index.mightContain(KeyIndex.hashOf("absent-parallel"))
                storage.onPageRead = null
                assertTrue(threads.size >= 2)
                assertTrue(threads.all { it.isVirtual })
            }
        }
    }

    @Test
    fun `writer generation coverage is exact and stale watermarks fall back safely`() {
        storage(0).use { storage ->
            HashIndex(storage, "watermark", 64, 12).use { index ->
                index.mayWait = true
                val hash = KeyIndex.hashOf("duplicate")
                val watermark = index.prefetch(hash) { error("not committed yet") }
                index.put(hash, 0)
                repeat(128) { index.put(KeyIndex.hashOf("recent-$it"), it + 1L) }
                index.finishRecovery()
                val reads = AtomicLong()
                storage.onPageRead = { _, _ -> reads.incrementAndGet() }
                assertTrue(index.anyCandidateAfter(hash, watermark) { it == 0L })
                assertFalse(index.anyCandidateAfter(KeyIndex.hashOf("new-key"), watermark) { true })
                assertEquals(0L, reads.get(), "recent generations are exact, even when disk filters are disabled in memory")
                storage.onPageRead = null
                repeat(64 * 12) { index.put(KeyIndex.hashOf("later-$it"), it + 129L) }
                index.finishRecovery()
                storage.create("evict").append(ByteArray(PAGE_SIZE))
                storage.onPageRead = { _, _ -> reads.incrementAndGet() }
                assertTrue(index.anyCandidateAfter(hash, watermark) { it == 0L })
                assertTrue(reads.get() > 0, "expired coverage must search disk instead of claiming absence")
            }
        }
    }

    @Test
    fun `merge compares full keys throughout an equal-hash group not just adjacent records`() {
        val keys = listOf("a", "b", "a")
        val failures = AtomicReference<Throwable?>()
        storage(0, failures).use { storage ->
            HashIndex(storage, "collision", 1, 12, sameKey = { a, b -> keys[a.toInt()] == keys[b.toInt()] }).use { index ->
                index.mayWait = true
                assertFailsWith<TamperDetectedException> {
                    keys.indices.forEach { index.put(1L, it.toLong()) }
                    index.finishRecovery()
                }
                assertTrue(failures.get() is TamperDetectedException)
            }
        }
    }

    @Test
    fun `recovery rejects a far apart duplicate before any ledger opens`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        var chain = Hashes.genesis(TestSupport.LEDGER_ID)
        val postings = listOf(Posting(AccountId("house:a"), "EUR", -1), Posting(AccountId("house:b"), "EUR", 1))
        val records = List(8193) { i ->
            val key = if (i == 8192) "key-0" else "key-$i"
            val sealed = SealedEntry.seal(LedgerEntry(i.toLong(), i.toLong(), UUID(0, i.toLong()), chain, key, "TEST", postings, sortedMapOf()))
            chain = sealed.chainHash
            sealed.journalRecord()
        }
        TestSupport.writeJournal(dir.resolve(Ledger.ENTRIES_FILE), records)
        val error = assertFailsWith<TamperDetectedException> {
            Ledger.open(config(dir, signer).copy(indexFlushSize = 64, filterMemoryBytes = 0)).close()
        }
        assertTrue(error.message!!.contains("duplicate idempotency key"), error.message)
        // A failed open released its lock; investigators may still audit the evidence.
        assertFalse(LedgerVerifier.verify(dir, TestSupport.LEDGER_ID, listOf(signer.publicKey)).ok)
    }

    @Test
    fun `tampering with an on-disk filter is authenticated and stops the ledger`() {
        val cfg = config(tempDir(), Ed25519TreeHeadSigner.generate()).copy(
            filterMemoryBytes = 0, indexFlushSize = 64, derivedCachePages = 1,
        )
        Ledger.open(cfg).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            repeat(128) { wallet.deposit("p", 1, "EUR", "tamper-$it") }
        }
        Ledger.open(cfg).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            val dir = cfg.directory.resolve(Ledger.DERIVED_DIRECTORY)
            // Open finished the full merge; no background task races these mutations.
            Files.list(dir).use { paths ->
                paths.filter { it.fileName.toString().startsWith("keys-filter") }.forEach { path ->
                    FileChannel.open(path, READ, WRITE).use { channel ->
                        val mac = ByteBuffer.allocate(1)
                        assertEquals(1, channel.read(mac, PAGE_SIZE.toLong()))
                        mac.flip()
                        mac.put(0, (mac.get(0).toInt() xor 1).toByte())
                        assertEquals(1, channel.write(mac, PAGE_SIZE.toLong()))
                    }
                }
            }
            ledger.derived.create("evict").append(ByteArray(PAGE_SIZE))
            assertFailsWith<TamperDetectedException> { wallet.deposit("p", 1, "EUR", "tamper-after") }
            assertTrue(ledger.isFailed)
            assertEquals(128L, ledger.head().size)
        }
    }

    @Test
    fun `latched maintenance failures are ledger failures on every index read path`() {
        val cause = IOException("unavailable maintenance reader")
        val failures = AtomicReference<Throwable?>()
        val allowFailure = CountDownLatch(1)
        storage(0, failures).use { storage ->
            HashIndex(storage, "failed", 2, 12, sameKey = { _, _ ->
                allowFailure.await()
                throw cause
            }).use { index ->
                try {
                    index.put(1L, 0)
                    index.put(1L, 1)
                } finally {
                    allowFailure.countDown()
                }
                await("maintenance failure") { failures.get() != null }
                assertSame(cause, failures.get())
                assertSame(cause, assertFailsWith<LedgerFailedException> { index.mightContain(1L) }.cause)
                assertSame(cause, assertFailsWith<LedgerFailedException> { index.anyCandidate(1L) { true } }.cause)
                assertSame(cause, assertFailsWith<LedgerFailedException> { index.prefetch(1L) { true } }.cause)
            }
        }
    }

    @Test
    fun `caller filter read failures are explicit retryable ledger errors and never commit`() {
        val cfg = config(tempDir(), Ed25519TreeHeadSigner.generate()).copy(
            filterMemoryBytes = 0, indexFlushSize = 64, derivedCachePages = 1,
        )
        Ledger.open(cfg).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            repeat(64) { wallet.deposit("p", 1, "EUR", "read-$it") }
        }
        Ledger.open(cfg).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ledger.derived.create("evict").append(ByteArray(PAGE_SIZE))
            ledger.derived.onPageRead = { file, _ ->
                if (file.startsWith("keys-filter")) throw IOException("unavailable test reader")
            }
            assertFailsWith<IndexUnreadableException> { wallet.deposit("p", 1, "EUR", "read-retry") }
            assertEquals(64L, ledger.head().size)
            assertFalse(ledger.isFailed)
            ledger.derived.onPageRead = null
            assertFalse(wallet.deposit("p", 1, "EUR", "read-retry").replayed)
            assertTrue(wallet.deposit("p", 1, "EUR", "read-retry").replayed)
        }
    }

    @Test
    fun `one hundred thousand new keys never read a derived page on the writer`() {
        val cfg = config(tempDir(), Ed25519TreeHeadSigner.generate()).copy(filterMemoryBytes = PAGE_SIZE.toLong())
        Ledger.open(cfg).use { ledger ->
            val writerReads = AtomicLong()
            val callerReads = AtomicLong()
            ledger.derived.onPageRead = { _, _ ->
                if (Thread.currentThread().name.startsWith("pam-ledger-writer")) writerReads.incrementAndGet()
                else if (!Thread.currentThread().name.startsWith("ledger-index")) callerReads.incrementAndGet()
            }
            val postings = listOf(Posting(AccountId("house:a"), "EUR", -1), Posting(AccountId("house:b"), "EUR", 1))
            for (batch in 0 until 100) {
                val futures = (0 until 1000).map { ledger.submit(TransactionRequest("new-${batch * 1000 + it}", "TEST", postings)) }
                futures.forEach { assertFalse(it.join().replayed) }
            }
            assertEquals(100_000L, ledger.head().size)
            assertEquals(0L, writerReads.get())
            assertTrue(callerReads.get() > 0, "the small budget exercised on-disk filters")
        }
    }

    @Test
    fun `concurrent duplicate submissions commit once across flush boundaries`() {
        val cfg = config(tempDir(), Ed25519TreeHeadSigner.generate()).copy(indexFlushSize = 64, filterMemoryBytes = 0)
        Ledger.open(cfg).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            repeat(12) { round ->
                repeat(65) { wallet.deposit("p", 1, "EUR", "filler-$round-$it") }
                val results = race(16) { wallet.deposit("p", 1, "EUR", "race-$round") }.map { it.getOrThrow() }
                assertEquals(1, results.count { !it.replayed })
                assertEquals(1, results.map { it.seq }.toSet().size)
            }
            assertEquals(12L * 66, ledger.head().size)
        }
    }
}
