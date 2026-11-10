package com.pam.ledger

import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLongArray
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
 *  not a second ledger writer and never sees entries, balances or the head.
 *
 *  THE BLOOM FILTER keeps the writer off the disk when LOOKING, too. Almost every
 *  transaction has a new key, and proving a key absent would otherwise mean
 *  searching every run. The filter is a bit array in memory, about ten bits per
 *  key, with two possible answers:
 *
 *    "definitely not present"   -> answered from memory, no disk read
 *    "possibly present"         -> search the tables and runs for the real answer
 *
 *  It never says "not present" for a key that is present, so it cannot cause a
 *  duplicate commit. A false "possibly" (about 1 in 100) costs one search.
 */

/**
 * @param flushSize pairs held in the active in-memory table before it is written out as a run
 * @param bloomBitsPerKey bits of filter per key at capacity; 0 disables the filter (every lookup searches)
 */
internal class HashIndex(
    private val storage: DerivedStorage,
    private val name: String,
    private val flushSize: Int,
    private val bloomBitsPerKey: Int,
) : AutoCloseable {

    /** One consistent view of where every pair is. Immutable; replaced by compare-and-set. */
    private class State(val active: KeyIndex, val frozen: List<KeyIndex>, val runs: List<Run>)

    private val state = AtomicReference(State(KeyIndex(), emptyList(), emptyList()))
    private var activeCount = 0 // writer only
    /** Pairs added so far. Written by the single writer, read by maintenance. */
    @Volatile var count = 0L
        private set

    @Volatile private var bloom: Bloom? = if (bloomBitsPerKey > 0) Bloom.forKeys(flushSize.toLong() * 2, bloomBitsPerKey) else null
    // While a larger filter is being filled, the writer adds new keys to both.
    @Volatile private var pendingBloom: Bloom? = null

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
        current.active.put(hash, seq)
        // Table first, filters second, and the pending filter read before the live one: see rebuildBloom.
        val pending = pendingBloom
        val live = bloom
        pending?.add(hash)
        live?.add(hash)
        count++
        if (++activeCount >= flushSize) {
            // Set the full table aside. Only the list it is filed under changes; its contents stay readable throughout.
            while (true) {
                val s = state.get()
                if (state.compareAndSet(s, State(KeyIndex(), s.frozen + s.active, s.runs))) break
            }
            activeCount = 0
            work.release()
        }
        if (mayWait) {
            while (failure == null && !closed && (state.get().frozen.size > MAX_BACKLOG || bloom?.let { count > it.capacity * 4 } == true)) {
                work.release()
                Thread.sleep(1)
            }
        }
        failure?.let { throw LedgerFailedException(it) }
    }

    // ----------------------------------------------------------------- readers

    /** False only if no pair with [hash] exists. Answered from memory. Any thread. */
    fun mightContain(hash: Long): Boolean = bloom?.mightContain(hash) ?: true

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
    fun anyCandidate(hash: Long, minSeq: Long, visit: (Long) -> Boolean): Boolean {
        if (!mightContain(hash)) return false
        while (true) {
            val s = state.get()
            try {
                if (s.active.anyCandidate(hash, visit)) return true
                for (table in s.frozen) if (table.anyCandidate(hash, visit)) return true
                for (run in s.runs) if (run.maxSeq >= minSeq && run.anyCandidate(hash, visit)) return true
                return false
            } catch (e: RetiredFileException) {
                // After close() the runs are deleted and no state replaces them: looking again would spin forever.
                if (closed) throw LedgerClosedException()
                continue // the pairs are all still there, in the state that replaced this one
            }
        }
    }

    /** Visit every pair. May visit a pair more than once if a merge completes meanwhile. Any thread. */
    fun forEach(visit: (Long, Long) -> Unit) {
        while (true) {
            val s = state.get()
            try {
                s.active.forEach(visit)
                s.frozen.forEach { it.forEach(visit) }
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

    // ------------------------------------------------------------- maintenance

    private fun maintain() {
        try {
            // Flushing comes first, but never every time: under a sustained load the frozen list may
            // never empty, and merges and filter growth that waited for it would wait for ever, leaving
            // runs to pile up and the filter to fill. When both kinds of work are waiting they alternate.
            var flushedLast = false
            while (!closed) {
                val s = state.get()
                val bloomNow = bloom
                val inputs = mergeable(s.runs)
                val growFilter = bloomNow != null && count > bloomNow.capacity
                val other = inputs != null || growFilter
                when {
                    s.frozen.isNotEmpty() && !(flushedLast && other) -> { flush(s.frozen.first()); flushedLast = true }
                    inputs != null -> { merge(inputs); flushedLast = false }
                    growFilter -> { rebuildBloom(); flushedLast = false }
                    else -> work.tryAcquire(100, TimeUnit.MILLISECONDS)
                }
            }
        } catch (t: Throwable) {
            if (closed) return // interrupted by close(); the files are about to be deleted anyway
            failure = t
            storage.failed(t) // the ledger fail-stops: an index that cannot be maintained cannot be relied on
        }
    }

    /** Write one frozen table out as a sorted run, then swap it for the run in a single step. */
    private fun flush(table: KeyIndex) {
        var n = 0
        table.forEach { _, _ -> n++ }
        val hashes = LongArray(n)
        val seqs = LongArray(n)
        var i = 0
        table.forEach { hash, seq -> hashes[i] = hash; seqs[i] = seq; i++ }
        sortPairs(hashes, seqs, 0, n - 1)
        val writer = RunWriter(storage.create("$name-run"))
        for (j in 0 until n) writer.add(hashes[j], seqs[j])
        val run = writer.finish(seqs.maxOrNull() ?: -1)
        while (true) {
            val s = state.get()
            if (state.compareAndSet(s, State(s.active, s.frozen - table, s.runs + run))) break
        }
    }

    /** Runs of similar size are merged once there are [FAN_IN] of them, so the number of runs grows only with log(pairs). */
    private fun mergeable(runs: List<Run>): List<Run>? =
        runs.groupBy { tier(it.records) }.values.firstOrNull { it.size >= FAN_IN }

    private fun tier(records: Long): Int {
        var t = 0
        var size = records / flushSize
        while (size >= FAN_IN) { size /= FAN_IN; t++ }
        return t
    }

    private fun merge(inputs: List<Run>) {
        val writer = RunWriter(storage.create("$name-run"))
        val cursors = inputs.map { it.cursor() }
        while (true) {
            var least: Run.Cursor? = null
            for (c in cursors) if (c.valid && (least == null || c.before(least))) least = c
            if (least == null) break
            writer.add(least.hash, least.seq)
            least.advance()
        }
        val merged = writer.finish(inputs.maxOf { it.maxSeq })
        while (true) {
            val s = state.get()
            if (state.compareAndSet(s, State(s.active, s.frozen, s.runs - inputs.toSet() + merged))) break
        }
        // Only now, with no state naming them, are the inputs removed. A reader still inside one starts again.
        inputs.forEach { it.file.delete() }
    }

    /**
     * Replace the filter with one twice as large, without the writer waiting and without ever
     * answering "not present" for a key that is present.
     *
     * The writer adds each key to its table FIRST, then to the pending filter if it sees one,
     * then to the live filter. So for any key: either the writer saw the pending filter and
     * added the key to it, or the key was already in a table when the pending filter was
     * published, and the copy below finds it there.
     */
    private fun rebuildBloom() {
        val larger = Bloom.forKeys(count * 2, bloomBitsPerKey)
        pendingBloom = larger
        forEach { hash, _ -> larger.add(hash) }
        bloom = larger
        pendingBloom = null
    }

    override fun close() {
        if (closed) return
        closed = true
        storage.unregister(this)
        work.release()
        maintenance.interrupt()
        maintenance.join(10_000)
        state.get().runs.forEach { runCatching { it.file.delete() } }
    }

    // ------------------------------------------------------------------- parts

    /** A sorted, immutable file of (hash, sequence) pairs: 16 bytes each, ordered by unsigned hash, then sequence. */
    private class Run(val file: PagedFile, val records: Long, val maxSeq: Long) {

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
    private class RunWriter(private val file: PagedFile) {
        private var page = ByteBuffer.allocate(PAGE_SIZE)
        private var records = 0L

        fun add(hash: Long, seq: Long) {
            page.putLong(hash).putLong(seq)
            records++
            if (!page.hasRemaining()) {
                file.append(page.array())
                page = ByteBuffer.allocate(PAGE_SIZE)
            }
        }

        fun finish(maxSeq: Long): Run {
            if (page.position() > 0) file.append(page.array()) // zero padding lies beyond [records] and is never read
            return Run(file, records, maxSeq)
        }
    }

    /** A Bloom filter over 64-bit hashes. Bits are only ever set, so readers need no coordination. */
    private class Bloom(private val bits: AtomicLongArray, private val probes: Int, val capacity: Long) {
        private val mask = bits.length().toLong() * 64 - 1

        fun add(hash: Long) {
            var position = hash
            val stride = stride(hash)
            repeat(probes) {
                val bit = position and mask
                val word = (bit ushr 6).toInt()
                val flag = 1L shl (bit and 63).toInt()
                // The writer and a rebuild may set bits in the same word, so this is a compare-and-set loop.
                while (true) {
                    val old = bits.get(word)
                    if (old and flag != 0L || bits.compareAndSet(word, old, old or flag)) break
                }
                position += stride
            }
        }

        fun mightContain(hash: Long): Boolean {
            var position = hash
            val stride = stride(hash)
            repeat(probes) {
                val bit = position and mask
                if (bits.get((bit ushr 6).toInt()) and (1L shl (bit and 63).toInt()) == 0L) return false
                position += stride
            }
            return true
        }

        // An odd second hash derived from the first: the standard way to get several probe positions from one hash.
        private fun stride(hash: Long): Long {
            var h = hash xor (hash ushr 33)
            h *= -0xae502812aa7333L
            h = h xor (h ushr 33)
            return h or 1L
        }

        companion object {
            /** A filter with at least [bitsPerKey] bits for each of [keys] keys, rounded up to a power of two. */
            fun forKeys(keys: Long, bitsPerKey: Int): Bloom {
                var words = MIN_WORDS
                while (words.toLong() * 64 < keys * bitsPerKey) {
                    check(words < Int.MAX_VALUE / 2) { "Bloom filter would exceed the largest array" }
                    words *= 2
                }
                // 0.69 * bits per key probes minimises false positives for a filter filled to capacity.
                val probes = (bitsPerKey * 0.69).toInt().coerceIn(1, 16)
                return Bloom(AtomicLongArray(words), probes, words.toLong() * 64 / bitsPerKey)
            }

            private const val MIN_WORDS = 1 shl 10 // 8 KiB
        }
    }

    private companion object {
        const val PAIR = 16
        const val PER_PAGE = (PAGE_SIZE / PAIR).toLong()
        const val FAN_IN = 4
        const val MAX_BACKLOG = 4
        // Live writers are not slowed for brief stalls; only a sustained one turns requests away.
        const val MAX_LIVE_BACKLOG = 16

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
