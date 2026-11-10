package com.pam.ledger

/**
 * An immutable, blocked Bloom filter belonging to one sorted run. All probes land
 * in one 64-byte block, hence at most one authenticated page is read per lookup.
 * Disk addresses are Longs; only filters that fit the shared budget get heap copies.
 */
internal class RunFilter(
    val file: PagedFile,
    private val blocks: Long,
    private val probes: Int,
) {
    val bytes: Long get() = Math.multiplyExact(file.pages, PAGE_SIZE.toLong())
    @Volatile private var memory: Array<ByteArray>? = null
    val resident: Boolean get() = memory != null

    fun mightContain(hash: Long): Boolean {
        val block = Math.unsignedMultiplyHigh(hash, blocks)
        val page = block / BLOCKS_PER_PAGE
        val bytes = memory?.get(Math.toIntExact(page)) ?: file.page(page)
        val offset = (block % BLOCKS_PER_PAGE).toInt() * BLOCK_BYTES
        var position = hash
        val stride = stride(hash)
        repeat(probes) {
            val bit = (position and 511).toInt()
            if (bytes[offset + bit / 8].toInt() and (1 shl (bit % 8)) == 0) return false
            position += stride
        }
        return true
    }

    fun load() {
        if (memory == null) memory = Array(Math.toIntExact(file.pages)) { file.page(it.toLong()) }
    }

    fun evict() { memory = null }

    /** Sorted hashes select nondecreasing blocks, so even a huge filter needs only one construction page. */
    class Writer(private val file: PagedFile, keys: Long, bitsPerKey: Int) {
        private val blocks = blocksFor(keys, bitsPerKey)
        private val probes = (bitsPerKey * 0.69).toInt().coerceIn(1, 16)
        private var pageNumber = 0L
        private var page = ByteArray(PAGE_SIZE)

        fun add(hash: Long) {
            val block = Math.unsignedMultiplyHigh(hash, blocks)
            val target = block / BLOCKS_PER_PAGE
            check(target >= pageNumber) { "filter hashes must be unsigned-sorted" }
            while (pageNumber < target) append()
            val offset = (block % BLOCKS_PER_PAGE).toInt() * BLOCK_BYTES
            var position = hash
            val stride = stride(hash)
            repeat(probes) {
                val bit = (position and 511).toInt()
                val i = offset + bit / 8
                page[i] = (page[i].toInt() or (1 shl (bit % 8))).toByte()
                position += stride
            }
        }

        private fun append() {
            file.append(page)
            page = ByteArray(PAGE_SIZE)
            pageNumber++
        }

        fun finish(): RunFilter {
            val pages = Math.ceilDiv(blocks, BLOCKS_PER_PAGE)
            while (pageNumber < pages) append()
            return RunFilter(file, blocks, probes)
        }
    }

    companion object {
        private const val BLOCK_BYTES = 64
        private const val BLOCKS_PER_PAGE = (PAGE_SIZE / BLOCK_BYTES).toLong()

        internal fun blocksFor(keys: Long, bitsPerKey: Int): Long {
            require(keys >= 0 && bitsPerKey in 1..64)
            return maxOf(1, Math.ceilDiv(Math.multiplyExact(keys, bitsPerKey.toLong()), 512))
        }

        private fun stride(hash: Long): Long {
            var h = hash xor (hash ushr 33)
            h *= -0xae502812aa7333L
            h = h xor (h ushr 33)
            return h or 1L
        }
    }
}

/**
 * Shared by every index of a ledger. Maintenance alone admits/retire filters;
 * readers only take volatile references, never this lock. Prefer the smallest
 * runs and, at equal size, the newest. Evict before loading, not after.
 */
internal class FilterMemory(private val limit: Long) {
    private val filters = HashSet<RunFilter>()
    @Volatile var usedBytes = 0L
        private set

    @Synchronized
    fun replace(removed: List<RunFilter>, added: RunFilter? = null) {
        removed.forEach { filters.remove(it); it.evict() }
        if (added != null) filters.add(added)
        var remaining = limit
        val chosen = HashSet<RunFilter>()
        for (filter in filters.sortedWith(compareBy<RunFilter> { it.bytes }.thenByDescending { it.file.id })) {
            if (filter.bytes <= remaining && filter.file.pages <= Int.MAX_VALUE) {
                chosen.add(filter)
                remaining -= filter.bytes
            }
        }
        filters.filter { it !in chosen }.forEach { it.evict() }
        chosen.forEach { it.load() }
        usedBytes = limit - remaining
    }

    @Synchronized
    fun clear() {
        filters.forEach { it.evict() }
        filters.clear()
        usedBytes = 0
    }
}
