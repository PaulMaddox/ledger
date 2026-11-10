package com.pam.ledger

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * ============================================================================
 *  DERIVED STORAGE: indexes on disk, the means to trust them in memory
 * ============================================================================
 *
 *  The journal is the ledger. Everything else the engine needs in order to
 *  answer quickly (where each entry starts, the Merkle tree's nodes, which
 *  idempotency keys exist) is DERIVED from it. Kept on the heap, derived data
 *  grows with history until the heap is full. Kept in files, it does not.
 *
 *  But a file can be altered by anything that can reach the disk, and these
 *  indexes steer real decisions: an idempotency index that wrongly answered
 *  "never seen" would let a transaction commit twice. So derived files follow
 *  three rules:
 *
 *   1. NEVER TRUSTED ACROSS A RESTART. The directory is emptied when the ledger
 *      opens and every file is rebuilt during the full replay that verifies the
 *      journal. Nothing in it is ever loaded in place of that replay.
 *
 *   2. EVERY PAGE IS AUTHENTICATED. A page is stored with an HMAC over its
 *      contents, the file it belongs to and its page number, under a key that
 *      is generated at startup and exists only in this process's memory. A page
 *      that was edited, or moved from another file or position, fails its MAC,
 *      the read throws TamperDetectedException and the ledger stops.
 *
 *   3. EVERY PAGE IS WRITTEN ONCE. Files only grow, a page goes to disk when it
 *      is full, and it is never rewritten. So there is no "earlier valid
 *      version" of a page for an attacker to put back: for each file and page
 *      number, exactly one page was ever given a valid MAC.
 *
 *  Nothing here is fsynced. Losing these files costs nothing, because rule 1
 *  rebuilds them; the journal alone carries durability.
 */

/** Size of the data in one page. Record widths used here (8, 16, 32 bytes) all divide it. */
internal const val PAGE_SIZE = 4096

/**
 * A directory of derived files sharing one in-memory MAC key and one page cache.
 *
 * @param directory emptied on creation and again on [close]; must be used for nothing else
 * @param cachePages how many verified pages are kept in memory across all files
 * @param onFailure told when a page fails authentication or index maintenance fails, so the ledger can fail-stop
 */
internal class DerivedStorage(
    val directory: Path,
    cachePages: Int,
    filterMemoryBytes: Long = 64L * 1024 * 1024,
    val bloomBitsPerKey: Int = 12,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    internal val filters = FilterMemory(filterMemoryBytes)
    internal val filterReads = Executors.newVirtualThreadPerTaskExecutor()
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, MAC_ALGORITHM)
    // A Mac is not thread-safe, and initialising one is the expensive part: keep one per thread.
    private val macs = ThreadLocal.withInitial { Mac.getInstance(MAC_ALGORITHM).apply { init(key) } }
    private val nextId = AtomicLong()
    private val files = ConcurrentHashMap.newKeySet<PagedFile>()
    // Indexes with background threads working on these files; stopped before the files are deleted.
    private val users = ConcurrentHashMap.newKeySet<AutoCloseable>()
    /** Cache-miss reads, observed on the reading thread; internal diagnostics only. */
    @Volatile internal var onPageRead: ((String, Long) -> Unit)? = null

    private class Cached(val file: Long, val page: Long, val bytes: ByteArray)
    // Direct-mapped and lock-free: a page lives in the slot its file and number hash to.
    private val cache = AtomicReferenceArray<Cached?>(Integer.highestOneBit(maxOf(cachePages, 1)))
    private val cacheMask = cache.length() - 1

    init {
        Files.createDirectories(directory)
        clear()
    }

    /** Create a new, empty file. Names are unique within this storage, so a page can never be replayed into a successor. */
    fun create(name: String): PagedFile {
        val id = nextId.getAndIncrement()
        return PagedFile(this, directory.resolve("$name-$id.pages"), id).also(files::add)
    }

    internal fun mac(file: Long, page: Long, data: ByteArray): ByteArray {
        val mac = macs.get()
        mac.update(ByteBuffer.allocate(16).putLong(file).putLong(page).array())
        mac.update(data, 0, PAGE_SIZE)
        return mac.doFinal()
    }

    internal fun cached(file: Long, page: Long): ByteArray? =
        cache[slot(file, page)]?.takeIf { it.file == file && it.page == page }?.bytes

    internal fun remember(file: Long, page: Long, bytes: ByteArray) = cache.set(slot(file, page), Cached(file, page, bytes))

    private fun slot(file: Long, page: Long): Int {
        val h = (file * -0x61c8864680b583ebL) xor (page * -0x4b47d0c2c2b1a7b5L)
        return (h xor (h ushr 32)).toInt() and cacheMask
    }

    internal fun tampered(message: String): Nothing {
        val e = TamperDetectedException(message)
        onFailure(e)
        throw e
    }

    /** Report that work on these files failed (for example a disk error in index maintenance). */
    internal fun failed(cause: Throwable) = onFailure(cause)

    /** Have [user] closed before this storage deletes its files. */
    fun register(user: AutoCloseable) {
        users.add(user)
    }

    fun unregister(user: AutoCloseable) {
        users.remove(user)
    }

    /** Recovery has stopped adding: finish every index's full merge before exposing any ledger. */
    fun finishRecovery() {
        users.filterIsInstance<HashIndex>().forEach { it.finishRecovery() }
    }

    internal fun forget(file: PagedFile) {
        files.remove(file)
    }

    private fun clear() {
        Files.list(directory).use { paths -> paths.filter { it.fileName.toString().endsWith(".pages") }.forEach(Files::delete) }
    }

    /** Close every file and delete them all. Derived data is never kept for the next start. */
    override fun close() {
        users.toList().forEach { runCatching { it.close() } }
        filterReads.shutdown()
        filters.clear()
        files.toList().forEach { runCatching { it.delete() } }
        runCatching { clear() }
        runCatching { Files.deleteIfExists(directory) } // only succeeds if nothing else was put there
    }

    private companion object {
        const val MAC_ALGORITHM = "HmacSHA256"
    }
}

/**
 * A file of fixed-size pages, each written once with its MAC and verified on every
 * read that is not served from the cache.
 *
 * ONE thread appends; any thread reads pages below [pages]. Readers use their own
 * channel, reopened if an interrupted reader closes it (see [JournalReader]).
 */
internal class PagedFile(private val storage: DerivedStorage, private val path: Path, val id: Long) {
    private val writer = FileChannel.open(path, CREATE_NEW, WRITE)
    @Volatile private var reader: FileChannel? = null
    @Volatile private var closed = false
    /** Pages written so far. A page is readable as soon as it is counted here. */
    @Volatile var pages = 0L
        private set

    /** Appending thread only. [data] must hold exactly [PAGE_SIZE] bytes and must not be modified afterwards. */
    fun append(data: ByteArray) {
        require(data.size == PAGE_SIZE)
        val number = pages
        val slot = ByteBuffer.allocate(SLOT)
        slot.put(data).put(storage.mac(id, number, data)).flip()
        var position = number * SLOT
        try {
            while (slot.hasRemaining()) position += writer.write(slot, position)
        } catch (e: IOException) {
            // A derived file that cannot be written cannot be relied on, whoever was writing it (the
            // ledger's writer, index maintenance or a read model). Stop the ledger, unless this file
            // was retired on purpose. The caller must also treat its own state as damaged.
            if (!closed) storage.failed(e)
            throw e
        }
        storage.remember(id, number, data)
        pages = number + 1
    }

    /** Page [number], authenticated. The returned array is shared: callers must not modify it. */
    fun page(number: Long): ByteArray {
        storage.cached(id, number)?.let { return it }
        if (number < 0 || number >= pages) throw IndexOutOfBoundsException("page $number of $pages")
        storage.onPageRead?.invoke(path.fileName.toString(), number)
        val slot = ByteBuffer.allocate(SLOT)
        var attempt = 0
        while (true) {
            val ch = reader ?: open(null)
            try {
                slot.clear()
                while (slot.hasRemaining()) {
                    if (ch.read(slot, number * SLOT + slot.position()) < 0) storage.tampered("derived file ${path.fileName} is shorter than its $pages pages")
                }
                break
            } catch (e: ClosedByInterruptException) {
                throw e
            } catch (e: ClosedChannelException) {
                if (closed) throw RetiredFileException()
                if (++attempt > 3) throw e
                open(ch)
            }
        }
        val data = slot.array().copyOfRange(0, PAGE_SIZE)
        val stored = slot.array().copyOfRange(PAGE_SIZE, SLOT)
        // Constant-time comparison: do not reveal how much of a forged MAC was right.
        if (!MessageDigest.isEqual(stored, storage.mac(id, number, data))) {
            storage.tampered("derived file ${path.fileName} page $number fails authentication")
        }
        storage.remember(id, number, data)
        return data
    }

    private fun open(stale: FileChannel?): FileChannel = synchronized(this) {
        if (closed) throw RetiredFileException()
        val current = reader
        if (current != null && current !== stale) return current
        FileChannel.open(path, READ).also { reader = it }
    }

    /** Close and remove the file. Readers still holding it get [RetiredFileException] and must look again. */
    fun delete() {
        closed = true
        synchronized(this) {
            runCatching { writer.close() }
            runCatching { reader?.close() }
        }
        storage.forget(this)
        Files.deleteIfExists(path)
    }

    private companion object {
        const val TAG_SIZE = 32
        const val SLOT = PAGE_SIZE + TAG_SIZE
    }
}

/** The file was replaced (for example by a merge) while a reader held it. The reader should fetch the current file and retry. */
internal class RetiredFileException : IOException("derived file was retired")

/**
 * Append-only log of fixed-width records over a [PagedFile]: the on-disk
 * counterpart of an array list. ONE writer, lock-free readers.
 *
 * The last, partly filled page stays in memory and goes to the file only when it
 * is full, which is what makes every page on disk write-once. Readers follow the
 * same rule as [AppendOnlyList]: the owner publishes a count through a volatile
 * write after adding, and a reader only asks for indexes below a count it read.
 */
internal class FixedLog(private val file: PagedFile, private val width: Int) {
    private class Tail(val page: Long, val bytes: ByteArray)

    private val perPage = PAGE_SIZE / width
    @Volatile private var tail = Tail(0, ByteArray(PAGE_SIZE))
    var size = 0L // writer only; readers are bounded by the owner's published count
        private set

    init {
        require(PAGE_SIZE % width == 0) { "record width must divide the page size" }
    }

    /** Writer only: append [width] bytes of [record] starting at [offset]. */
    fun add(record: ByteArray, offset: Int = 0) {
        val t = tail
        System.arraycopy(record, offset, t.bytes, (size % perPage).toInt() * width, width)
        size++
        if (size % perPage == 0L) {
            file.append(t.bytes)                       // on disk, with its MAC, before...
            tail = Tail(t.page + 1, ByteArray(PAGE_SIZE)) // ...readers are pointed past it
        }
    }

    fun addLong(value: Long) = add(ByteBuffer.allocate(8).putLong(value).array())

    /** Copy record [index] into [into]. Caller guarantees [index] is below a published count. */
    fun read(index: Long, into: ByteArray, offset: Int = 0) {
        val page = index / perPage
        val t = tail
        // The tail only moves forward, and a page is on disk before the tail leaves it.
        val bytes = if (page == t.page) t.bytes else file.page(page)
        System.arraycopy(bytes, (index % perPage).toInt() * width, into, offset, width)
    }

    fun long(index: Long): Long {
        val page = index / perPage
        val t = tail
        val bytes = if (page == t.page) t.bytes else file.page(page)
        return ByteBuffer.wrap(bytes, (index % perPage).toInt() * width, 8).long
    }
}
