package com.pam.ledger

import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.readRecords
import com.pam.ledger.TestSupport.tempDir
import com.pam.ledger.web.ReadModel
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32C
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
 * Entries live in the journal, not on the heap (issue #73). These tests pin what
 * that must never cost: an entry read back from disk is the entry that was
 * verified, idempotency stays exact without storing keys, and readers cannot
 * disturb the writer.
 */
class EntryStoreTest {
    private fun entriesPath(dir: Path) = dir.resolve(Ledger.ENTRIES_FILE)

    /**
     * A ledger that keeps as little in memory as it can: one cached entry, two cached index pages,
     * and an idempotency table flushed to disk every 32 keys. Almost every read comes from a file.
     */
    private fun coldConfig(dir: Path, signer: TreeHeadSigner) =
        config(dir, signer).copy(entryCacheSize = 1, derivedCachePages = 2, indexFlushSize = 32)

    /** Overwrite the body of record [index] in place, optionally repairing the frame CRC as a careful attacker would. */
    private fun alterRecord(dir: Path, index: Int, repairCrc: Boolean) {
        val records = readRecords(entriesPath(dir))
        val offset = records.take(index).sumOf { Journal.HEADER + it.size.toLong() }
        val altered = records[index].copyOf().also { it[it.size - Digest.SIZE - 1] = (it[it.size - Digest.SIZE - 1] + 1).toByte() }
        FileChannel.open(entriesPath(dir), WRITE).use { ch ->
            ch.write(ByteBuffer.wrap(altered), offset + Journal.HEADER)
            if (repairCrc) {
                val crc = CRC32C().apply { update(altered) }
                ch.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).flip(), offset + 4)
            }
            ch.force(true)
        }
    }

    @Test
    fun `entries read back from the journal equal the entries that were committed`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        val committed = ArrayList<Pair<Receipt, ByteArray>>()
        Ledger.open(coldConfig(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            repeat(200) {
                val receipt = wallet.deposit("p${it % 7}", 100L + it, "EUR", "store-$it")
                committed += receipt to ledger.entry(receipt.seq).encodedBytes()
            }
            // A long metadata value makes one record larger than the reader's first read.
            val big = ledger.append(TransactionRequest("store-big", "DEPOSIT", listOf(
                Posting(SportsbookWallet.Accounts.pspClearing("EUR"), "EUR", -5),
                Posting(SportsbookWallet.Accounts.cash("p0", "EUR"), "EUR", 5)), mapOf("note-a" to "x".repeat(2_000), "note-b" to "y".repeat(2_000))))
            committed += big to ledger.entry(big.seq).encodedBytes()
            for (round in 0 until 2) for ((receipt, bytes) in committed.shuffled(Random(round))) {
                val entry = ledger.entry(receipt.seq)
                assertEquals(receipt.seq, entry.entry.seq)
                assertEquals(receipt.leafHash, entry.leafHash)
                assertEquals(receipt.chainHash, entry.chainHash)
                assertEquals(receipt.txId, entry.entry.txId)
                assertContentEquals(bytes, entry.encodedBytes())
            }
            val scanned = ArrayList<Long>()
            ledger.scan(50, 150) { scanned += it.entry.seq; assertEquals(committed[it.entry.seq.toInt()].first.leafHash, it.leafHash) }
            assertEquals((50L until 150L).toList(), scanned)
            assertFailsWith<IndexOutOfBoundsException> { ledger.entry(ledger.head().size) }
            assertFailsWith<IndexOutOfBoundsException> { ledger.entry(-1) }
            assertFailsWith<IndexOutOfBoundsException> { ledger.scan(0, ledger.head().size + 1) {} }
        }
        // After a restart nothing is cached: every entry comes from the journal and still matches its receipt.
        Ledger.open(coldConfig(dir, signer)).use { ledger ->
            for ((receipt, bytes) in committed) {
                assertContentEquals(bytes, ledger.entry(receipt.seq).encodedBytes())
                assertEquals(receipt.chainHash, ledger.entry(receipt.seq).chainHash)
                assertTrue(ledger.inclusionProof(receipt.seq).verify())
            }
        }
    }

    @Test
    fun `a record altered on disk after startup is detected on read and stops the ledger`() {
        for (repairCrc in listOf(false, true)) {
            val dir = tempDir()
            Ledger.open(coldConfig(dir, Ed25519TreeHeadSigner.generate())).use { ledger ->
                val wallet = SportsbookWallet(ledger)
                repeat(20) { wallet.deposit("victim", 100, "EUR", "tamper-$it") }
                assertEquals(3L, ledger.entry(3).entry.seq) // readable before the attack
                alterRecord(dir, 3, repairCrc)
                assertEquals(19L, ledger.entry(19).entry.seq) // displaces entry 3 from the one-entry cache
                assertFalse(ledger.isFailed)
                assertFailsWith<TamperDetectedException>("repairCrc=$repairCrc") { ledger.entry(3) }
                assertTrue(ledger.isFailed, "a ledger that cannot prove its history must stop")
                assertFailsWith<LedgerFailedException> { wallet.deposit("victim", 1, "EUR", "after-tamper") }
                assertEquals(20L, ledger.head().size)
                // Untouched history is still readable and still provable.
                assertEquals(5L, ledger.entry(5).entry.seq)
                assertTrue(ledger.inclusionProof(5).verify())
            }
        }
    }

    @Test
    fun `a retry whose original entry was altered on disk fails stop instead of committing twice`() {
        val dir = tempDir()
        Ledger.open(coldConfig(dir, Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            repeat(10) { wallet.deposit("victim", 100, "EUR", "retry-$it") }
            alterRecord(dir, 2, repairCrc = true)
            // The writer must read entry 2 to compare keys and fingerprints. It cannot prove what it
            // reads, so it stops rather than guess whether this is a retry or a new transaction.
            val retry = TransactionRequest("deposit:retry-2", "DEPOSIT", listOf(
                Posting(SportsbookWallet.Accounts.pspClearing("EUR"), "EUR", -100),
                Posting(SportsbookWallet.Accounts.cash("victim", "EUR"), "EUR", 100)), mapOf("playerId" to "victim"))
            assertFailsWith<LedgerFailedException> { ledger.append(retry) }
            // The wallet's own lookup of the reference fails the same way, on the caller's thread.
            assertFailsWith<LedgerException> { wallet.deposit("victim", 100, "EUR", "retry-2") }
            assertTrue(ledger.isFailed)
            assertEquals(10L, ledger.head().size)
            assertEquals(1_000L, wallet.cashBalance("victim", "EUR"))
        }
    }

    @Test
    fun `idempotency stays exact across restarts without keeping the keys in memory`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        val receipts = HashMap<String, Receipt>()
        Ledger.open(coldConfig(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            repeat(300) { receipts["idem-$it"] = wallet.deposit("p${it % 5}", 10L + it, "EUR", "idem-$it") }
        }
        Ledger.open(coldConfig(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            for ((ref, original) in receipts) {
                val i = ref.removePrefix("idem-").toInt()
                val replay = wallet.deposit("p${i % 5}", 10L + i, "EUR", ref)
                assertTrue(replay.replayed)
                assertEquals(original.copy(replayed = true), replay)
                assertEquals(original.seq, ledger.findByIdempotencyKey("deposit:$ref")!!.entry.seq)
            }
            assertFailsWith<IdempotencyConflictException> { wallet.deposit("p0", 999, "EUR", "idem-0") }
            assertNull(ledger.findByIdempotencyKey("deposit:idem-never"))
            assertEquals(300L, ledger.head().size)
            assertFalse(wallet.deposit("p0", 1, "EUR", "idem-new").replayed)
            assertEquals(301L, ledger.head().size)
        }
    }

    @Test
    fun `key index returns every candidate for a shared hash and survives growth`() {
        val index = KeyIndex()
        val random = Random(73)
        val expected = HashMap<Long, MutableList<Long>>()
        repeat(50_000) { seq ->
            // A small hash space forces many keys to share a hash, as a real collision would.
            val hash = if (seq % 10 == 0) 1L + random.nextInt(500) else random.nextLong().let { if (it == 0L) 1L else it }
            index.put(hash, seq.toLong())
            expected.getOrPut(hash) { ArrayList() }.add(seq.toLong())
        }
        for ((hash, seqs) in expected) {
            val found = ArrayList<Long>()
            assertFalse(index.anyCandidate(hash) { found += it; false })
            assertEquals(seqs.sorted(), found.sorted())
            assertTrue(index.anyCandidate(hash) { it == seqs.last() })
        }
        assertFalse(index.anyCandidate(Long.MIN_VALUE + 12345) { true })
        assertTrue(KeyIndex.hashOf("bet:1:place") != 0L && KeyIndex.hashOf("bet:1:place") == KeyIndex.hashOf("bet:1:place"))
        assertTrue(KeyIndex.hashOf("bet:1:place") != KeyIndex.hashOf("bet:1:settle"))
    }

    @Test
    fun `readers loading old entries race the writer and see only committed bytes`() {
        Ledger.open(coldConfig(tempDir(), Ed25519TreeHeadSigner.generate()).copy(entryCacheSize = 8)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            repeat(50) { wallet.deposit("seed", 1, "EUR", "race-seed-$it") }
            val outcomes = race(8) { worker ->
                if (worker < 3) List(200) { wallet.deposit("w$worker", 1L + it, "EUR", "race-$worker-$it") }
                else {
                    val random = Random(worker)
                    repeat(1_500) {
                        val size = ledger.head().size
                        val seq = random.nextLong(size)
                        val entry = ledger.entry(seq)
                        assertEquals(seq, entry.entry.seq)
                        assertEquals(Hashes.leaf(entry.encodedBytes()), entry.leafHash)
                        if (seq > 0) assertEquals(ledger.entry(seq - 1).chainHash, entry.entry.prevChainHash)
                        ledger.findByIdempotencyKey(entry.entry.idempotencyKey).let { assertEquals(seq, it!!.entry.seq) }
                    }
                    emptyList()
                }
            }
            val receipts = outcomes.flatMap { it.getOrThrow() }
            assertEquals(600, receipts.size)
            assertEquals(650L, ledger.head().size)
            for (r in receipts) assertEquals(r.leafHash, ledger.entry(r.seq).leafHash)
            assertFalse(ledger.isFailed)
        }
    }

    @Test
    fun `an interrupted reader cannot close the journal for the writer or for other readers`() {
        Ledger.open(coldConfig(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            repeat(30) { wallet.deposit("p", 1, "EUR", "interrupt-$it") }
            val failure = AtomicReference<Throwable?>()
            val reader = Thread {
                Thread.currentThread().interrupt() // a blocked read on an interrupted thread closes its channel
                try { ledger.entry(4) } catch (t: Throwable) { failure.set(t) }
            }
            reader.start()
            reader.join()
            assertNotNull(failure.get(), "an interrupted read should fail for that thread")
            assertTrue(failure.get() is EntryUnreadableException, "got ${failure.get()}")
            // Everyone else carries on: other reads reopen the channel, and the writer never shared it.
            assertEquals(4L, ledger.entry(4).entry.seq)
            assertEquals(11L, ledger.entry(11).entry.seq)
            assertFalse(ledger.isFailed)
            assertFalse(wallet.deposit("p", 1, "EUR", "interrupt-after").replayed)
            assertTrue(wallet.deposit("p", 1, "EUR", "interrupt-3").replayed)
        }
    }

    @Test
    fun `a read model built during recovery equals one built by tailing`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            listOf("ann", "ben").forEach { wallet.deposit(it, 10_000, "EUR", "feed-$it") }
            repeat(60) { i ->
                val p = if (i % 2 == 0) "ann" else "ben"
                wallet.placeBet(p, "feed-$i", 50, "EUR", "F${i % 4}|${if (i % 3 == 0) "HOME" else "AWAY"}|210")
                when (i % 5) { 0 -> wallet.settleWin("feed-$i", 105); 1 -> wallet.settleLoss("feed-$i"); 2 -> wallet.cashOut("feed-$i", 42); 3 -> wallet.voidBet("feed-$i") }
            }
            wallet.withdraw("ann", 100, "EUR", "feed-out")
        }
        val recovering = ReadModel.Recovering()
        Ledger.open(config(dir, signer), recovering).use { ledger ->
            recovering.attach(ledger).use { fed ->
                ReadModel(ledger).use { tailed ->
                    tailed.catchUp()
                    assertEquals(ledger.head().size, fed.totals().entries, "recovery indexed every entry before the ledger opened")
                    assertEquals(tailed.totals(), fed.totals())
                    assertEquals(tailed.allBets(), fed.allBets())
                    assertEquals(tailed.openBets(), fed.openBets())
                    assertEquals(tailed.fixtureSummaries(), fed.fixtureSummaries())
                    assertEquals(tailed.players(), fed.players())
                    for (p in listOf("ann", "ben")) {
                        assertEquals(tailed.historyForPlayer(p, 40), fed.historyForPlayer(p, 40))
                        assertEquals(tailed.betsForPlayer(p, 40), fed.betsForPlayer(p, 40))
                        assertEquals(tailed.openBetsForPlayer(p), fed.openBetsForPlayer(p))
                        val cash = SportsbookWallet.Accounts.cash(p, "EUR")
                        assertContentEquals(tailed.seqsForAccount(cash), fed.seqsForAccount(cash))
                    }
                    // It keeps tailing from where recovery stopped.
                    SportsbookWallet(ledger).deposit("cy", 5, "EUR", "feed-later")
                    fed.catchUp()
                    assertEquals(ledger.head().size, fed.totals().entries)
                    assertTrue("cy" in fed.players())
                }
            }
        }
    }
}
