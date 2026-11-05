package com.pam.ledger

import com.pam.ledger.TestSupport.LEDGER_ID
import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.readRecords
import com.pam.ledger.TestSupport.tempDir
import com.pam.ledger.TestSupport.writeJournal
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tamper-evidence and crash-recovery tests. We play the attacker (or the
 * power cut) and check the ledger notices.
 */
class IntegrityTest {
    private val signer = Ed25519TreeHeadSigner.generate()

    private fun seed(dir: Path, deposits: Int = 10) {
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            repeat(deposits) { wallet.deposit("p$it", 1_000L + it, "EUR", "psp-$it") }
        } // close() writes a final signed checkpoint
    }

    private fun entriesPath(dir: Path) = dir.resolve(Ledger.ENTRIES_FILE)

    @Test
    fun `compact audit replay retains hashes and exact state but no full entries`() {
        val dir = tempDir()
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            ledger.checkpoint()
            repeat(129) { i ->
                wallet.deposit("p${i % 3}", 100, "EUR", "compact-$i")
                if (i in listOf(2, 15, 16, 63)) ledger.checkpoint()
            }
            wallet.placeBet("p0", "compact-bet", 100, "EUR", selection = "F1|HOME|200")
            wallet.settleWin("compact-bet", 200)
        }
        val full = Replayer(LEDGER_ID, AccountPolicy.PLAYER_ACCOUNTS_NON_NEGATIVE)
        val compact = Replayer(LEDGER_ID, AccountPolicy.PLAYER_ACCOUNTS_NON_NEGATIVE, retainEntries = false)
        Journal.scanReadOnly(entriesPath(dir)) { record ->
            full.apply(record)
            compact.apply(record)
            val seq = full.size - 1
            assertEquals(full.leafHash(seq), compact.leafHash(seq))
            assertEquals(full.chainHash(seq), compact.chainHash(seq))
            assertEquals(full.head(), compact.head())
            assertEquals(0, compact.entries.size, "the audit must not retain decoded entries or canonical byte arrays")
        }
        assertEquals(full.accounts, compact.accounts)
        assertEquals(full.idempotency, compact.idempotency, "duplicate-key detection must not expire")
        val fullProver = MerkleProver(full::leafHash)
        val compactProver = MerkleProver(compact::leafHash)
        for (size in 0..full.size) assertEquals(fullProver.root(size), compactProver.root(size))
        var previous: SignedTreeHead? = null
        Journal.scanReadOnly(dir.resolve(Ledger.CHECKPOINTS_FILE)) { record ->
            val sth = SignedTreeHead.decode(record)
            val keys = listOf(signer.publicKey).associateBy(::keyIdOf)
            CheckpointCheck.verify(sth, LEDGER_ID, keys, full, fullProver, previous)
            CheckpointCheck.verify(sth, LEDGER_ID, keys, compact, compactProver, previous)
            previous = sth
        }
        val duplicate = full.entries[0].entry.copy(
            seq = full.size, prevChainHash = full.chain, timestampMicros = full.lastTimestamp,
        )
        val error = assertFailsWith<TamperDetectedException> {
            compact.apply(SealedEntry.seal(duplicate).journalRecord())
        }
        assertTrue(error.message!!.contains("duplicate idempotency key"))
        assertEquals(full.head(), compact.head(), "rejection must not mutate verified state")
        val report = LedgerVerifier.verify(dir, LEDGER_ID, listOf(signer.publicKey))
        assertTrue(report.ok, report.toString())
        assertEquals(full.head(), report.head)
    }

    private fun assertTamperDetected(dir: Path, expectInMessage: String) {
        val e = runCatching { Ledger.open(config(dir, signer)).close() }.exceptionOrNull()
        assertTrue(e is TamperDetectedException, "expected tamper detection, got $e")
        assertTrue(e.message!!.contains(expectInMessage), "message was: ${e.message}")
        assertFalse(LedgerVerifier.verify(dir, LEDGER_ID, listOf(signer.publicKey)).ok)
    }

    /** Naive attacker: edits a byte (and even fixes the CRC). The hash chain catches it. */
    @Test
    fun `editing an entry in place is detected by the hash chain`() {
        val dir = tempDir()
        seed(dir)
        val records = readRecords(entriesPath(dir)).toMutableList()
        val victim = records[3].copyOf()
        victim[victim.size - 40] = (victim[victim.size - 40].toInt() xor 0x01).toByte() // flip a bit
        records[3] = victim
        writeJournal(entriesPath(dir), records) // writes fresh, CORRECT CRCs

        assertTamperDetected(dir, "entry #3")
    }

    /**
     * Sophisticated attacker: changes a deposit amount AND recomputes every hash
     * after it, producing a fully self-consistent chain. Only the signed
     * checkpoint, made with a key they do not have, gives them away.
     */
    @Test
    fun `rewriting history with recomputed hashes is caught by the signed checkpoint`() {
        val dir = tempDir()
        seed(dir)
        val original = readRecords(entriesPath(dir)).map { EntryCodec.decode(it.copyOfRange(0, it.size - 32)) }

        var chain = Hashes.genesis(LEDGER_ID)
        val forged = original.map { e ->
            val postings = if (e.seq == 2L) e.postings.map { it.copy(amount = it.amount * 100) } else e.postings
            val sealed = SealedEntry.seal(e.copy(prevChainHash = chain, postings = postings))
            chain = sealed.chainHash
            sealed.journalRecord()
        }
        writeJournal(entriesPath(dir), forged)

        // The forged chain is internally perfect: replay alone would accept it...
        val replayer = Replayer(LEDGER_ID, AccountPolicy.PLAYER_ACCOUNTS_NON_NEGATIVE)
        forged.forEach(replayer::apply)
        // ...but it contradicts the signed tree head.
        assertTamperDetected(dir, "history rewritten")
    }

    /** Deleting recent transactions (say, to hide a big win) contradicts the signed head. */
    @Test
    fun `rolling back committed entries is detected`() {
        val dir = tempDir()
        seed(dir)
        val records = readRecords(entriesPath(dir))
        writeJournal(entriesPath(dir), records.dropLast(3)) // clean truncation on a record boundary

        assertTamperDetected(dir, "truncated or rolled back")
    }

    /** A checkpoint signed by someone else's key is rejected. */
    @Test
    fun `checkpoints signed by an untrusted key are rejected`() {
        val dir = tempDir()
        val impostor = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(dir, impostor)).use { SportsbookWallet(it).deposit("m", 5, "EUR", "r") }
        assertTamperDetected(dir, "untrusted key")
    }

    /**
     * Power cut mid-write: the last record is half on disk. It was never
     * acknowledged, so recovery quarantines and drops it, and carries on.
     */
    @Test
    fun `a torn final write is quarantined and the ledger recovers`() {
        val dir = tempDir()
        seed(dir, deposits = 5)
        // Append a frame header promising 500 bytes, followed by only 100.
        Files.newByteChannel(entriesPath(dir), StandardOpenOption.APPEND).use { ch ->
            ch.write(ByteBuffer.allocate(108).putInt(500).putInt(12345).put(Random.nextBytes(100)).flip())
        }

        Ledger.open(config(dir, signer)).use { ledger ->
            assertEquals(108L, ledger.recoveryReport.tornBytesQuarantined)
            assertEquals(5L, ledger.head().size)
            SportsbookWallet(ledger).deposit("after-crash", 42, "EUR", "psp-new") // keeps working
            assertEquals(6L, ledger.head().size)
        }
        assertTrue(dir.listDirectoryEntries().any { it.name.startsWith("${Ledger.ENTRIES_FILE}.torn-") }, "torn bytes must be preserved")
        assertTrue(LedgerVerifier.verify(dir, LEDGER_ID, listOf(signer.publicKey)).ok)
    }

    /** Corruption in the MIDDLE of the file cannot be a torn write: refuse to start. */
    @Test
    fun `corruption before the tail is never silently truncated`() {
        val dir = tempDir()
        seed(dir)
        val path = entriesPath(dir)
        val bytes = Files.readAllBytes(path)
        bytes[20] = (bytes[20].toInt() xor 0xFF).toByte() // inside record #0, CRC now wrong
        Files.write(path, bytes)
        assertTamperDetected(dir, "CRC mismatch")
    }

    @Test
    fun `clean ledger passes independent verification and proofs check out`() {
        val dir = tempDir()
        seed(dir, deposits = 37)
        val report = LedgerVerifier.verify(dir, LEDGER_ID, listOf(signer.publicKey))
        assertTrue(report.ok, report.toString())
        assertEquals(37L, report.entries)

        Ledger.open(config(dir, signer)).use { ledger ->
            val sth = ledger.latestCheckpoint()!!
            assertTrue(sth.verify(signer.publicKey))
            for (i in 0 until 37L) assertTrue(ledger.inclusionProof(i, sth.head.treeSize).verify(), "inclusion of #$i")
            val cons = ledger.consistencyProof(10, 37)
            assertTrue(MerkleVerify.consistency(10, 37, ledger.merkleRoot(10), sth.head.rootHash, cons))
        }
    }

    @Test
    fun `unbalanced or malformed transactions are rejected before reaching the ledger`() {
        val a = AccountId("player:x:cash:EUR")
        val b = AccountId("house:revenue:EUR")
        val bad = listOf(
            { TransactionRequest("k", "DEPOSIT", listOf(Posting(a, "EUR", 10), Posting(b, "EUR", -9))) },   // unbalanced
            { TransactionRequest("k", "DEPOSIT", listOf(Posting(a, "EUR", 10), Posting(b, "GBP", -10))) },  // mixed currencies
            { TransactionRequest("k", "DEPOSIT", listOf(Posting(a, "EUR", 10))) },                          // single leg
            { TransactionRequest("k", "DEPOSIT", listOf(Posting(a, "EUR", Long.MAX_VALUE), Posting(b, "EUR", Long.MAX_VALUE), Posting(AccountId("c"), "EUR", 2))) }, // overflow trick
            { TransactionRequest("k\uD800", "DEPOSIT", listOf(Posting(a, "EUR", 1), Posting(b, "EUR", -1))) }, // broken unicode
        )
        bad.forEachIndexed { i, make -> assertTrue(runCatching(make).exceptionOrNull() is IllegalArgumentException, "case $i should be rejected") }
    }
}
