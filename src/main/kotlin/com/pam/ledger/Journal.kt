package com.pam.ledger

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.zip.CRC32C

/*
 * ============================================================================
 *  JOURNAL: an append-only file of framed records
 * ============================================================================
 *
 *  On disk:   [ len:i32 | crc32c:i32 | payload... ] [ len | crc | payload ] ...
 *
 *  - Records are only ever APPENDED. Nothing is updated in place, ever.
 *  - Every batch ends with fsync (FileChannel.force). A receipt is only handed
 *    to a caller after that fsync returns, so acknowledged == durable.
 *  - CRC32C detects ACCIDENTAL damage (torn writes, bit rot). It is NOT a
 *    security control: an attacker can recompute a CRC. Deliberate tampering
 *    is caught by the hash chain and signed checkpoints one level up.
 *
 *  TORN WRITES. If the machine dies mid-write, the LAST record may be partial.
 *  That record was never acknowledged (its fsync never completed), so it is safe
 *  to drop. But we never DESTROY bytes: the torn tail is copied to a
 *  `.torn-<millis>` quarantine file for forensics before being truncated.
 *
 *  Damage anywhere OTHER than the tail cannot be a torn write. That is
 *  corruption or tampering, and recovery refuses to proceed.
 */
internal class Journal private constructor(
    private val channel: FileChannel,
) : AutoCloseable {

    /** Set once a write fails; afterwards the journal refuses everything (fail-stop). */
    @Volatile private var poisoned: Throwable? = null

    /**
     * Append [records] and fsync, as ONE unit (group commit).
     *
     * Group commit is the trick that makes a single writer fast: fsync costs
     * roughly the same for 1 record or 1,000, so we pay it once per batch.
     */
    fun appendBatch(records: List<ByteArray>) {
        poisoned?.let { throw LedgerFailedException(it) }
        try {
            val total = records.sumOf { HEADER + it.size }
            val buf = ByteBuffer.allocate(total)
            val crc = CRC32C()
            for (r in records) {
                require(r.size in 1..MAX_RECORD) { "record size ${r.size} out of range" }
                crc.reset()
                crc.update(r)
                buf.putInt(r.size).putInt(crc.value.toInt()).put(r)
            }
            buf.flip()
            while (buf.hasRemaining()) channel.write(buf) // write() may be partial; loop until done
            // force(true) also flushes file METADATA (the new length). force(false)
            // can leave the data on disk but the file length stale on some filesystems.
            channel.force(true)
        } catch (t: Throwable) {
            poisoned = t
            throw t
        }
    }

    override fun close() = channel.close()

    /** A positional read like [FileChannel.read]: may return fewer bytes than asked for, or -1 at end of file. */
    fun interface PositionalRead {
        fun read(dst: ByteBuffer, position: Long): Int
    }

    /** Result of scanning a journal. */
    data class ScanResult(val records: Int, val validBytes: Long, val tornTailBytes: Long)

    companion object {
        const val HEADER = 8
        const val MAX_RECORD = 16 * 1024 * 1024

        /**
         * Open (creating if needed) for appending. Every intact record is fed to
         * [onRecord] in order; a torn tail is quarantined then truncated.
         */
        fun openForAppend(path: Path, onRecord: (ByteArray) -> Unit): Pair<Journal, ScanResult> {
            val ch = FileChannel.open(path, CREATE, READ, WRITE)
            try {
                val scan = scan(ch, onRecord)
                if (scan.tornTailBytes > 0) {
                    quarantine(ch, path, scan.validBytes)
                    ch.truncate(scan.validBytes)
                    ch.force(true)
                }
                ch.position(scan.validBytes)
                return Journal(ch) to scan
            } catch (t: Throwable) {
                ch.close()
                throw t
            }
        }

        /** Read-only scan for offline verification. Never modifies anything. */
        fun scanReadOnly(path: Path, onRecord: (ByteArray) -> Unit): ScanResult = scanReadOnly(path, 0L, onRecord)

        /**
         * Read-only scan starting at byte [from], which must be a record boundary (0, or the
         * validBytes of an earlier scan). Lets a reader tail a growing journal, reading each
         * record exactly once. A record still being written appears as a torn tail and is
         * simply picked up by the next scan.
         */
        fun scanReadOnly(path: Path, from: Long, onRecord: (ByteArray) -> Unit): ScanResult {
            if (!Files.exists(path)) return ScanResult(0, from, 0)
            return FileChannel.open(path, READ).use { scan(it, onRecord, from) }
        }

        /** Default read-ahead: one channel read supplies many framed records, yet memory stays flat. */
        const val READ_AHEAD = 256 * 1024

        private fun scan(ch: FileChannel, onRecord: (ByteArray) -> Unit, start: Long = 0L): ScanResult =
            scan(ch.size(), start, READ_AHEAD, PositionalRead { dst, position -> ch.read(dst, position) }, onRecord)

        /**
         * The scanner proper. It classifies exactly the first [size] bytes of the file, and never
         * reads beyond them: bytes appended while the scan runs cannot change which bytes it claims
         * to have validated, and an incomplete record is simply picked up by a later scan.
         *
         * Reads go through a read-ahead buffer of [readAhead] bytes so that one [read] call can
         * supply many records. The buffer only changes how bytes arrive. Every length limit, CRC32C
         * check, torn-tail versus corruption decision and callback happens exactly as if each header
         * and payload had been read separately. A record too big for the buffer is read straight into
         * its own array, after its length and its fit inside the prefix have been validated.
         *
         * Each payload handed to [onRecord] is a fresh array that the callback owns; buffer storage
         * is never exposed or reused.
         */
        internal fun scan(size: Long, start: Long, readAhead: Int, read: PositionalRead, onRecord: (ByteArray) -> Unit): ScanResult {
            require(readAhead >= HEADER) { "read-ahead $readAhead is smaller than a record header" }
            var pos = start
            var count = 0
            val ahead = ReadAhead(read, size, start, readAhead)
            val crc = CRC32C()
            while (pos < size) {
                val remaining = size - pos
                if (remaining < HEADER) return ScanResult(count, pos, remaining) // partial header: torn tail

                ahead.fill(HEADER)
                val len = ahead.int(0)
                val expectedCrc = ahead.int(4)

                if (len == 0 && allZero(read, pos, size)) {
                    // Some filesystems extend a file with zeros before the data lands. Torn tail.
                    return ScanResult(count, pos, remaining)
                }
                if (len <= 0 || len > MAX_RECORD) {
                    throw TamperDetectedException("journal corrupt at byte $pos: impossible record length $len")
                }
                val end = pos + HEADER + len
                if (end > size) return ScanResult(count, pos, remaining) // record runs past EOF: torn tail

                // Only now, with len validated and the whole record known to lie inside the prefix,
                // is a payload array allocated.
                val payload: ByteArray
                if (HEADER + len <= readAhead) {
                    ahead.fill(HEADER + len)
                    crc.reset()
                    ahead.updateCrc(crc, HEADER, len)
                    if (crc.value.toInt() != expectedCrc) return crcFailure(end, size, count, pos, remaining)
                    payload = ahead.copy(HEADER, len)
                    ahead.consume(HEADER + len)
                } else {
                    payload = ahead.takeOversized(len)
                    crc.reset()
                    crc.update(payload)
                    if (crc.value.toInt() != expectedCrc) return crcFailure(end, size, count, pos, remaining)
                }
                onRecord(payload)
                count++
                pos = end
            }
            return ScanResult(count, pos, 0)
        }

        /** A bad CRC on the very last record is a torn write. Anywhere else it is corruption. */
        private fun crcFailure(end: Long, size: Long, count: Int, pos: Long, remaining: Long): ScanResult {
            if (end == size) return ScanResult(count, pos, remaining)
            throw TamperDetectedException("journal corrupt at byte $pos: CRC mismatch in a non-final record")
        }

        private fun readFully(read: PositionalRead, buf: ByteBuffer, position: Long) {
            var p = position
            while (buf.hasRemaining()) {
                val n = read.read(buf, p)
                if (n < 0) throw java.io.EOFException("unexpected end of journal")
                p += n
            }
        }

        /** True if every byte in [from, to) is zero. Reads nothing at or beyond [to]. */
        private fun allZero(read: PositionalRead, from: Long, to: Long): Boolean {
            val buf = ByteBuffer.allocate(8192)
            var p = from
            while (p < to) {
                buf.clear()
                buf.limit(minOf(buf.capacity().toLong(), to - p).toInt())
                val n = read.read(buf, p)
                if (n <= 0) break
                for (i in 0 until n) if (buf.get(i).toInt() != 0) return false
                p += n
            }
            return true
        }

        /**
         * Sequential read-ahead over the first [limit] bytes of a file. [fill] tops the buffer up
         * with as many bytes as fit, in as few reads as the source allows, and tolerates short reads.
         */
        private class ReadAhead(private val source: PositionalRead, private val limit: Long, start: Long, capacity: Int) {
            private val buf = ByteArray(capacity)
            private val wrapper = ByteBuffer.wrap(buf)
            private var r = 0 // next unconsumed byte
            private var w = 0 // end of valid bytes
            private var next = start // file offset of the first byte not yet read into buf

            /** Make at least [n] unconsumed bytes available; the caller guarantees they lie inside the prefix. */
            fun fill(n: Int) {
                if (w - r >= n) return
                if (r > 0) {
                    System.arraycopy(buf, r, buf, 0, w - r)
                    w -= r
                    r = 0
                }
                while (w < n) {
                    val want = minOf((buf.size - w).toLong(), limit - next).toInt()
                    check(want > 0) { "read past the captured journal prefix" }
                    wrapper.limit(w + want).position(w)
                    val got = source.read(wrapper, next)
                    if (got < 0) throw java.io.EOFException("unexpected end of journal")
                    w += got
                    next += got
                }
            }

            /** Big-endian int at [offset] from the next unconsumed byte, as ByteBuffer's default order reads it. */
            fun int(offset: Int): Int {
                val i = r + offset
                return (buf[i].toInt() and 0xFF shl 24) or (buf[i + 1].toInt() and 0xFF shl 16) or
                    (buf[i + 2].toInt() and 0xFF shl 8) or (buf[i + 3].toInt() and 0xFF)
            }

            fun updateCrc(crc: CRC32C, offset: Int, length: Int) = crc.update(buf, r + offset, length)

            fun copy(offset: Int, length: Int): ByteArray = buf.copyOfRange(r + offset, r + offset + length)

            fun consume(n: Int) {
                r += n
            }

            /**
             * The record at the cursor is larger than the buffer: its header and the start of its
             * payload are buffered, and the rest is read straight into the returned array. The record
             * therefore ends beyond everything buffered, so the buffer is left empty.
             */
            fun takeOversized(length: Int): ByteArray {
                val out = ByteArray(length)
                val have = w - r - HEADER
                System.arraycopy(buf, r + HEADER, out, 0, have)
                r = 0
                w = 0
                readFully(source, ByteBuffer.wrap(out, have, length - have), next)
                next += length - have
                return out
            }
        }

        /** Preserve the torn bytes in a side file before truncating. Evidence is never deleted. */
        private fun quarantine(ch: FileChannel, path: Path, from: Long) {
            val target = path.resolveSibling("${path.fileName}.torn-${System.currentTimeMillis()}")
            FileChannel.open(target, CREATE_NEW, WRITE).use { out ->
                ch.transferTo(from, ch.size() - from, out)
                out.force(true)
            }
        }
    }
}

/**
 * OS-level exclusive lock on the ledger directory.
 *
 * In-process concurrency is handled by the single writer thread. But two
 * PROCESSES (say, a deployment that started the new version before stopping
 * the old one) appending to the same file would interleave records and fork
 * the chain. This lock makes the second process fail fast instead.
 */
internal class DirectoryLock private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        channel.close()
    }

    companion object {
        fun acquire(file: Path): DirectoryLock {
            val ch = FileChannel.open(file, CREATE, WRITE)
            val lock = try {
                ch.tryLock()
            } catch (e: OverlappingFileLockException) {
                null // already locked by this same JVM
            }
            if (lock == null) {
                ch.close()
                throw IllegalStateException("ledger at ${file.parent} is already open by another writer")
            }
            return DirectoryLock(ch, lock)
        }
    }
}
