package com.pam.ledger

import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import com.pam.ledger.web.ReadModel
import java.nio.ByteBuffer
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Indexes live in derived files instead of on the heap (issue #73, step 3). A file can be
 * altered by anything that reaches the disk, so these tests pin the three rules that make
 * the files safe to rely on: every page is authenticated, every page is written once, and
 * nothing is trusted across a restart. They also pin that the on-disk idempotency index
 * and its Bloom filter give exactly the answers the in-memory map gave.
 */
class DerivedIndexTest {
    private val pageSlot = PAGE_SIZE + 32L

    private fun storage(failures: AtomicReference<Throwable?> = AtomicReference(), cachePages: Int = 1, dir: Path = tempDir()) =
        DerivedStorage(dir.resolve("derived"), cachePages) { failures.compareAndSet(null, it) }

    private fun pageFiles(storage: DerivedStorage, prefix: String): List<Path> =
        Files.list(storage.directory).use { paths -> paths.filter { it.fileName.toString().startsWith(prefix) }.sorted().toList() }

    private fun overwrite(path: Path, position: Long, bytes: ByteArray) =
        FileChannel.open(path, WRITE).use { it.write(ByteBuffer.wrap(bytes), position) }

    private fun readAt(path: Path, position: Long, length: Int): ByteArray =
        FileChannel.open(path, READ).use { ch -> ByteBuffer.allocate(length).also { ch.read(it, position) }.array() }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    // ---------------------------------------------------------------- pages

    @Test
    fun `fixed logs keep records across page boundaries and serve the unwritten tail from memory`() {
        storage().use { storage ->
            val longs = FixedLog(storage.create("longs"), 8)
            val digests = FixedLog(storage.create("digests"), Digest.SIZE)
            repeat(3_000) {
                longs.addLong(it * 31L - 7)
                digests.add(Hashes.sha256(byteArrayOf(it.toByte(), (it ushr 8).toByte())).bytes())
            }
            assertEquals(3_000L, longs.size)
            // 512 longs or 128 digests per page: these indexes straddle written pages and the in-memory tail.
            for (i in listOf(0, 1, 127, 128, 511, 512, 513, 2_047, 2_048, 2_999)) {
                assertEquals(i * 31L - 7, longs.long(i.toLong()))
                val digest = ByteArray(Digest.SIZE).also { digests.read(i.toLong(), it) }
                assertContentEquals(Hashes.sha256(byteArrayOf(i.toByte(), (i ushr 8).toByte())).bytes(), digest)
            }
            // Only full pages are on disk: 5 of longs (2,560 records), and the tail of 440 is still in memory.
            assertEquals(5 * pageSlot, Files.size(pageFiles(storage, "longs").single()))
        }
    }

    @Test
    fun `an altered moved or foreign page fails authentication and raises the alarm`() {
        val attacks = mapOf<String, (Path, Path) -> Unit>(
            "edit one byte" to { a, _ -> overwrite(a, pageSlot + 100, byteArrayOf(0x55)) },
            "edit the MAC" to { a, _ -> overwrite(a, pageSlot + PAGE_SIZE + 3, byteArrayOf(0x55)) },
            "move a page within the file" to { a, _ -> overwrite(a, pageSlot, readAt(a, 2 * pageSlot, pageSlot.toInt())) },
            "copy the same page from another file" to { a, b -> overwrite(a, pageSlot, readAt(b, pageSlot, pageSlot.toInt())) },
            "truncate the file" to { a, _ -> FileChannel.open(a, WRITE).use { it.truncate(pageSlot + 10) } },
        )
        for ((name, attack) in attacks) {
            val failures = AtomicReference<Throwable?>()
            storage(failures).use { storage ->
                val a = FixedLog(storage.create("a"), 8)
                val b = FixedLog(storage.create("b"), 8)
                repeat(4 * 512) { a.addLong(it.toLong()); b.addLong(it.toLong()) } // identical contents, different files
                assertEquals(600L, a.long(600))
                attack(pageFiles(storage, "a-").single(), pageFiles(storage, "b-").single())
                a.long(0) // a different page displaces page 1 from the one-page cache
                assertFailsWith<TamperDetectedException>(name) { a.long(600) }
                assertTrue(failures.get() is TamperDetectedException, name)
                assertEquals(10L, a.long(10), "$name: untouched pages still read")
            }
        }
    }

    @Test
    fun `derived files are emptied at open never read from a previous run and removed at close`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        val derived = dir.resolve(Ledger.DERIVED_DIRECTORY)
        Ledger.open(config(dir, signer).copy(indexFlushSize = 16)).use { ledger ->
            repeat(200) { SportsbookWallet(ledger).deposit("p", 1, "EUR", "derived-$it") }
            assertTrue(Files.list(derived).use { it.count() } > 3, "offsets, Merkle levels and key runs are on disk while open")
        }
        assertFalse(Files.exists(derived), "nothing derived is kept for the next start")
        // Whatever is found there at the next start, even something shaped like our own files, is deleted unread.
        Files.createDirectories(derived)
        Files.write(derived.resolve("offsets-0.pages"), ByteArray(3 * pageSlot.toInt()) { 0x7F })
        Files.write(derived.resolve("keys-run-9.pages"), ByteArray(pageSlot.toInt()))
        Ledger.open(config(dir, signer).copy(indexFlushSize = 16)).use { ledger ->
            assertEquals(200L, ledger.head().size)
            assertEquals(7L, ledger.entry(7).entry.seq)
            assertTrue(SportsbookWallet(ledger).deposit("p", 1, "EUR", "derived-7").replayed)
            assertTrue(ledger.inclusionProof(150).verify())
        }
        assertTrue(LedgerVerifier.verify(dir, TestSupport.LEDGER_ID, listOf(signer.publicKey)).ok)
    }

    @Test
    fun `tampering with any derived file of a running ledger stops it`() {
        for (prefix in listOf("offsets", "merkle-0", "keys-run")) {
            val dir = tempDir()
            val config = config(dir, Ed25519TreeHeadSigner.generate()).copy(entryCacheSize = 1, derivedCachePages = 1, indexFlushSize = 64)
            Ledger.open(config).use { ledger ->
                val wallet = SportsbookWallet(ledger)
                repeat(1_200) { wallet.deposit("p", 1, "EUR", "run-$it") }
                val files = Files.list(dir.resolve(Ledger.DERIVED_DIRECTORY)).use { paths ->
                    paths.filter { it.fileName.toString().startsWith(prefix) && Files.size(it) >= pageSlot }.toList()
                }
                if (prefix == "keys-run") awaitTrue("a key run on disk") {
                    Files.list(dir.resolve(Ledger.DERIVED_DIRECTORY)).use { p -> p.anyMatch { it.fileName.toString().startsWith(prefix) && Files.size(it) >= pageSlot } }
                }
                val targets = if (files.isNotEmpty()) files else Files.list(dir.resolve(Ledger.DERIVED_DIRECTORY)).use { paths ->
                    paths.filter { it.fileName.toString().startsWith(prefix) && Files.size(it) >= pageSlot }.toList()
                }
                assertTrue(targets.isNotEmpty(), prefix)
                // Flip one byte in the first page of every such file. Each write races index maintenance
                // only in that a merged-away run may already be gone, which is fine.
                for (file in targets) runCatching { overwrite(file, 17, byteArrayOf(readAt(file, 17, 1)[0].inc())) }
                // Background index maintenance may read a damaged run page first and stop the ledger before
                // the reads below do. Either detector is correct; what must hold is checked after them.
                // Something that needs the damaged page: old entries, their proofs, and retries of old keys.
                val failure = runCatching {
                    repeat(1_200) { i ->
                        ledger.entry(i.toLong())
                        ledger.inclusionProof(i.toLong())
                        wallet.deposit("p", 1, "EUR", "run-$i")
                    }
                }.exceptionOrNull()
                assertNotNull(failure, "$prefix: a damaged index page must not be used")
                assertTrue(failure is TamperDetectedException || failure is LedgerFailedException, "$prefix: $failure")
                assertTrue(ledger.isFailed, prefix)
                assertFailsWith<LedgerFailedException> { wallet.deposit("p", 1, "EUR", "after-$prefix") }
                assertEquals(1_200L, ledger.head().size, "$prefix: nothing was written on the strength of a damaged index")
            }
        }
    }

    @Test
    fun `a failure raised before the ledger exists is delivered when it connects`() {
        val alarm = FailureAlarm()
        val early = TamperDetectedException("early")
        alarm.raise(early)
        alarm.raise(TamperDetectedException("second")) // only the first is kept; it is enough to stop
        val seen = ArrayList<Throwable>()
        alarm.connect { seen += it }
        assertEquals(listOf<Throwable>(early), seen, "a failure in the gap before connect is not lost")
        val late = TamperDetectedException("late")
        alarm.raise(late)
        assertEquals(listOf<Throwable>(early, late), seen, "afterwards failures go straight to the ledger")
    }

    @Test
    fun `a lookup on a closed index ends instead of retrying forever`() {
        storage(cachePages = 1).use { storage ->
            val index = HashIndex(storage, "closing", flushSize = 16, bloomBitsPerKey = 0).apply { mayWait = true }
            repeat(2_000) { index.put(it + 1L, it.toLong()) }
            awaitTrue("runs on disk") { index.runCount > 0 }
            index.close()
            // The runs are gone and nothing replaces them. Every page read must give up, not spin.
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                runCatching { (1L..2_000L).forEach { h -> index.anyCandidate(h) { false } } }
                runCatching { index.forEach { _, _ -> } }
            }
        }
    }

    // ----------------------------------------------------------- hash index

    @Test
    fun `on-disk index with its filter answers exactly as an in-memory map across flushes and merges`() {
        val failures = AtomicReference<Throwable?>()
        storage(failures, cachePages = 4).use { storage ->
            HashIndex(storage, "t", flushSize = 64, bloomBitsPerKey = 10).use { index ->
                val random = Random(73)
                val expected = HashMap<Long, MutableList<Long>>()
                repeat(30_000) { seq ->
                    // One hash in ten comes from a tiny space, so many keys share a hash as a collision would.
                    val hash = if (seq % 10 == 0) 1L + random.nextInt(300) else random.nextLong().let { if (it == 0L) 1L else it }
                    index.put(hash, seq.toLong())
                    expected.getOrPut(hash) { ArrayList() }.add(seq.toLong())
                }
                fun check() {
                    for ((hash, seqs) in expected) {
                        assertTrue(index.mightContain(hash), "the filter must never deny a key that is present")
                        val found = HashSet<Long>()
                        assertFalse(index.anyCandidate(hash) { found += it; false })
                        assertEquals(seqs.toSet(), found)
                    }
                }
                check() // while maintenance is still flushing and merging
                awaitTrue("flushes and merges to settle") { index.runCount in 1..12 }
                Thread.sleep(200)
                check()
                assertEquals(30_000L, index.count)
                assertTrue(index.runCount <= 12, "runs are merged, not accumulated: ${index.runCount}")
                // Absent keys: nearly all are refused by the filter in memory; none yields a candidate.
                var maybe = 0
                repeat(20_000) {
                    val absent = random.nextLong() or Long.MIN_VALUE.ushr(1) // any hash not in the tiny space
                    if (absent !in expected) {
                        if (index.mightContain(absent)) maybe++
                        assertFalse(index.anyCandidate(absent) { true })
                    }
                }
                assertTrue(maybe < 1_000, "false-positive rate should be near 1%, saw $maybe in 20,000")
                val all = HashSet<Pair<Long, Long>>()
                index.forEach { hash, seq -> all += hash to seq }
                assertEquals(expected.flatMap { (h, seqs) -> seqs.map { h to it } }.toSet(), all)
            }
            assertNull(failures.get())
        }
    }

    @Test
    fun `a key is always found once added while the index is flushed merged and its filter rebuilt`() {
        val failures = AtomicReference<Throwable?>()
        storage(failures, cachePages = 8).use { storage ->
            HashIndex(storage, "race", flushSize = 32, bloomBitsPerKey = 10).use { index ->
                val published = AtomicLong(0) // keys 1..published have been added
                fun hashOf(n: Long) = KeyIndex.hashOf("race-key-$n")
                val outcomes = race(6) { worker ->
                    if (worker == 0) {
                        for (n in 1L..40_000L) { index.put(hashOf(n), n); published.set(n) }
                    } else {
                        val random = Random(worker)
                        var checked = 0
                        while (published.get() < 40_000L || checked < 2_000) {
                            val upTo = published.get()
                            if (upTo == 0L) continue
                            val n = 1 + random.nextLong(upTo)
                            assertTrue(index.mightContain(hashOf(n)), "filter denied key $n of $upTo")
                            assertTrue(index.anyCandidate(hashOf(n)) { it == n }, "key $n of $upTo not found")
                            checked++
                        }
                    }
                }
                outcomes.forEach { it.getOrThrow() }
                for (n in 1L..40_000L) assertTrue(index.anyCandidate(hashOf(n)) { it == n })
            }
            assertNull(failures.get())
        }
    }

    // ----------------------------------------------------------------- chains

    @Test
    fun `chains return any element and the latest page while holding only a few marks each`() {
        storage(cachePages = 16).use { storage ->
            val log = ChainLog(FixedLog(storage.create("chains"), 8))
            val random = Random(7)
            val chains = List(5) { ChainLog.Chain() }
            val expected = List(5) { ArrayList<Long>() }
            repeat(20_000) {
                // Interleaved appends, as different players' entries interleave in a ledger. Chain 0 gets most.
                val c = if (random.nextInt(3) > 0) 0 else 1 + random.nextInt(4)
                val value = random.nextLong(1L shl 40)
                log.append(chains[c], value)
                expected[c] += value
            }
            for (c in chains.indices) {
                val chain = chains[c]
                val values = expected[c]
                assertEquals(values.size.toLong(), chain.count)
                assertContentEquals(values.toLongArray(), log.tail(chain))
                assertContentEquals(values.takeLast(40).toLongArray(), log.tail(chain, 40))
                assertEquals(0, log.tail(chain, 0).size)
                for (n in listOf(1L, 2L, 3L, 4L, 5L, 63L, 64L, 65L, chain.count / 2, chain.count - 1, chain.count) + List(200) { 1 + random.nextLong(chain.count) }) {
                    if (n in 1..chain.count) assertEquals(values[(n - 1).toInt()], log.get(chain, n), "chain $c element $n")
                }
                val newest = ArrayList<Long>()
                log.walkBack(chain) { newest += it; newest.size < 7 }
                assertEquals(values.takeLast(7).reversed(), newest)
            }
            assertFailsWith<IllegalArgumentException> { log.get(chains[0], chains[0].count + 1) }
            assertContentEquals(LongArray(0), log.tail(ChainLog.Chain()))
        }
    }

    // ------------------------------------------------------------- end to end

    @Test
    fun `proofs read from node files equal proofs hashed from the leaves`() {
        val dir = tempDir()
        Ledger.open(config(dir, Ed25519TreeHeadSigner.generate()).copy(derivedCachePages = 2)).use { ledger ->
            repeat(777) { SportsbookWallet(ledger).deposit("p${it % 3}", 1, "EUR", "node-$it") }
            val leaves = ArrayList<Digest>()
            ledger.scan(0, 777) { leaves += it.leafHash }
            val reference = MerkleProver({ leaves[it.toInt()] }) // hashes every subtree from the leaves, no stored nodes
            for (size in listOf(1L, 2L, 3L, 64L, 255L, 256L, 257L, 500L, 776L, 777L)) {
                assertEquals(reference.root(size), ledger.merkleRoot(size), "root of $size")
                for (seq in listOf(0L, size / 2, size - 1)) {
                    val proof = ledger.inclusionProof(seq, size)
                    assertEquals(reference.inclusionPath(seq, size), proof.auditPath, "path of $seq in $size")
                    assertTrue(proof.verify())
                }
                if (size > 1) assertEquals(reference.consistencyProof(size / 2, size), ledger.consistencyProof(size / 2, size))
            }
            assertEquals(ledger.head().merkleRoot, reference.root(777))
        }
    }

    @Test
    fun `a bet settled again under another key replaces its outcome in the fixture summary`() {
        fun settle(ledger: Ledger, key: String, kind: String, payout: Long) = ledger.append(TransactionRequest(key, kind,
            if (payout > 0) listOf(
                Posting(AccountId("house:funds:EUR"), "EUR", -payout), Posting(AccountId("player:alice:cash:EUR"), "EUR", payout))
            else listOf(Posting(AccountId("house:funds:EUR"), "EUR", -1), Posting(AccountId("house:other:EUR"), "EUR", 1)),
            mapOf("playerId" to "alice", "betId" to "legacy")))
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        val expected = ReadModel.FixtureSummary(mapOf("HOME" to 200), mapOf("HOME" to setOf("WON")), 0)
        Ledger.open(config(dir, signer)).use { ledger ->
            ledger.append(TransactionRequest("legacy-place", "BET_PLACED", listOf(
                Posting(AccountId("house:funds:EUR"), "EUR", -100), Posting(AccountId("player:alice:open-bets:EUR"), "EUR", 100)),
                mapOf("playerId" to "alice", "betId" to "legacy", "selection" to "F1|HOME|200")))
            ReadModel(ledger).use { model ->
                settle(ledger, "legacy-first", "BET_LOST", 0)
                model.catchUp()
                assertEquals(mapOf("HOME" to setOf("LOST")), model.fixtureSummaries().getValue("F1").outcomes)
                settle(ledger, "legacy-second", "BET_WON", 200)
                // A settlement for a bet this model never saw placed changes nothing.
                settle(ledger, "unknown-bet", "BET_WON", 5).also { assertTrue(it.seq > 0) }
                model.catchUp()
                assertEquals(expected, model.fixtureSummaries().getValue("F1"))
                assertEquals("WON", model.bet("legacy")!!.outcome)
            }
        }
        // The same answer when the model is built during recovery, where it cannot read other entries.
        val recovering = ReadModel.Recovering()
        Ledger.open(config(dir, signer), recovering).use { ledger ->
            recovering.attach(ledger).use { model ->
                assertEquals(expected, model.fixtureSummaries().getValue("F1"))
                assertEquals("WON", model.bet("legacy")!!.outcome)
            }
        }
    }
}
