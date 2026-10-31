package com.pam.ledger.web

import com.pam.ledger.Hashes
import com.pam.ledger.Journal
import com.pam.ledger.Ledger
import com.pam.ledger.LedgerVerifier
import com.pam.ledger.SealedEntry
import com.pam.ledger.SignedTreeHead
import com.pam.ledger.EntryCodec
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * ============================================================================
 *  REGULATOR TOOLS: checkpoint history, independent audit, tamper lab
 * ============================================================================
 *
 *  Everything here READS the ledger's files. The audit and tamper lab work on a
 *  snapshot copy, so the running ledger is never touched, and a deliberately
 *  corrupted copy can be examined safely.
 */
class Audit(
    private val ledger: Ledger,
    private val directory: Path,
    private val ledgerId: String,
    private val trustedKeys: List<PublicKey>,
) : AutoCloseable {

    // ---- Checkpoint history: every signed tree head, oldest first ----

    private val history = ArrayList<SignedTreeHead>()
    private var checkpointOffset = 0L // guarded by `history`
    private val poller = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sth-history").apply { isDaemon = true } }

    init {
        refreshHistory()
        poller.scheduleAtFixedRate({ runCatching { refreshHistory() } }, 0, 250, TimeUnit.MILLISECONDS)
    }

    private fun refreshHistory() = synchronized(history) {
        checkpointOffset = Journal.scanReadOnly(directory.resolve(Ledger.CHECKPOINTS_FILE), checkpointOffset) {
            history += SignedTreeHead.decode(it)
        }.validBytes
    }

    fun checkpoints(): List<SignedTreeHead> {
        refreshHistory()
        return synchronized(history) { history.toList() }
    }

    /** Sign a checkpoint now. It is persisted before this returns, so the history includes it. */
    fun checkpointNow(): SignedTreeHead = ledger.checkpoint().also { refreshHistory() }

    /** A checkpoint covering entry [seq]: the latest one if it does, otherwise a fresh one. */
    fun checkpointCovering(seq: Long): SignedTreeHead {
        val latest = ledger.latestCheckpoint()
        return if (latest != null && latest.head.treeSize > seq) latest else checkpointNow()
    }

    fun checkpointOfSize(size: Long): SignedTreeHead? {
        refreshHistory()
        return synchronized(history) { history.lastOrNull { it.head.treeSize == size } }
    }

    // ---- Snapshot and independent audit ----

    /** Copy one signed prefix so no concurrent append can escape its checkpoint. */
    private fun snapshot(anchor: SignedTreeHead): Path {
        val copy = Files.createTempDirectory("pam-ledger-snapshot")
        try {
            val checkpoints = ArrayList<ByteArray>()
            val anchorBytes = anchor.encode()
            var prefixEnd = -1
            Journal.scanReadOnly(directory.resolve(Ledger.CHECKPOINTS_FILE)) { record ->
                checkpoints += record
                if (record.contentEquals(anchorBytes)) prefixEnd = checkpoints.size
            }
            check(prefixEnd >= 0) { "ledger snapshot is missing its signed checkpoint" }
            // Bound by the anchor's position, never by an unverified record's claimed size.
            writeRecords(copy.resolve(Ledger.CHECKPOINTS_FILE), checkpoints.take(prefixEnd))
            copyEntryPrefix(copy.resolve(Ledger.ENTRIES_FILE), anchor.head.treeSize)
            return copy
        } catch (t: Throwable) {
            copy.toFile().deleteRecursively()
            throw t
        }
    }

    private fun copyEntryPrefix(destination: Path, treeSize: Long) {
        val (journal, _) = Journal.openForAppend(destination) {}
        val batch = ArrayList<ByteArray>(1_000)
        var copied = 0L
        try {
            Journal.scanReadOnly(directory.resolve(Ledger.ENTRIES_FILE)) { record ->
                if (copied < treeSize) {
                    batch += record
                    copied++
                    if (batch.size == 1_000) {
                        journal.appendBatch(batch)
                        batch.clear()
                    }
                }
            }
            check(copied == treeSize) { "ledger snapshot ended before its signed head" }
            if (batch.isNotEmpty()) journal.appendBatch(batch)
        } finally {
            journal.close()
        }
    }

    private fun writeRecords(path: Path, records: List<ByteArray>) {
        val (journal, _) = Journal.openForAppend(path) {}
        journal.use { out -> records.chunked(1_000).forEach(out::appendBatch) }
    }

    data class AuditResult(val report: LedgerVerifier.Report, val millis: Long, val note: String)

    /** Run the offline verifier, exactly as a regulator would, over a fresh snapshot. */
    fun fullAudit(): AuditResult {
        val started = System.nanoTime()
        // Anchor everything committed so far: unsigned history would (rightly) fail the audit.
        val checkpoint = checkpointNow()
        val dir = snapshot(checkpoint)
        try {
            val report = LedgerVerifier.verify(dir, ledgerId, trustedKeys)
            return AuditResult(report, (System.nanoTime() - started) / 1_000_000, "Independent audit of a snapshot of the live ledger.")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---- Tamper lab ----

    /**
     * Corrupt a snapshot COPY in one of three ways and audit it. The live ledger is untouched.
     *
     *  - "edit":     inflate one deposit tenfold in place (a careless forger);
     *  - "rewrite":  inflate it and recompute every later hash (a careful forger);
     *  - "truncate": delete the newest five entries (a rollback to hide a recent payout).
     */
    fun tamper(mode: String): AuditResult {
        require(mode in setOf("edit", "rewrite", "truncate")) { "mode must be edit, rewrite or truncate" }
        val checkpoint = checkpointNow() // the tamper must alter history covered by this signed head
        val dir = snapshot(checkpoint)
        try {
            val path = dir.resolve(Ledger.ENTRIES_FILE)
            val records = ArrayList<ByteArray>()
            Journal.scanReadOnly(path) { records += it }
            require(records.size >= 6) { "the ledger needs a few more entries first" }
            fun body(r: ByteArray) = r.copyOfRange(0, r.size - 32)

            val note = when (mode) {
                "truncate" -> {
                    repeat(5) { records.removeAt(records.size - 1) }
                    "Deleted the newest 5 entries from a copy of the journal, as if to hide recent payouts."
                }
                else -> {
                    // The victim: the most recent deposit outside the newest few entries.
                    val target = (records.size - 3 downTo 0).first { EntryCodec.decode(body(records[it])).kind == "DEPOSIT" }
                    val original = EntryCodec.decode(body(records[target]))
                    val forged = original.copy(postings = original.postings.map { it.copy(amount = it.amount * 10) })
                    if (mode == "edit") {
                        // Re-encode the forged entry but keep the stored chain hash: no hashes recomputed.
                        records[target] = EntryCodec.encode(forged) + records[target].copyOfRange(records[target].size - 32, records[target].size)
                        "Inflated deposit #$target tenfold in a copy of the journal, without touching any hashes."
                    } else {
                        // Recompute the chain from the forged entry onwards: internally consistent.
                        var chain = if (target == 0) Hashes.genesis(ledgerId) else SealedEntry.seal(EntryCodec.decode(body(records[target - 1]))).chainHash
                        for (i in target until records.size) {
                            val e = if (i == target) forged else EntryCodec.decode(body(records[i]))
                            val sealed = SealedEntry.seal(e.copy(prevChainHash = chain))
                            records[i] = sealed.journalRecord()
                            chain = sealed.chainHash
                        }
                        "Inflated deposit #$target tenfold in a copy of the journal and recomputed every later hash, so the chain is internally perfect."
                    }
                }
            }
            Files.delete(path)
            val (journal, _) = Journal.openForAppend(path) {}
            journal.use { it.appendBatch(records) }
            val started = System.nanoTime()
            val report = LedgerVerifier.verify(dir, ledgerId, trustedKeys)
            return AuditResult(report, (System.nanoTime() - started) / 1_000_000, note)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    override fun close() { poller.shutdownNow() }
}
