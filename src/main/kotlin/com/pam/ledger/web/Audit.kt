package com.pam.ledger.web

import com.pam.ledger.Hashes
import com.pam.ledger.Journal
import com.pam.ledger.Ledger
import com.pam.ledger.LedgerVerifier
import com.pam.ledger.SealedEntry
import com.pam.ledger.SignedTreeHead
import com.pam.ledger.EntryCodec
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.PublicKey
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    // Concurrent audit/tamper requests must not multiply full-history working sets.
    private val verificationLock = ReentrantLock()
    private var checkpointOffset = 0L // guarded by `history`
    private val poller = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sth-history").apply { isDaemon = true } }

    init {
        refreshHistory()
        poller.scheduleAtFixedRate({ runCatching { refreshHistory() } }, 0, 250, TimeUnit.MILLISECONDS)
    }

    private fun refreshHistory() = synchronized(history) {
        val additions = ArrayList<SignedTreeHead>()
        val scan = Journal.scanReadOnly(directory.resolve(Ledger.CHECKPOINTS_FILE), checkpointOffset) {
            additions += SignedTreeHead.decode(it)
        }
        history += additions
        checkpointOffset = scan.validBytes
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
    internal fun snapshot(anchor: SignedTreeHead): Path {
        val copy = Files.createTempDirectory("pam-ledger-snapshot")
        try {
            val anchorBytes = anchor.encode()
            val source = directory.resolve(Ledger.CHECKPOINTS_FILE)
            var scannedBytes = 0L
            var prefixEnd = -1L
            Journal.scanReadOnly(source) { record ->
                scannedBytes = Math.addExact(scannedBytes, Journal.HEADER.toLong() + record.size)
                if (record.contentEquals(anchorBytes)) prefixEnd = scannedBytes
            }
            check(prefixEnd >= 0) { "ledger snapshot is missing its signed checkpoint" }
            // Bound by the anchor's position, never by an unverified record's claimed size.
            copyPrefix(source, copy.resolve(Ledger.CHECKPOINTS_FILE), prefixEnd)
            copyEntryPrefix(copy.resolve(Ledger.ENTRIES_FILE), anchor.head.treeSize)
            return copy
        } catch (t: Throwable) {
            copy.toFile().deleteRecursively()
            throw t
        }
    }

    private fun copyEntryPrefix(destination: Path, treeSize: Long) {
        val source = directory.resolve(Ledger.ENTRIES_FILE)
        var copied = 0L
        var prefixBytes = 0L
        Journal.scanReadOnly(source) { record ->
            if (copied < treeSize) {
                prefixBytes = Math.addExact(prefixBytes, Journal.HEADER.toLong() + record.size)
                copied++
            }
        }
        check(copied == treeSize) { "ledger snapshot ended before its signed head" }
        copyPrefix(source, destination, prefixBytes)
    }

    private fun copyPrefix(source: Path, destination: Path, bytes: Long) {
        // A disposable audit copy issues no receipts: flush on close, without journal rewrites or fsyncs.
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(destination, CREATE_NEW, WRITE).use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = bytes
                while (remaining > 0) {
                    val length = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = input.readNBytes(buffer, 0, length)
                    if (read != length) throw EOFException("ledger snapshot ended before its signed prefix")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    data class AuditResult(val report: LedgerVerifier.Report, val millis: Long, val note: String)

    /** Run the offline verifier, exactly as a regulator would, over a fresh snapshot. */
    fun fullAudit(): AuditResult = verificationLock.withLock {
        val started = System.nanoTime()
        // Anchor everything committed so far: unsigned history would (rightly) fail the audit.
        val checkpoint = checkpointNow()
        val dir = snapshot(checkpoint)
        try {
            val report = LedgerVerifier.verify(dir, ledgerId, trustedKeys)
            AuditResult(report, (System.nanoTime() - started) / 1_000_000, "Independent audit of a snapshot of the live ledger.")
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
    fun tamper(mode: String): AuditResult = verificationLock.withLock {
        require(mode in setOf("edit", "rewrite", "truncate")) { "mode must be edit, rewrite or truncate" }
        val checkpoint = checkpointNow() // the tamper must alter history covered by this signed head
        val dir = snapshot(checkpoint)
        try {
            val started = System.nanoTime()
            val note = tamperStorage(dir, ledgerId, mode)
            val report = LedgerVerifier.verify(dir, ledgerId, trustedKeys)
            AuditResult(report, (System.nanoTime() - started) / 1_000_000, "$note (snapshot copy)")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    companion object {
        /** Demo-only attack on a CLOSED ledger, under its directory lock. Never a writer operation. */
        internal fun tamperStorage(dir: Path, ledgerId: String, mode: String): String {
            require(mode in setOf("edit", "rewrite", "truncate")) { "mode must be edit, rewrite or truncate" }
            val path = dir.resolve(Ledger.ENTRIES_FILE)
            val records = ArrayList<ByteArray>()
            Journal.scanReadOnly(path) { records += it }
            require(records.size >= 6) { "the ledger needs a few more entries first" }
            fun body(r: ByteArray) = r.copyOfRange(0, r.size - 32)

            val note = when (mode) {
                "truncate" -> {
                    repeat(5) { records.removeAt(records.size - 1) }
                    "Deleted the newest 5 entries from the journal."
                }
                else -> {
                    // The victim: the most recent deposit outside the newest few entries.
                    val target = (records.size - 3 downTo 0).first { EntryCodec.decode(body(records[it])).kind == "DEPOSIT" }
                    val original = EntryCodec.decode(body(records[target]))
                    val forged = original.copy(postings = original.postings.map { it.copy(amount = Math.multiplyExact(it.amount, 10)) })
                    if (mode == "edit") {
                        // Re-encode the forged entry but keep the stored chain hash: no hashes recomputed.
                        records[target] = EntryCodec.encode(forged) + records[target].copyOfRange(records[target].size - 32, records[target].size)
                        "Inflated deposit #$target tenfold without touching any hashes."
                    } else {
                        // Recompute the chain from the forged entry onwards: internally consistent.
                        var chain = if (target == 0) Hashes.genesis(ledgerId) else SealedEntry.seal(EntryCodec.decode(body(records[target - 1]))).chainHash
                        for (i in target until records.size) {
                            val e = if (i == target) forged else EntryCodec.decode(body(records[i]))
                            val sealed = SealedEntry.seal(e.copy(prevChainHash = chain))
                            records[i] = sealed.journalRecord()
                            chain = sealed.chainHash
                        }
                        "Inflated deposit #$target tenfold and recomputed every later hash."
                    }
                }
            }
            Files.delete(path)
            val (journal, _) = Journal.openForAppend(path) {}
            journal.use { it.appendBatch(records) }
            return note
        }
    }

    override fun close() {
        poller.shutdownNow()
        check(poller.awaitTermination(5, TimeUnit.SECONDS)) { "checkpoint history sampler did not stop" }
    }
}
