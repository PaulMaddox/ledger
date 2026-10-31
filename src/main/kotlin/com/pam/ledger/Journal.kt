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
        fun scanReadOnly(path: Path, onRecord: (ByteArray) -> Unit): ScanResult {
            if (!Files.exists(path)) return ScanResult(0, 0, 0)
            return FileChannel.open(path, READ).use { scan(it, onRecord) }
        }

        private fun scan(ch: FileChannel, onRecord: (ByteArray) -> Unit): ScanResult {
            val size = ch.size()
            var pos = 0L
            var count = 0
            val header = ByteBuffer.allocate(HEADER)
            val crc = CRC32C()
            while (pos < size) {
                val remaining = size - pos
                if (remaining < HEADER) return ScanResult(count, pos, remaining) // partial header: torn tail

                header.clear()
                readFully(ch, header, pos)
                header.flip()
                val len = header.int
                val expectedCrc = header.int

                if (len == 0 && allZero(ch, pos, size)) {
                    // Some filesystems extend a file with zeros before the data lands. Torn tail.
                    return ScanResult(count, pos, remaining)
                }
                if (len <= 0 || len > MAX_RECORD) {
                    throw TamperDetectedException("journal corrupt at byte $pos: impossible record length $len")
                }
                val end = pos + HEADER + len
                if (end > size) return ScanResult(count, pos, remaining) // record runs past EOF: torn tail

                val payload = ByteBuffer.allocate(len)
                readFully(ch, payload, pos + HEADER)
                crc.reset()
                crc.update(payload.array())
                if (crc.value.toInt() != expectedCrc) {
                    // A bad CRC on the very last record is a torn write. Anywhere else it is corruption.
                    if (end == size) return ScanResult(count, pos, remaining)
                    throw TamperDetectedException("journal corrupt at byte $pos: CRC mismatch in a non-final record")
                }
                onRecord(payload.array())
                count++
                pos = end
            }
            return ScanResult(count, pos, 0)
        }

        private fun readFully(ch: FileChannel, buf: ByteBuffer, position: Long) {
            var p = position
            while (buf.hasRemaining()) {
                val n = ch.read(buf, p)
                if (n < 0) throw java.io.EOFException("unexpected end of journal")
                p += n
            }
        }

        private fun allZero(ch: FileChannel, from: Long, to: Long): Boolean {
            val buf = ByteBuffer.allocate(8192)
            var p = from
            while (p < to) {
                buf.clear()
                val n = ch.read(buf, p)
                if (n <= 0) break
                for (i in 0 until n) if (buf.get(i).toInt() != 0) return false
                p += n
            }
            return true
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
