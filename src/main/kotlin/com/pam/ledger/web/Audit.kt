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
import java.nio.file.StandardCopyOption
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

    // The history is the checkpoints journal itself, tailed: every signed head ever persisted
    // is read exactly once, in order, however quickly they are signed. (Sampling only the
    // latest checkpoint would miss heads signed between samples.)
    private val history = ArrayList<SignedTreeHead>()
    private var tailOffset = 0L // guarded by `history`
    private val poller = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sth-history").apply { isDaemon = true } }

    init {
        refresh()
        poller.scheduleAtFixedRate({ runCatching { refresh() } }, 500, 500, TimeUnit.MILLISECONDS)
    }

    /** Read any checkpoint records appended since the last read. */
    private fun refresh() = synchronized(history) {
        tailOffset = Journal.scanReadOnly(directory.resolve(Ledger.CHECKPOINTS_FILE), tailOffset) { history += SignedTreeHead.decode(it) }.validBytes
    }

    fun checkpoints(): List<SignedTreeHead> = synchronized(history) { refresh(); history.toList() }

    /** Sign a checkpoint now. It is persisted before this returns, so the history includes it. */
    fun checkpointNow(): SignedTreeHead = ledger.checkpoint().also { refresh() }

    /** A checkpoint covering entry [seq]: the latest one if it does, otherwise a fresh one. */
    fun checkpointCovering(seq: Long): SignedTreeHead {
        val latest = ledger.latestCheckpoint()
        return if (latest != null && latest.head.treeSize > seq) latest else checkpointNow()
    }

    fun checkpointOfSize(size: Long): SignedTreeHead? = synchronized(history) { refresh(); history.lastOrNull { it.head.treeSize == size } }

    // ---- Snapshot and independent audit ----

    /**
     * Copy the ledger's files to a new folder. The checkpoints file is copied FIRST:
     * a checkpoint is written only after the entries it covers are fsynced, so copying
     * entries second guarantees every copied checkpoint's entries are in the copy.
     */
    fun snapshot(): Path {
        val copy = Files.createTempDirectory("pam-ledger-snapshot")
        for (name in listOf(Ledger.CHECKPOINTS_FILE, Ledger.ENTRIES_FILE)) {
            val source = directory.resolve(name)
            if (Files.exists(source)) Files.copy(source, copy.resolve(name), StandardCopyOption.REPLACE_EXISTING)
        }
        return copy
    }

    data class AuditResult(val report: LedgerVerifier.Report, val millis: Long, val note: String)

    /** Run the offline verifier, exactly as a regulator would, over a fresh snapshot. */
    fun fullAudit(): AuditResult {
        val started = System.nanoTime()
        // Anchor everything committed so far: unsigned history would (rightly) fail the audit.
        checkpointNow()
        val dir = snapshot()
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
     *  - "truncate": delete the newest five signed entries (a rollback to hide a recent payout).
     */
    fun tamper(mode: String): AuditResult {
        require(mode in setOf("edit", "rewrite", "truncate")) { "mode must be edit, rewrite or truncate" }
        val sth = checkpointNow()
        val dir = snapshot()
        try {
            val path = dir.resolve(Ledger.ENTRIES_FILE)
            val records = ArrayList<ByteArray>()
            Journal.scanReadOnly(path) {
                if (records.size.toLong() < sth.head.treeSize) records += it
            }
            require(records.size.toLong() == sth.head.treeSize) { "snapshot is missing signed entries" }
            // The simulator can append while files are copied. Attack exactly this signed
            // prefix, not an unsigned suffix; discard newer checkpoints from the copy too.
            val checkpointsPath = dir.resolve(Ledger.CHECKPOINTS_FILE)
            val checkpoints = ArrayList<ByteArray>()
            Journal.scanReadOnly(checkpointsPath) {
                if (SignedTreeHead.decode(it).head.treeSize <= sth.head.treeSize) checkpoints += it
            }
            Files.delete(checkpointsPath)
            val (checkpointJournal, _) = Journal.openForAppend(checkpointsPath) {}
            checkpointJournal.use { it.appendBatch(checkpoints) }
            require(records.size >= 6) { "the ledger needs a few more entries first" }
            fun body(r: ByteArray) = r.copyOfRange(0, r.size - 32)

            val note = when (mode) {
                "truncate" -> {
                    repeat(5) { records.removeAt(records.size - 1) }
                    "Deleted the newest 5 signed entries from a copy of the journal, as if to hide recent payouts."
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
