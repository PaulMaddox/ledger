package com.pam.ledger.web

/** Primitive sequence storage. Owners supply synchronization when sharing it between threads. */
internal class LongHistory {
    private val chunks = ArrayList<LongArray>()
    var size = 0
        private set

    fun add(value: Long) {
        check(size < Int.MAX_VALUE) { "history full" }
        val chunk = size / CHUNK_SIZE
        val offset = size % CHUNK_SIZE
        if (chunk == chunks.size) chunks.add(LongArray(8))
        if (offset == chunks[chunk].size) chunks[chunk] = chunks[chunk].copyOf(chunks[chunk].size * 2)
        chunks[chunk][offset] = value
        size++
    }

    operator fun get(index: Int): Long {
        require(index in 0 until size) { "history index out of range" }
        return chunks[index / CHUNK_SIZE][index % CHUNK_SIZE]
    }

    fun tail(limit: Int = Int.MAX_VALUE): List<Long> {
        require(limit >= 0) { "history limit must be non-negative" }
        val count = minOf(size, limit)
        return LongArray(count) { get(size - count + it) }.asList()
    }

    fun sorted(): List<Long> {
        val values = LongArray(size) { get(it) }
        values.sort()
        return values.asList()
    }

    private companion object {
        const val CHUNK_SIZE = 256
    }
}
