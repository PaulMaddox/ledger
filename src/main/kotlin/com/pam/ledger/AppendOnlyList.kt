package com.pam.ledger

/**
 * A list with ONE writer and ANY number of lock-free readers.
 *
 * How it stays safe without locks (the Java Memory Model in one paragraph):
 *
 *   The writer stores new elements, THEN writes the @Volatile [published] count.
 *   A reader reads [published] FIRST, then the elements. The JMM guarantees that
 *   everything the writer did before a volatile write is visible to any thread
 *   that subsequently reads that volatile and sees the new value
 *   ("happens-before"). So a reader that sees published = 10 is guaranteed to
 *   see fully-initialised elements 0..9, and it never looks beyond 9.
 *
 * Elements live in fixed-size chunks so growth never copies the elements
 * themselves, only the small chunk directory.
 *
 * Elements are staged with [add] and become visible to readers only on
 * [publish], which the ledger calls after the batch has been fsync'd.
 */
internal class AppendOnlyList<T : Any> {
    private companion object {
        const val CHUNK_BITS = 12
        const val CHUNK_SIZE = 1 shl CHUNK_BITS // 4096 elements per chunk
        const val MASK = CHUNK_SIZE - 1
    }

    @Volatile private var directory: Array<Array<Any?>?> = arrayOfNulls(16)
    @Volatile private var published: Int = 0
    private var staged: Int = 0 // touched only by the writer thread

    /** Writer thread only. */
    fun add(value: T) {
        val index = staged
        check(index < Int.MAX_VALUE) { "list full" }
        val chunkIndex = index ushr CHUNK_BITS
        var dir = directory
        if (chunkIndex >= dir.size) {
            dir = dir.copyOf(dir.size * 2) // copies chunk REFERENCES only, never elements
            directory = dir
        }
        val chunk = dir[chunkIndex] ?: arrayOfNulls<Any?>(CHUNK_SIZE).also { dir[chunkIndex] = it }
        chunk[index and MASK] = value
        staged = index + 1
    }

    /** Writer thread only: make everything added so far visible to readers. */
    fun publish() {
        published = staged
    }

    val size: Int get() = published

    operator fun get(index: Int): T {
        val n = published // volatile read FIRST: establishes happens-before
        if (index < 0 || index >= n) throw IndexOutOfBoundsException("index $index, size $n")
        @Suppress("UNCHECKED_CAST")
        return directory[index ushr CHUNK_BITS]!![index and MASK] as T
    }
}
