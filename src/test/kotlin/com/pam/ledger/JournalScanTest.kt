package com.pam.ledger

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.READ
import java.util.Random
import java.util.zip.CRC32C
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The buffered journal scanner is an I/O optimisation, never a verification shortcut. These tests
 * hold it to the previous scanner (kept below, verbatim apart from where its bytes come from)
 * over generated, truncated and corrupted journals, and check the exact outcome: every callback
 * payload in order, the [Journal.ScanResult], or the exact failure.
 */
class JournalScanTest {

    // ---- the reference: the scanner as it was before read-ahead, one positional read per header and payload ----

    private fun referenceScan(size: Long, start: Long, read: Journal.PositionalRead, onRecord: (ByteArray) -> Unit): Journal.ScanResult {
        fun readFully(buf: ByteBuffer, position: Long) {
            var p = position
            while (buf.hasRemaining()) {
                val n = read.read(buf, p)
                if (n < 0) throw EOFException("unexpected end of journal")
                p += n
            }
        }

        fun allZero(from: Long, to: Long): Boolean {
            val buf = ByteBuffer.allocate(8192)
            var p = from
            while (p < to) {
                buf.clear()
                val n = read.read(buf, p)
                if (n <= 0) break
                for (i in 0 until n) if (buf.get(i).toInt() != 0) return false
                p += n
            }
            return true
        }

        var pos = start
        var count = 0
        val header = ByteBuffer.allocate(Journal.HEADER)
        val crc = CRC32C()
        while (pos < size) {
            val remaining = size - pos
            if (remaining < Journal.HEADER) return Journal.ScanResult(count, pos, remaining)
            header.clear()
            readFully(header, pos)
            header.flip()
            val len = header.int
            val expectedCrc = header.int
            if (len == 0 && allZero(pos, size)) return Journal.ScanResult(count, pos, remaining)
            if (len <= 0 || len > Journal.MAX_RECORD) {
                throw TamperDetectedException("journal corrupt at byte $pos: impossible record length $len")
            }
            val end = pos + Journal.HEADER + len
            if (end > size) return Journal.ScanResult(count, pos, remaining)
            val payload = ByteBuffer.allocate(len)
            readFully(payload, pos + Journal.HEADER)
            crc.reset()
            crc.update(payload.array())
            if (crc.value.toInt() != expectedCrc) {
                if (end == size) return Journal.ScanResult(count, pos, remaining)
                throw TamperDetectedException("journal corrupt at byte $pos: CRC mismatch in a non-final record")
            }
            onRecord(payload.array())
            count++
            pos = end
        }
        return Journal.ScanResult(count, pos, 0)
    }

    // ---- harness ----

    private class Outcome(val records: List<ByteArray>, val result: Journal.ScanResult?, val failure: Throwable?) {
        override fun toString() = "records=${records.size} result=$result failure=${failure?.let { "${it::class.simpleName}: ${it.message}" }}"
    }

    private fun outcomeOf(scan: ((ByteArray) -> Unit) -> Journal.ScanResult): Outcome {
        val records = ArrayList<ByteArray>()
        return try {
            Outcome(records, scan { records += it }, null)
        } catch (t: Throwable) {
            Outcome(records, null, t)
        }
    }

    private fun assertEquivalent(expected: Outcome, actual: Outcome, what: String) {
        assertEquals(expected.result, actual.result, "scan result: $what ($expected vs $actual)")
        assertEquals(expected.failure?.javaClass, actual.failure?.javaClass, "failure class: $what ($expected vs $actual)")
        assertEquals(expected.failure?.message, actual.failure?.message, "failure message: $what")
        assertEquals(expected.records.size, actual.records.size, "callback count: $what")
        for (i in expected.records.indices) assertContentEquals(expected.records[i], actual.records[i], "callback $i: $what")
    }

    private fun source(bytes: ByteArray, maxPerRead: Int = Int.MAX_VALUE) = Journal.PositionalRead { dst, position ->
        if (position >= bytes.size) {
            -1
        } else {
            val n = minOf(dst.remaining().toLong(), bytes.size - position, maxPerRead.toLong()).toInt()
            dst.put(bytes, position.toInt(), n)
            n
        }
    }

    private fun reference(bytes: ByteArray, from: Long = 0L, maxPerRead: Int = Int.MAX_VALUE) =
        outcomeOf { referenceScan(bytes.size.toLong(), from, source(bytes, maxPerRead), it) }

    private fun buffered(bytes: ByteArray, readAhead: Int, from: Long = 0L, maxPerRead: Int = Int.MAX_VALUE) =
        outcomeOf { Journal.scan(bytes.size.toLong(), from, readAhead, source(bytes, maxPerRead), it) }

    /** Buffer sizes that straddle the 8-byte header and typical record sizes, down to the minimum. */
    private val readAheads = intArrayOf(8, 9, 11, 16, 23, 64, 100, 257, 4096, Journal.READ_AHEAD)

    private fun frame(payload: ByteArray, crcOverride: Int? = null): ByteArray {
        val crc = CRC32C().also { it.update(payload) }
        return ByteBuffer.allocate(Journal.HEADER + payload.size)
            .putInt(payload.size).putInt(crcOverride ?: crc.value.toInt()).put(payload).array()
    }

    private fun payload(rnd: Random, size: Int) = ByteArray(size).also { rnd.nextBytes(it) }

    private fun journal(vararg payloads: ByteArray): ByteArray =
        ByteBuffer.allocate(payloads.sumOf { Journal.HEADER + it.size }).apply {
            payloads.forEach { put(frame(it)) }
        }.array()

    private fun boundaries(journal: ByteArray, sizes: List<Int>): List<Long> {
        var p = 0L
        return listOf(0L) + sizes.map { p += Journal.HEADER + it; p }
    }

    private fun checkAll(bytes: ByteArray, what: String, from: Long = 0L) {
        val expected = reference(bytes, from)
        for (ra in readAheads) assertEquivalent(expected, buffered(bytes, ra, from), "$what, read-ahead $ra, from $from")
    }

    // ---- differential tests ----

    private val representativeSizes = listOf(1, 7, 8, 9, 30, 1, 120, 2)
    private val representative: Pair<ByteArray, List<Int>> =
        journal(*representativeSizes.map { payload(Random(1), it) }.toTypedArray()) to representativeSizes

    @Test
    fun `every truncation boundary matches the reference at every buffer size`() {
        val (full, _) = representative
        for (cut in 0..full.size) checkAll(full.copyOf(cut), "truncated to $cut of ${full.size}")
    }

    @Test
    fun `every single-byte corruption matches the reference at every buffer size`() {
        val (full, _) = representative
        for (i in full.indices) {
            for (replacement in intArrayOf(0x00, 0xFF, 0x80, 0x01)) {
                val damaged = full.copyOf()
                if (damaged[i].toInt() and 0xFF == replacement) damaged[i] = (replacement xor 0x55).toByte() else damaged[i] = replacement.toByte()
                checkAll(damaged, "byte $i set to ${damaged[i]}")
            }
        }
    }

    @Test
    fun `corruption of a final record is a torn tail but of an earlier record is rejected, exactly as before`() {
        val rnd = Random(2)
        val a = payload(rnd, 40)
        val b = payload(rnd, 40)
        val badFinal = frame(a) + frame(b, crcOverride = 0x1234)
        val badMiddle = frame(a, crcOverride = 0x1234) + frame(b)
        for (ra in readAheads) {
            val tail = buffered(badFinal, ra)
            assertEquals(Journal.ScanResult(1, 48, 48), tail.result, "final CRC damage is torn, read-ahead $ra")
            assertContentEquals(a, tail.records.single())
            val middle = buffered(badMiddle, ra)
            assertTrue(middle.failure is TamperDetectedException, "non-final CRC damage is corruption, read-ahead $ra")
            assertTrue(middle.records.isEmpty(), "no callback for an unverified record")
        }
        checkAll(badFinal, "bad final crc")
        checkAll(badMiddle, "bad middle crc")
    }

    @Test
    fun `zero tails, impossible lengths and maximum lengths are classified as before`() {
        val rnd = Random(3)
        val base = journal(payload(rnd, 10), payload(rnd, 20))
        for (zeros in 1..40) checkAll(base + ByteArray(zeros), "zero tail of $zeros bytes")
        // A zero length header followed by anything non-zero is corruption, not a zero extension.
        for (extra in 1..20) for (at in 0 until extra) {
            val tail = ByteArray(extra).also { it[at] = 1 }
            checkAll(base + tail, "zeros then a non-zero byte at $at of $extra")
        }
        for (len in intArrayOf(-1, Int.MIN_VALUE, Journal.MAX_RECORD + 1, Int.MAX_VALUE)) {
            val header = ByteBuffer.allocate(8).putInt(len).putInt(0).array()
            checkAll(base + header, "impossible length $len, header only")
            checkAll(base + header + ByteArray(100), "impossible length $len with trailing bytes")
            for (ra in readAheads) assertTrue(buffered(base + header, ra).failure is TamperDetectedException)
        }
        // The largest legal length with the file ending early is a torn tail, and nothing is allocated for it.
        for (stub in intArrayOf(0, 1, 50)) {
            val header = ByteBuffer.allocate(8).putInt(Journal.MAX_RECORD).putInt(0).array()
            checkAll(base + header + ByteArray(stub) { 7 }, "maximum length, $stub payload bytes present")
        }
    }

    @Test
    fun `a maximum-size record is read intact through the default buffer`() {
        val rnd = Random(4)
        val big = payload(rnd, Journal.MAX_RECORD)
        val small = payload(rnd, 25)
        val bytes = journal(small, big, small)
        val expected = reference(bytes)
        assertEquivalent(expected, buffered(bytes, Journal.READ_AHEAD), "maximum record, default buffer")
        assertEquivalent(expected, buffered(bytes, 64), "maximum record, tiny buffer")
        assertEquals(3, expected.result!!.records)
    }

    @Test
    fun `records and headers split across refills and records larger than the buffer match the reference`() {
        val rnd = Random(5)
        val sizes = listOf(1, 3, 5000, 1, 70_000, 12, 256, 2, 255, 1)
        val bytes = journal(*sizes.map { payload(rnd, it) }.toTypedArray())
        for (ra in intArrayOf(8, 9, 15, 16, 17, 255, 256, 257, 1000, 4096, 65_536, Journal.READ_AHEAD)) {
            for (maxPerRead in intArrayOf(Int.MAX_VALUE, 1, 3, 7, 4096)) {
                assertEquivalent(reference(bytes), buffered(bytes, ra, maxPerRead = maxPerRead), "read-ahead $ra, at most $maxPerRead per read")
            }
        }
        // Every cut of a journal whose records straddle a small buffer.
        val small = journal(*listOf(9, 8, 1, 17, 2).map { payload(rnd, it) }.toTypedArray())
        for (cut in 0..small.size) {
            for (ra in intArrayOf(8, 9, 10, 17, 18, 33)) {
                assertEquivalent(reference(small.copyOf(cut)), buffered(small.copyOf(cut), ra, maxPerRead = 2), "cut $cut, read-ahead $ra, short reads")
            }
        }
    }

    @Test
    fun `scans from every record boundary match the reference`() {
        val (full, sizes) = representative
        for (from in boundaries(full, sizes)) {
            checkAll(full, "full journal", from)
            checkAll(full.copyOf(full.size - 3), "torn journal", from)
        }
        // A start at or beyond the end reports no records and no torn bytes.
        for (ra in readAheads) {
            assertEquals(Journal.ScanResult(0, full.size.toLong(), 0), buffered(full, ra, from = full.size.toLong()).result)
            assertEquals(reference(full, from = full.size + 5L).result, buffered(full, ra, from = full.size + 5L).result)
        }
    }

    @Test
    fun `generated journals with random damage match the reference`() {
        val rnd = Random(6)
        repeat(400) { round ->
            val sizes = List(rnd.nextInt(8)) { if (rnd.nextInt(6) == 0) 1 + rnd.nextInt(600) else 1 + rnd.nextInt(40) }
            var bytes = journal(*sizes.map { payload(rnd, it) }.toTypedArray())
            when (rnd.nextInt(5)) {
                0 -> bytes = bytes.copyOf(rnd.nextInt(bytes.size + 1))
                1 -> if (bytes.isNotEmpty()) bytes[rnd.nextInt(bytes.size)] = rnd.nextInt().toByte()
                2 -> bytes += ByteArray(rnd.nextInt(30))
                3 -> bytes += payload(rnd, rnd.nextInt(30))
            }
            val from = boundaries(bytes, sizes).filter { it <= bytes.size }.let { it[rnd.nextInt(it.size)] }
            val maxPerRead = intArrayOf(Int.MAX_VALUE, 1, 5)[rnd.nextInt(3)]
            val expected = reference(bytes, from, maxPerRead)
            for (ra in readAheads) assertEquivalent(expected, buffered(bytes, ra, from, maxPerRead), "round $round, read-ahead $ra")
        }
    }

    // ---- ownership, bounds and read counts ----

    @Test
    fun `callbacks own their payload arrays`() {
        val rnd = Random(7)
        val payloads = List(50) { payload(rnd, 1 + rnd.nextInt(90)) } + payload(rnd, 20_000)
        val bytes = journal(*payloads.toTypedArray())
        for (ra in intArrayOf(8, 64, 1000, 4096)) {
            // Kept untouched, then compared after the scan: no later refill may have rewritten them.
            val kept = ArrayList<ByteArray>()
            Journal.scan(bytes.size.toLong(), 0, ra, source(bytes)) { kept += it }
            assertEquals(payloads.size, kept.size)
            for (i in payloads.indices) {
                assertContentEquals(payloads[i], kept[i], "retained payload $i, read-ahead $ra")
                for (j in 0 until i) assertNotSame(kept[j], kept[i])
            }
            // A callback that scribbles over its payload must not disturb the rest of the scan.
            val seen = ArrayList<ByteArray>()
            val result = Journal.scan(bytes.size.toLong(), 0, ra, source(bytes)) { seen += it.copyOf(); it.fill(0x5A) }
            assertEquals(Journal.ScanResult(payloads.size, bytes.size.toLong(), 0), result)
            for (i in payloads.indices) assertContentEquals(payloads[i], seen[i])
        }
    }

    @Test
    fun `one read supplies many records and no read exceeds the read-ahead`() {
        val rnd = Random(8)
        val records = 20_000
        val bytes = journal(*List(records) { payload(rnd, 40 + rnd.nextInt(80)) }.toTypedArray())
        var reads = 0
        var largest = 0
        val counting = Journal.PositionalRead { dst, position ->
            reads++
            largest = maxOf(largest, dst.remaining())
            source(bytes).read(dst, position)
        }
        val result = Journal.scan(bytes.size.toLong(), 0, Journal.READ_AHEAD, counting) {}
        assertEquals(records, result.records)
        assertTrue(largest <= Journal.READ_AHEAD, "no read may ask for more than the read-ahead, asked $largest")
        assertTrue(reads <= bytes.size / (Journal.READ_AHEAD / 2) + 2, "$reads reads for ${bytes.size} bytes")
        var referenceReads = 0
        val referenceCounting = Journal.PositionalRead { dst, position -> referenceReads++; source(bytes).read(dst, position) }
        referenceScan(bytes.size.toLong(), 0, referenceCounting) {}
        assertTrue(referenceReads >= 2 * records)
        println("journal scan read calls for $records records / ${bytes.size} bytes: reference $referenceReads, buffered $reads")
    }

    @Test
    fun `an oversized length is validated before any payload memory is committed`() {
        val rnd = Random(9)
        val base = journal(payload(rnd, 10))
        val header = ByteBuffer.allocate(8).putInt(Journal.MAX_RECORD).putInt(0).array()
        val bytes = base + header + ByteArray(20)
        val largest = intArrayOf(0)
        val counting = Journal.PositionalRead { dst, position -> largest[0] = maxOf(largest[0], dst.remaining()); source(bytes).read(dst, position) }
        val result = Journal.scan(bytes.size.toLong(), 0, 64, counting) {}
        assertEquals(Journal.ScanResult(1, base.size.toLong(), 28), result)
        assertTrue(largest[0] <= 64, "a claimed length beyond the file must not turn into a read of that size")
    }

    // ---- captured prefix and tailing ----

    /** A source over a real file that, at a chosen read, appends to that file through a second handle. */
    private class GrowingFile(val path: Path, val atRead: Int, val append: ByteArray) {
        var reads = 0
        var furthest = 0L

        fun read(channel: FileChannel) = Journal.PositionalRead { dst, position ->
            if (reads++ == atRead) FileChannel.open(path, APPEND).use { it.write(ByteBuffer.wrap(append)) }
            val n = channel.read(dst, position)
            if (n > 0) furthest = maxOf(furthest, position + n)
            n
        }
    }

    @Test
    fun `appends during a scan cannot change what it claims to have validated`() {
        val rnd = Random(10)
        val first = List(3) { payload(rnd, 30) }
        val fourth = payload(rnd, 55)
        val complete = frame(fourth)
        // Only combinations where the chosen read is reached: a 4096-byte buffer takes the whole file in read 0.
        val cases = listOf(8, 16, 64).flatMap { ra -> (0..2).map { ra to it } } + (4096 to 0)
        for ((ra, atRead) in cases) {
            val dir = TestSupport.tempDir()
            try {
                // A fourth record caught mid-write: its remainder lands while the scan runs.
                val file = dir.resolve("entries.journal")
                val cut = 20
                file.writeBytes(journal(*first.toTypedArray()) + complete.copyOf(cut))
                val committedBytes = (3 * (Journal.HEADER + 30)).toLong()
                val snapshot = file.readBytes()
                val growing = GrowingFile(file, atRead, complete.copyOfRange(cut, complete.size))
                val seen = ArrayList<ByteArray>()
                val result = FileChannel.open(file, READ).use { ch ->
                    Journal.scan(ch.size(), 0, ra, growing.read(ch)) { seen += it }
                }
                assertTrue(growing.reads > atRead, "the append must have happened during the scan (read-ahead $ra, read $atRead)")
                assertEquals(file.readBytes().size, snapshot.size + complete.size - cut, "append landed")
                assertEquals(Journal.ScanResult(3, committedBytes, cut.toLong()), result, "read-ahead $ra, append at read $atRead")
                assertTrue(growing.furthest <= snapshot.size, "no byte beyond the captured prefix may be read")
                assertEquivalent(reference(snapshot), Outcome(seen, result, null), "prefix scan equals scan of the snapshot")
                // Tailing: the next scan, from the previous validBytes, sees the finished record exactly once.
                val tail = ArrayList<ByteArray>()
                val next = Journal.scanReadOnly(file, result.validBytes) { tail += it }
                assertEquals(Journal.ScanResult(1, committedBytes + complete.size, 0), next)
                assertContentEquals(fourth, tail.single())
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `zero extension is judged only within the captured prefix`() {
        val rnd = Random(11)
        val base = journal(payload(rnd, 12))
        val dir = TestSupport.tempDir()
        try {
            val file = dir.resolve("entries.journal")
            file.writeBytes(base + ByteArray(16))
            // After capture, real bytes land where the zero tail was being checked.
            val growing = GrowingFile(file, 0, frame(payload(rnd, 9)))
            val result = FileChannel.open(file, READ).use { ch -> Journal.scan(ch.size(), 0, 8, growing.read(ch)) {} }
            assertEquals(Journal.ScanResult(1, base.size.toLong(), 16), result)
            assertTrue(growing.furthest <= base.size + 16)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---- failure propagation ----

    @Test
    fun `an I-O failure propagates unchanged and is not retried`() {
        val rnd = Random(12)
        val bytes = journal(*List(20) { payload(rnd, 50) }.toTypedArray())
        for (ra in intArrayOf(8, 64, 4096)) for (failAt in 0..4) {
            val boom = IOException("disk error")
            var reads = 0
            val failing = Journal.PositionalRead { dst, position -> if (reads++ == failAt) throw boom else source(bytes).read(dst, position) }
            val outcome = outcomeOf { Journal.scan(bytes.size.toLong(), 0, ra, failing, it) }
            val thrown = outcome.failure
            if (thrown != null) {
                assertSame(boom, thrown, "the original exception, read-ahead $ra failing read $failAt")
                assertEquals(failAt + 1, reads, "no retry after a failed read")
            } else {
                assertTrue(reads <= failAt, "the scan finished before the failing read was reached")
            }
        }
    }

    @Test
    fun `an early end of file is reported, never a silent success`() {
        val rnd = Random(13)
        val bytes = journal(*List(5) { payload(rnd, 50) }.toTypedArray())
        // The journal shrinks under a scan that captured the longer size: the final record cannot be read.
        val shrunk = bytes.copyOf(bytes.size - 10)
        for (ra in intArrayOf(8, 64, 4096)) {
            val ref = outcomeOf { referenceScan(bytes.size.toLong(), 0, source(shrunk), it) }
            val got = outcomeOf { Journal.scan(bytes.size.toLong(), 0, ra, source(shrunk), it) }
            assertTrue(ref.failure is EOFException)
            assertTrue(got.failure is EOFException, "read-ahead $ra: ${got.failure}")
        }
    }

    @Test
    fun `a callback failure propagates and stops the scan`() {
        val rnd = Random(14)
        val bytes = journal(*List(10) { payload(rnd, 20) }.toTypedArray())
        for (ra in intArrayOf(8, 64, 4096)) {
            val boom = IllegalStateException("callback")
            var calls = 0
            val thrown = assertFailsWith<IllegalStateException> {
                Journal.scan(bytes.size.toLong(), 0, ra, source(bytes)) { if (++calls == 4) throw boom }
            }
            assertSame(boom, thrown)
            assertEquals(4, calls)
        }
    }

    @Test
    fun `a read-ahead too small for a header is refused`() {
        assertFailsWith<IllegalArgumentException> { Journal.scan(0, 0, Journal.HEADER - 1, source(ByteArray(0))) {} }
    }

    // ---- recovery on real files ----

    private fun tornTails(rnd: Random): List<Pair<String, ByteArray>> {
        val whole = frame(payload(rnd, 300))
        return listOf(
            "partial header" to whole.copyOf(5),
            "header only" to whole.copyOf(8),
            "partial payload" to whole.copyOf(150),
            "payload one byte short" to whole.copyOf(whole.size - 1),
            "zero extension" to ByteArray(37),
            "bad CRC on the final record" to frame(payload(rnd, 64), crcOverride = 77),
        )
    }

    @Test
    fun `recovery quarantines the exact torn bytes before truncating and leaves a clean journal`() {
        val rnd = Random(15)
        val good = journal(*List(6) { payload(rnd, 10 + 40 * it) }.toTypedArray())
        for ((name, tail) in tornTails(rnd)) {
            val dir = TestSupport.tempDir()
            try {
                val file = dir.resolve("entries.journal")
                file.writeBytes(good + tail)
                val before = file.readBytes()

                // Read-only scan: reports the tail but changes nothing and creates nothing.
                val readOnly = Journal.scanReadOnly(file) {}
                assertEquals(Journal.ScanResult(6, good.size.toLong(), tail.size.toLong()), readOnly, name)
                assertContentEquals(before, file.readBytes(), "$name: read-only scan modified the file")
                assertEquals(listOf("entries.journal"), dir.listDirectoryEntries().map { it.name }, "$name: read-only scan created a file")

                val recovered = ArrayList<ByteArray>()
                val (journal, scan) = Journal.openForAppend(file) { recovered += it }
                journal.close()
                assertEquals(readOnly, scan, name)
                assertEquals(6, recovered.size)
                val quarantine = dir.listDirectoryEntries().single { it.name.startsWith("entries.journal.torn-") }
                assertContentEquals(tail, quarantine.readBytes(), "$name: quarantine must hold the exact torn bytes")
                assertContentEquals(good, file.readBytes(), "$name: journal truncated to the last good record")
                assertEquals(Journal.ScanResult(6, good.size.toLong(), 0), Journal.scanReadOnly(file) {})
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `recovery refuses non-final corruption and leaves the file and directory untouched`() {
        val rnd = Random(16)
        val good = journal(*List(6) { payload(rnd, 30) }.toTypedArray())
        val dir = TestSupport.tempDir()
        try {
            val file = dir.resolve("entries.journal")
            val damaged = good.copyOf().also { it[Journal.HEADER + 3] = (it[Journal.HEADER + 3] + 1).toByte() } // payload of record 0
            file.writeBytes(damaged)
            assertFailsWith<TamperDetectedException> { Journal.openForAppend(file) {} }
            assertContentEquals(damaged, file.readBytes())
            assertEquals(listOf("entries.journal"), dir.listDirectoryEntries().map { it.name })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scanReadOnly of a real file matches the reference across a nonzero offset`() {
        val rnd = Random(17)
        val sizes = listOf(5, 60, 700, 4)
        val bytes = journal(*sizes.map { payload(rnd, it) }.toTypedArray())
        val dir = TestSupport.tempDir()
        try {
            val file = dir.resolve("entries.journal")
            file.writeBytes(bytes + ByteArray(3))
            for (from in boundaries(bytes, sizes)) {
                val real = outcomeOf { Journal.scanReadOnly(file, from, it) }
                val expected = reference(bytes + ByteArray(3), from)
                assertEquivalent(expected, real, "from $from")
            }
            assertEquals(Journal.ScanResult(0, 42, 0), Journal.scanReadOnly(dir.resolve("absent.journal"), 42) {})
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
