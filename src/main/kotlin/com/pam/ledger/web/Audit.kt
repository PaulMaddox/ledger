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

    private val history = ArrayList<SignedTreeHead>()
    private val poller = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sth-history").apply { isDaemon = true } }

    init {
        // Checkpoints from before this process started are read from the journal file.
        Journal.scanReadOnly(directory.resolve(Ledger.CHECKPOINTS_FILE)) { history += SignedTreeHead.decode(it) }
        poller.scheduleAtFixedRate({ runCatching { record(ledger.latestCheckpoint()) } }, 0, 500, TimeUnit.MILLISECONDS)
    }

    private fun record(sth: SignedTreeHead?) = synchronized(history) {
        if (sth != null && history.none { it.head.treeSize == sth.head.treeSize && it.head.rootHash == sth.head.rootHash }) history += sth
    }

    fun checkpoints(): List<SignedTreeHead> = synchronized(history) { history.toList() }

    /** Sign a checkpoint now and record it. */
    fun checkpointNow(): SignedTreeHead = ledger.checkpoint().also(::record)

    /** A checkpoint covering entry [seq]: the latest one if it does, otherwise a fresh one. */
    fun checkpointCovering(seq: Long): SignedTreeHead {
        val latest = ledger.latestCheckpoint()
        return if (latest != null && latest.head.treeSize > seq) latest else checkpointNow()
    }

    fun checkpointOfSize(size: Long): SignedTreeHead? = synchronized(history) { history.lastOrNull { it.head.treeSize == size } }

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
     *  - "truncate": delete the newest five entries (a rollback to hide a recent payout).
     */
    fun tamper(mode: String): AuditResult {
        require(mode in setOf("edit", "rewrite", "truncate")) { "mode must be edit, rewrite or truncate" }
        ledger.checkpoint() // make sure a signed checkpoint covers everything, as in production
        val dir = snapshot()
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
