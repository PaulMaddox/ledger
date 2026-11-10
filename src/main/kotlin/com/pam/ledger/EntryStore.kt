package com.pam.ledger

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.zip.CRC32C

/*
 * ============================================================================
 *  ENTRY STORE: history lives on disk, proof of it lives in memory
 * ============================================================================
 *
 *  A ledger that keeps every entry on the heap stops growing when the heap is
 *  full. The journal already holds every entry, so the engine keeps only what it
 *  needs to FIND an entry and to PROVE it is the one that was verified:
 *
 *      per entry:   where its record starts in the journal  (8 bytes)
 *                   its Merkle leaf hash                    (32 bytes)
 *
 *  Even those are not on the heap. They live in derived files whose pages are
 *  authenticated with a key held only in memory (see Derived.kt), so what the
 *  heap holds per entry is nothing at all.
 *
 *  Reading entry #n means: look in a small cache of recent entries; otherwise
 *  read the record at its offset, hash the bytes, and compare with the leaf hash
 *  that recovery verified. The leaf hash is SHA-256 over the canonical bytes, so
 *  a match means these are exactly the bytes that were checked at startup. A
 *  mismatch means the file changed underneath a running ledger: the read fails
 *  with [TamperDetectedException] and the ledger stops accepting writes.
 *
 *  Nothing here changes what is written to the journal. The offsets and hashes
 *  are DERIVED data: recovery rebuilds them from the journal on every start.
 */

/**
 * The Merkle tree's nodes, in derived files: one append-only log per level.
 *
 * Level 0 holds the leaf hashes; level k holds the root of every COMPLETE subtree
 * of 2^k leaves, in order. A complete subtree never changes (the ledger is
 * append-only), so each node is written once, when its last leaf arrives. A proof
 * then reads about log2(n) nodes instead of rehashing leaves, and the writer keeps
 * only the frontier, which is already O(log n).
 *
 * ONE writer adds nodes, before the entry that completes them is published;
 * readers ask only about trees no larger than the published ledger.
 */
internal class MerkleNodes(private val storage: DerivedStorage) {
    private val levels = AtomicReferenceArray<FixedLog?>(64)

    /** Writer only. Nodes of one level must be added in order; [MerkleFrontier] does exactly that. */
    fun add(level: Int, node: Digest) {
        val log = levels[level] ?: FixedLog(storage.create("merkle-$level"), Digest.SIZE).also { levels.set(level, it) }
        log.add(node.rawBytes())
    }

    /** Root of the complete subtree of 2^[level] leaves starting at leaf [index] * 2^[level]. */
    fun node(level: Int, index: Long): Digest {
        val bytes = ByteArray(Digest.SIZE)
        levels[level]!!.read(index, bytes)
        return Digest(bytes)
    }
}

/**
 * Idempotency index that stores no keys: a 64-bit hash of each key mapped to the
 * sequence number of the entry committed under it.
 *
 * A lookup yields CANDIDATE sequence numbers. The caller must read each candidate
 * entry and compare the full key, so two keys that share a hash cost one extra
 * read and can never be mistaken for each other. The hash is the first eight
 * bytes of SHA-256, so keys cannot cheaply be crafted to collide.
 *
 * One writer, lock-free readers. The table is split into [SEGMENTS] independent
 * open-addressing tables so growth rehashes one small segment at a time and the
 * writer never pauses to rehash the whole index.
 */
internal class KeyIndex {
    private class Table(capacity: Int) {
        val hashes = AtomicLongArray(capacity) // 0 = empty slot
        val seqs = AtomicLongArray(capacity)
        val mask = capacity - 1
    }

    private val segments = AtomicReferenceArray<Table>(SEGMENTS).apply { for (i in 0 until SEGMENTS) set(i, Table(INITIAL_CAPACITY)) }
    private val counts = IntArray(SEGMENTS) // writer only

    /** Writer thread only. Duplicate hashes are kept: each is a separate candidate. */
    fun put(hash: Long, seq: Long) {
        val s = segmentOf(hash)
        var table = segments[s]
        if ((counts[s] + 1) * 4L > table.hashes.length() * 3L) {
            check(table.hashes.length() < MAX_CAPACITY) { "idempotency index segment is full" }
            val grown = Table(table.hashes.length() * 2)
            for (i in 0 until table.hashes.length()) {
                val h = table.hashes[i]
                if (h != 0L) insert(grown, h, table.seqs[i])
            }
            // Readers still probing the old table see everything committed before this point,
            // which is all they are entitled to; the new table is complete before it is visible.
            segments[s] = grown
            table = grown
        }
        insert(table, hash, seq)
        counts[s]++
    }

    private fun insert(table: Table, hash: Long, seq: Long) {
        var i = slotOf(hash, table.mask)
        while (table.hashes[i] != 0L) i = (i + 1) and table.mask
        // Sequence first, hash second: a reader that sees the hash is guaranteed to see its sequence.
        table.seqs[i] = seq
        table.hashes[i] = hash
    }

    /** Visit candidate sequence numbers for [hash] until [visit] returns true. Returns whether it did. Any thread. */
    fun anyCandidate(hash: Long, visit: (Long) -> Boolean): Boolean {
        val table = segments[segmentOf(hash)]
        var i = slotOf(hash, table.mask)
        while (true) {
            val h = table.hashes[i]
            if (h == 0L) return false
            if (h == hash && visit(table.seqs[i])) return true
            i = (i + 1) and table.mask
        }
    }

    /** Visit every pair. Complete for pairs added before the call; a table no longer being written to is visited exactly. */
    fun forEach(visit: (Long, Long) -> Unit) {
        for (s in 0 until SEGMENTS) {
            val table = segments[s]
            for (i in 0 until table.hashes.length()) {
                val h = table.hashes[i]
                if (h != 0L) visit(h, table.seqs[i])
            }
        }
    }

    private fun segmentOf(hash: Long) = (hash ushr 56).toInt()
    private fun slotOf(hash: Long, mask: Int) = (hash xor (hash ushr 29)).toInt() and mask

    companion object {
        private const val SEGMENTS = 256
        private const val INITIAL_CAPACITY = 16
        private const val MAX_CAPACITY = 1 shl 30

        /** First eight bytes of SHA-256 over a domain tag and the key. Never zero, which marks an empty slot. */
        fun hashOf(key: String): Long {
            val digest = Hashes.sha256(TAG, key.toByteArray(Charsets.UTF_8)).rawBytes()
            val hash = ByteBuffer.wrap(digest).long
            return if (hash == 0L) 1L else hash
        }

        // Not part of any stored or signed format: this hash never leaves the process.
        private val TAG = "PAM-LEDGER/key-index".toByteArray(Charsets.UTF_8)
    }
}

/**
 * Positional, read-only access to journal records, shared by reader threads.
 *
 * It uses its OWN channel, never the writer's. Java closes a FileChannel when a
 * thread blocked in it is interrupted; with a separate channel, an interrupted
 * reader can never close the file the writer is appending to. If that happens
 * to this channel, the next reader simply reopens it.
 */
internal class JournalReader(private val path: Path) : AutoCloseable {
    // Opened on first use: recovery creates the journal file before anything is read back.
    @Volatile private var channel: FileChannel? = null
    @Volatile private var closed = false

    /** The payload of the record whose header starts at [position]. Throws [TamperDetectedException] if its frame is damaged. */
    fun record(position: Long): ByteArray {
        var attempt = 0
        while (true) {
            val ch = channel ?: open(null)
            try {
                return read(ch, position)
            } catch (e: ClosedByInterruptException) {
                throw e // this thread was interrupted; let it unwind, the next reader reopens
            } catch (e: ClosedChannelException) {
                if (closed || ++attempt > 3) throw e
                open(ch)
            }
        }
    }

    /** Replace [stale] (or open the first channel) unless another reader already has. */
    private fun open(stale: FileChannel?): FileChannel = synchronized(this) {
        if (closed) throw ClosedChannelException()
        val current = channel
        if (current != null && current !== stale) return current
        FileChannel.open(path, READ).also { channel = it }
    }

    private fun read(ch: FileChannel, position: Long): ByteArray {
        // Most records fit in one read; a longer one needs a second for the remainder.
        var buf = ByteBuffer.allocate(FIRST_READ)
        fill(ch, buf, position, Journal.HEADER)
        val len = buf.getInt(0)
        val expectedCrc = buf.getInt(4)
        if (len <= 0 || len > Journal.MAX_RECORD) throw TamperDetectedException("journal record at byte $position has impossible length $len")
        val total = Journal.HEADER + len
        if (buf.position() < total) {
            val whole = ByteBuffer.allocate(total)
            buf.flip()
            whole.put(buf)
            buf = whole
            fill(ch, buf, position, total)
        }
        val payload = ByteArray(len)
        buf.get(Journal.HEADER, payload)
        val crc = CRC32C()
        crc.update(payload)
        if (crc.value.toInt() != expectedCrc) throw TamperDetectedException("journal record at byte $position fails its CRC")
        return payload
    }

    /** Read until [buf] holds at least [atLeast] bytes of the file starting at [position]. */
    private fun fill(ch: FileChannel, buf: ByteBuffer, position: Long, atLeast: Int) {
        while (buf.position() < atLeast) {
            val n = ch.read(buf, position + buf.position())
            if (n < 0) throw TamperDetectedException("journal ends inside the record at byte $position")
        }
    }

    override fun close() {
        closed = true
        synchronized(this) { channel?.close() }
    }

    private companion object {
        const val FIRST_READ = 1024
    }
}

/**
 * Every committed entry, addressable by sequence number, without keeping the
 * entries themselves in memory. See the comment at the top of this file.
 *
 * ONE writer (recovery, then the ledger's writer thread) calls [stage] and
 * [publish]; any thread may read. Readers only ever see published entries, and
 * the writer publishes only after the batch is fsynced.
 *
 * The writer must add each entry's leaf to [nodes] (through the Merkle frontier)
 * before publishing it, because a read proves an entry against that leaf.
 *
 * @param onTamper told when a record read back from disk no longer matches the
 *   hash verified at startup, so the ledger can fail-stop.
 */
internal class EntryStore(
    path: Path,
    storage: DerivedStorage,
    cacheSize: Int,
    private val strings: EntryStrings,
    private val onTamper: (TamperDetectedException) -> Unit,
) : AutoCloseable {
    private val offsets = FixedLog(storage.create("offsets"), 8)
    /** The Merkle tree over all staged entries. Level 0 is the leaf hash of each entry. */
    val nodes = MerkleNodes(storage)
    private val reader = JournalReader(path)
    @Volatile private var published = 0L

    // Direct-mapped: entry n lives in slot n mod size. Bounded, lock-free, and a
    // newly committed entry always displaces an older one.
    private val cache = AtomicReferenceArray<SealedEntry?>(Integer.highestOneBit(maxOf(cacheSize, 1)))
    private val cacheMask = (cache.length() - 1).toLong()

    val size: Long get() = published

    /** Writer only: record where the next entry's record starts. Invisible until [publish]. */
    fun stage(offset: Long) = offsets.addLong(offset)

    /** Writer only: keep a just-sealed entry so its first readers (receipts, the read model) need no disk read. */
    fun remember(sealed: SealedEntry) = cache.set((sealed.entry.seq and cacheMask).toInt(), sealed)

    /** Writer only: make everything staged so far visible to readers. */
    fun publish() {
        published = offsets.size
    }

    fun leafHash(seq: Long): Digest {
        val n = published // volatile read FIRST: establishes happens-before with the writer
        if (seq < 0 || seq >= n) throw IndexOutOfBoundsException("entry $seq, size $n")
        return nodes.node(0, seq)
    }

    operator fun get(seq: Long): SealedEntry {
        val n = published
        if (seq < 0 || seq >= n) throw IndexOutOfBoundsException("entry $seq, size $n")
        val slot = (seq and cacheMask).toInt()
        cache[slot]?.let { if (it.entry.seq == seq) return it }
        return load(seq).also { cache.set(slot, it) }
    }

    /**
     * Visit entries [from] (inclusive) to [until] (exclusive) in order, each verified like any
     * other read. For readers of long ranges: entries read here do not displace the cache of
     * recent entries that customer requests rely on.
     */
    fun scan(from: Long, until: Long, visit: (SealedEntry) -> Unit) {
        val n = published
        if (from < 0 || until > n || from > until) throw IndexOutOfBoundsException("entries $from..$until, size $n")
        for (seq in from until until) {
            val cached = cache[(seq and cacheMask).toInt()]
            visit(if (cached != null && cached.entry.seq == seq) cached else load(seq))
        }
    }

    /** Read entry [seq] from the journal and prove it is the entry that was verified. */
    private fun load(seq: Long): SealedEntry {
        try {
            val record = reader.record(offsets.long(seq))
            if (record.size <= Digest.SIZE) throw TamperDetectedException("entry #$seq: record too short")
            val body = record.copyOfRange(0, record.size - Digest.SIZE)
            if (Hashes.leaf(body) != nodes.node(0, seq)) {
                throw TamperDetectedException("entry #$seq on disk no longer matches the hash verified at startup")
            }
            // The bytes are exactly those recovery accepted, so they decode; a failure here is still tampering.
            val entry = try {
                EntryCodec.decode(body)
            } catch (e: IllegalArgumentException) {
                throw TamperDetectedException("entry #$seq: undecodable: ${e.message}")
            }
            val sealed = SealedEntry.of(strings.share(entry), body)
            val storedChain = Digest(record.copyOfRange(record.size - Digest.SIZE, record.size))
            if (sealed.chainHash != storedChain) throw TamperDetectedException("entry #$seq: stored chain hash was altered")
            return sealed
        } catch (e: TamperDetectedException) {
            onTamper(e)
            throw e
        } catch (e: IOException) {
            // The journal or an index file could not be read (or this thread was interrupted mid-read).
            throw EntryUnreadableException(seq, e)
        }
    }

    override fun close() = reader.close()
}
