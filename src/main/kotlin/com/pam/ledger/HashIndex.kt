package com.pam.ledger

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/*
 * ============================================================================
 *  HASH INDEX: "which entries were committed under this key?", mostly on disk
 * ============================================================================
 *
 *  Maps a 64-bit hash of a key to the sequence numbers of entries committed
 *  under keys with that hash. As with KeyIndex, an answer is a CANDIDATE: the
 *  caller reads the entry and compares the full key.
 *
 *  Where the pairs live:
 *
 *    recent pairs      in memory, in a small KeyIndex (the "active" table)
 *    older pairs       on disk, in sorted, immutable files called runs
 *
 *  When the active table is full the writer sets it aside ("frozen") and starts
 *  a new one. A background thread writes each frozen table out as a sorted run,
 *  and merges runs so there are never many. Runs are derived files: write-once,
 *  authenticated, and rebuilt on every start (see Derived.kt).
 *
 *  THE WRITER NEVER TOUCHES THE DISK TO ADD A KEY. Adding is a write to the
 *  in-memory table. The background thread changes only this derived index: it is
 *  not a second ledger writer. It reads entries only to confirm equal-hash keys.
 *
 *  EACH RUN HAS A FILTER, written beside it in one pass. A fixed budget shared
 *  by every index holds the smallest/newest filters in memory; other filters
 *  need one authenticated page per probe. Callers do those reads concurrently.
 *
 *  THE WRITER uses the caller's flush watermark, not disk filters. Current
 *  tables and the last eight flushed tables cover newer commits exactly. An
 *  exceptionally stale caller falls back to a full lookup, never a false absence.
 */

/**
 * @param flushSize pairs held in the active in-memory table before it is written out as a run
 * @param bloomBitsPerKey bits of filter per key at capacity; 0 disables the filter (every lookup searches)
 * @param sameKey confirms full keys for equal-hash pairs during flush/merge, including recovery
 */
internal class HashIndex(
    private val storage: DerivedStorage,
    private val name: String,
    private val flushSize: Int,
    private val bloomBitsPerKey: Int,
    private val sameKey: ((Long, Long) -> Boolean)? = null,
    private val retainedGenerations: Int = KEPT_GENERATIONS,
) : AutoCloseable {

    /** One consistent view of where every pair is. Immutable; replaced by compare-and-set. */
    private class Table(val generation: Long, val keys: KeyIndex = KeyIndex())
    private data class State(
        val active: Table,
        val frozen: List<Table> = emptyList(),
        val runs: List<Run> = emptyList(),
        val kept: List<Table> = emptyList(),
        val flushed: Long = 0,
        val discardedThrough: Long = 0,
    )

    private val state = AtomicReference(State(Table(1)))
    private var activeCount = 0 // writer only
    /** Pairs added so far. Written by the single writer, read by maintenance. */
    @Volatile var count = 0L
        private set

    @Volatile private var compact: CompletableFuture<Unit>? = null
    /** Run searches after the filter accepted a hash; internal measurements only. */
    @Volatile internal var onRunSearch: (() -> Unit)? = null

    /**
     * True only while recovery is the writer: it may wait briefly for maintenance to catch
     * up, which keeps memory bounded when replaying a long journal at full speed. The live
     * writer thread never waits for maintenance.
     */
    @Volatile var mayWait = false

    /**
     * True when tables are waiting for maintenance faster than it writes them out: the derived
     * disk is stalled or slower than the writer. Each waiting table holds [flushSize] pairs in
     * memory, so a ledger stops ADMITTING requests while this is true (callers get
     * OverloadedException and retry with the same key) rather than letting the backlog grow
     * until the heap is gone. The writer itself still never waits: whatever is already queued
     * commits, which adds at most one queue's worth of keys beyond this limit.
     */
    val backlogged: Boolean get() = state.get().frozen.size > MAX_LIVE_BACKLOG

    @Volatile private var closed = false
    @Volatile private var failure: Throwable? = null
    private val work = Semaphore(0)
    private val maintenance = Thread(::maintain, "ledger-index-$name").apply { isDaemon = true; start() }

    init {
        storage.register(this)
    }

    // ------------------------------------------------------------------ writer

    /** Single writer only. Duplicate hashes are kept: each is a separate candidate. */
    fun put(hash: Long, seq: Long) {
        val current = state.get()
        current.active.keys.put(hash, seq)
        count++
        if (++activeCount >= flushSize) {
            // Set the full table aside. Only the list it is filed under changes; its contents stay readable throughout.
            freeze()
        }
        if (mayWait) {
            while (failure == null && !closed && state.get().frozen.size > MAX_BACKLOG) {
                work.release()
                Thread.sleep(1)
            }
        }
        failure?.let { throw it }
    }

    // ----------------------------------------------------------------- readers

    private fun freeze() {
        while (true) {
            val s = state.get()
            if (state.compareAndSet(s, s.copy(active = Table(s.active.generation + 1), frozen = s.frozen + s.active))) break
        }
        activeCount = 0
        work.release()
    }

    /** False only if no pair with [hash] exists. Disk filters are probed on this caller, never the writer. */
    fun mightContain(hash: Long): Boolean = reading { s ->
        inTables(s, hash) { true } || matchingRuns(s, hash, Long.MIN_VALUE).isNotEmpty()
    }

    /** Memory-only candidates, for recovery and the writer's exact post-watermark checks. */
    fun anyInMemory(hash: Long, visit: (Long) -> Boolean): Boolean = inTables(state.get(), hash, visit)

    private fun inTables(s: State, hash: Long, visit: (Long) -> Boolean): Boolean =
        s.active.keys.anyCandidate(hash, visit) || s.frozen.any { it.keys.anyCandidate(hash, visit) }

    /**
     * One snapshot covers every older run. A later commit is in the current tables
     * or a kept flush generation. Merges cannot erase that coverage: kept tables
     * are independent of run layout. Publication replaces tables and runs atomically.
     */
    fun prefetch(hash: Long, visit: (Long) -> Boolean): Long = reading { s ->
        search(s, hash, Long.MIN_VALUE, visit)
        s.flushed
    }

    /** Writer only. A stale watermark is safe but slower; it must never be treated as absence. */
    fun anyCandidateAfter(hash: Long, watermark: Long, visit: (Long) -> Boolean): Boolean {
        val s = state.get()
        if (watermark < s.discardedThrough) return anyCandidate(hash, visit)
        return inTables(s, hash, visit) ||
            s.kept.any { it.generation > watermark && it.keys.anyCandidate(hash, visit) }
    }

    private inline fun <T> reading(read: (State) -> T): T {
        while (true) {
            if (closed) throw LedgerClosedException()
            failure?.let { throw LedgerFailedException(it) }
            try {
                return read(state.get())
            } catch (e: RetiredFileException) {
                if (closed) throw LedgerClosedException()
            } catch (e: IOException) {
                throw IndexUnreadableException(name, e)
            }
        }
    }

    private fun matchingRuns(s: State, hash: Long, minSeq: Long): List<Run> {
        val runs = s.runs.filter { it.maxSeq >= minSeq }
        val disk = runs.filter { it.filter?.resident == false }
        // One virtual thread per disk probe overlaps positional reads without sharing a writer channel.
        if (disk.size <= 1) return runs.filter { it.filter?.mightContain(hash) != false }
        val probes = disk.associateWith { run ->
            CompletableFuture.supplyAsync({ run.filter!!.mightContain(hash) }, storage.filterReads)
        }
        try {
            CompletableFuture.allOf(*probes.values.toTypedArray()).join()
            return runs.filter { probes[it]?.join() ?: (it.filter?.mightContain(hash) != false) }
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
    }

    private fun search(s: State, hash: Long, minSeq: Long, visit: (Long) -> Boolean): Boolean {
        if (inTables(s, hash, visit)) return true
        for (run in matchingRuns(s, hash, minSeq)) {
            onRunSearch?.invoke()
            if (run.anyCandidate(hash, visit)) return true
        }
        return false
    }

    /**
     * Visit candidate sequence numbers for [hash] until [visit] returns true; returns whether it did.
     * Any thread. If a run is merged away mid-search the search starts again, so [visit] may see a
     * candidate twice and must not mind.
     */
    fun anyCandidate(hash: Long, visit: (Long) -> Boolean): Boolean = anyCandidate(hash, Long.MIN_VALUE, visit)

    /**
     * As [anyCandidate], for a caller that only wants sequence numbers of at least [minSeq]
     * (for example the settlement of a bet placed at a known entry). Runs written wholly
     * before [minSeq] are skipped without being read, so a search for something recent
     * touches only recent files. [visit] may still be offered smaller numbers and must check.
     */
    fun anyCandidate(hash: Long, minSeq: Long, visit: (Long) -> Boolean): Boolean =
        reading { search(it, hash, minSeq, visit) }

    /** Visit every pair. May visit a pair more than once if a merge completes meanwhile. Any thread. */
    fun forEach(visit: (Long, Long) -> Unit) {
        while (true) {
            val s = state.get()
            try {
                s.active.keys.forEach(visit)
                s.frozen.forEach { it.keys.forEach(visit) }
                s.runs.forEach { it.forEach(visit) }
                return
            } catch (e: RetiredFileException) {
                if (closed) throw LedgerClosedException() // as in anyCandidate
                continue
            }
        }
    }

    /** Runs on disk right now; for tests and diagnostics. */
    val runCount: Int get() = state.get().runs.size

    /** Recovery writer only, after its last put. Full compaction verifies all equal-hash groups before open returns. */
    fun finishRecovery() {
        if (closed) throw LedgerClosedException()
        failure?.let { throw it }
        if (activeCount > 0) freeze()
        val done = CompletableFuture<Unit>()
        compact = done
        if (closed) done.completeExceptionally(LedgerClosedException())
        failure?.let { done.completeExceptionally(it) }
        work.release()
        try {
            done.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
        failure?.let { throw it }
    }

    // ------------------------------------------------------------- maintenance

    private fun maintain() {
        try {
            // Flushing comes first, but never every time: under a sustained load the frozen list may
            // never empty. When both kinds of work are waiting they alternate.
            var flushedLast = false
            while (!closed) {
                // Observing a compaction request must precede the state snapshot:
                // that request publishes the recovery writer's final freeze.
                val request = compact
                val s = state.get()
                val inputs = mergeable(s.runs)
                when {
                    s.frozen.isNotEmpty() && !(flushedLast && inputs != null) -> { flush(s.frozen.first()); flushedLast = true }
                    inputs != null -> { merge(inputs); flushedLast = false }
                    request != null && s.runs.size > 1 -> { merge(s.runs); flushedLast = false }
                    request != null -> { compact = null; request.complete(Unit) }
                    else -> work.tryAcquire(100, TimeUnit.MILLISECONDS)
                }
            }
        } catch (t: Throwable) {
            if (closed) return // close() retired a file under it; the files are about to be deleted anyway
            failure = t
            try {
                storage.failed(t) // an index that cannot be maintained cannot be relied on
            } finally {
                compact?.completeExceptionally(t)
            }
        }
    }

    /** Write one frozen table out as a sorted run, then swap it for the run in a single step. */
    private fun flush(table: Table) {
        var n = 0
        table.keys.forEach { _, _ -> n++ }
        val hashes = LongArray(n)
        val seqs = LongArray(n)
        var i = 0
        table.keys.forEach { hash, seq -> hashes[i] = hash; seqs[i] = seq; i++ }
        sortPairs(hashes, seqs, 0, n - 1)
        val writer = RunWriter(n.toLong())
        for (j in 0 until n) {
            if (closed) { writer.abandon(); return } // abandoned between records, never mid-write; see close()
            writer.add(hashes[j], seqs[j])
        }
        val run = writer.finish(seqs.maxOrNull() ?: -1)
        while (true) {
            val s = state.get()
            val retained = s.kept + table
            val dropped = retained.dropLast(retainedGenerations)
            if (state.compareAndSet(s, s.copy(
                    frozen = s.frozen - table, runs = s.runs + run,
                    kept = retained.takeLast(retainedGenerations), flushed = table.generation,
                    discardedThrough = dropped.lastOrNull()?.generation ?: s.discardedThrough,
                ))) break
        }
    }

    /** Runs of similar size are merged once there are [FAN_IN] of them, so the number of runs grows only with log(pairs). */
    private fun mergeable(runs: List<Run>): List<Run>? =
        runs.groupBy { tier(it.records) }.values.firstOrNull { it.size >= FAN_IN }?.take(FAN_IN)

    private fun tier(records: Long): Int {
        var t = 0
        var size = records / flushSize
        while (size >= FAN_IN) { size /= FAN_IN; t++ }
        return t
    }

    private fun merge(inputs: List<Run>) {
        val writer = RunWriter(inputs.sumOf { it.records })
        val cursors = inputs.map { it.cursor() }
        while (true) {
            if (closed) { writer.abandon(); return } // as in flush
            var least: Run.Cursor? = null
            for (c in cursors) if (c.valid && (least == null || c.before(least))) least = c
            if (least == null) break
            writer.add(least.hash, least.seq)
            least.advance()
        }
        val merged = writer.finish(inputs.maxOf { it.maxSeq })
        while (true) {
            val s = state.get()
            if (state.compareAndSet(s, s.copy(runs = s.runs - inputs.toSet() + merged))) break
        }
        // Only now, with no state naming them, are the inputs removed. A reader still inside one starts again.
        storage.filters.replace(inputs.mapNotNull { it.filter })
        inputs.forEach { it.delete() }
    }

    override fun close() {
        if (closed) return
        closed = true
        storage.unregister(this)
        // Never interrupt maintenance: Java closes a channel when a thread blocked in it is interrupted,
        // and a derived write that fails stops the ledger. Signal it instead, and let the operation in
        // hand finish; it checks [closed] before starting another.
        work.release()
        maintenance.join(MAINTENANCE_JOIN_MILLIS)
        // Deleting a file marks it retired first, so a write that is somehow still running ends
        // without being mistaken for a disk failure.
        compact?.completeExceptionally(LedgerClosedException())
        val runs = state.get().runs
        storage.filters.replace(runs.mapNotNull { it.filter })
        runs.forEach { runCatching { it.delete() } }
    }

    // ------------------------------------------------------------------- parts

    /** A sorted, immutable file of (hash, sequence) pairs: 16 bytes each, ordered by unsigned hash, then sequence. */
    private class Run(val file: PagedFile, val records: Long, val maxSeq: Long, val filter: RunFilter?) {

        fun delete() { file.delete(); filter?.file?.delete() }

        private fun hashAt(index: Long): Long = ByteBuffer.wrap(file.page(index / PER_PAGE), (index % PER_PAGE).toInt() * PAIR, 8).long
        private fun seqAt(index: Long): Long = ByteBuffer.wrap(file.page(index / PER_PAGE), (index % PER_PAGE).toInt() * PAIR + 8, 8).long

        fun anyCandidate(hash: Long, visit: (Long) -> Boolean): Boolean {
            // Find the first index whose hash is >= the target. Hashes are uniform, so guessing the
            // position by proportion usually lands on the right page at once; every other step
            // halves the range instead, which bounds the search even if the guesses are poor.
            var lo = 0L
            var hi = records
            var loHash = 0L       // unsigned lower bound on hashes at indexes >= lo
            var hiHash = -1L      // unsigned upper bound (all ones)
            var step = 0
            while (lo < hi) {
                val mid = if (step++ % 2 == 0 && java.lang.Long.compareUnsigned(hiHash, loHash) > 0) {
                    val fraction = unsignedToDouble(hash - loHash) / unsignedToDouble(hiHash - loHash)
                    (lo + (fraction * (hi - lo)).toLong()).coerceIn(lo, hi - 1)
                } else lo + (hi - lo) / 2
                val h = hashAt(mid)
                if (java.lang.Long.compareUnsigned(h, hash) < 0) { lo = mid + 1; loHash = h } else { hi = mid; hiHash = h }
            }
            var i = lo
            while (i < records && hashAt(i) == hash) {
                if (visit(seqAt(i))) return true
                i++
            }
            return false
        }

        fun forEach(visit: (Long, Long) -> Unit) {
            val c = cursor()
            while (c.valid) { visit(c.hash, c.seq); c.advance() }
        }

        fun cursor() = Cursor()

        inner class Cursor {
            private var index = 0L
            var hash = 0L; private set
            var seq = 0L; private set
            val valid get() = index < records

            init { load() }

            private fun load() {
                if (index < records) { hash = hashAt(index); seq = seqAt(index) }
            }
            fun advance() { index++; load() }
            fun before(other: Cursor): Boolean {
                val c = java.lang.Long.compareUnsigned(hash, other.hash)
                return c < 0 || (c == 0 && seq < other.seq)
            }
        }
    }

    /** Fills pages in order and hands each to the file once, when full: the pages of a run are write-once. */
    private inner class RunWriter(expectedRecords: Long) {
        private val file = storage.create("$name-run")
        private val filterFile = if (bloomBitsPerKey > 0) storage.create("$name-filter") else null
        private val filter = filterFile?.let { RunFilter.Writer(it, expectedRecords, bloomBitsPerKey) }
        private var page = ByteBuffer.allocate(PAGE_SIZE)
        private var records = 0L
        private var previousHash = 0L
        private var equalFrom = 0L

        fun add(hash: Long, seq: Long) {
            if (records == 0L || hash != previousHash) equalFrom = records
            else if (sameKey != null) {
                // A hash match alone is not a duplicate. Compare the whole collision
                // group, not just adjacent keys: different keys can share a hash.
                for (i in equalFrom until records) {
                    val bytes = if (i / PER_PAGE == records / PER_PAGE) page.array() else file.page(i / PER_PAGE)
                    val other = ByteBuffer.wrap(bytes, (i % PER_PAGE).toInt() * PAIR + 8, 8).long
                    if (sameKey.invoke(other, seq)) {
                        throw TamperDetectedException("entry #$seq: duplicate idempotency key (also entry #$other)")
                    }
                }
            }
            previousHash = hash
            filter?.add(hash)
            page.putLong(hash).putLong(seq)
            records++
            if (!page.hasRemaining()) {
                file.append(page.array())
                page = ByteBuffer.allocate(PAGE_SIZE)
            }
        }

        fun finish(maxSeq: Long): Run {
            if (page.position() > 0) file.append(page.array()) // zero padding lies beyond [records] and is never read
            val built = filter?.finish()
            storage.filters.replace(emptyList(), built)
            return Run(file, records, maxSeq, built)
        }

        fun abandon() {
            file.delete()
            filterFile?.delete()
        }
    }

    private companion object {
        const val PAIR = 16
        const val PER_PAGE = (PAGE_SIZE / PAIR).toLong()
        const val FAN_IN = 4
        const val MAX_BACKLOG = 4
        const val KEPT_GENERATIONS = 8
        // Live writers are not slowed for brief stalls; only a sustained one turns requests away.
        const val MAX_LIVE_BACKLOG = 16
        // Long enough for the largest merge to finish on a slow disk.
        const val MAINTENANCE_JOIN_MILLIS = 60_000L

        fun unsignedToDouble(value: Long): Double =
            if (value >= 0) value.toDouble() else (value ushr 1).toDouble() * 2.0

        /** In-place quicksort of pairs by unsigned hash, then sequence. Two parallel arrays avoid an object per pair. */
        fun sortPairs(hashes: LongArray, seqs: LongArray, from: Int, to: Int) {
            var lo = from
            var hi = to
            while (lo < hi) {
                val pivotHash = hashes[(lo + hi) ushr 1]
                val pivotSeq = seqs[(lo + hi) ushr 1]
                var i = lo
                var j = hi
                while (i <= j) {
                    while (less(hashes[i], seqs[i], pivotHash, pivotSeq)) i++
                    while (less(pivotHash, pivotSeq, hashes[j], seqs[j])) j--
                    if (i <= j) {
                        val h = hashes[i]; hashes[i] = hashes[j]; hashes[j] = h
                        val s = seqs[i]; seqs[i] = seqs[j]; seqs[j] = s
                        i++; j--
                    }
                }
                // Recurse into the smaller half and loop on the larger, so the stack stays shallow.
                if (j - lo < hi - i) { sortPairs(hashes, seqs, lo, j); lo = i } else { sortPairs(hashes, seqs, i, hi); hi = j }
            }
        }

        fun less(h1: Long, s1: Long, h2: Long, s2: Long): Boolean {
            val c = java.lang.Long.compareUnsigned(h1, h2)
            return c < 0 || (c == 0 && s1 < s2)
        }
    }
}
