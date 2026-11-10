package com.pam.ledger

/**
 * Many append-only histories ("chains") sharing one derived file: for example,
 * the entries of each player, kept so that a history costs almost nothing in
 * memory however long it grows.
 *
 * In memory a chain is only its length and a few file positions ("marks"): one
 * per power of two up to its length, so about log2(length) numbers. Everything
 * else is in the file.
 *
 * In the file, element number p of a chain (counting from 1) is stored as its
 * value followed by back-pointers to elements p-1, p-2, p-4 ... p-2^k, where 2^k
 * is the largest power of two dividing p. Most elements carry one or two
 * pointers; the average is two. That gives:
 *
 *   - the latest page:      follow the p-1 pointer, one hop per element;
 *   - any element by number: hops that double, then halve, so about 2*log2(length).
 *
 * The marks are what make appending free of disk reads: mark i is the position
 * of the latest element whose number is a multiple of 2^i, which is exactly the
 * element a new pointer of span 2^i must name.
 *
 * Not thread-safe by itself: the owner appends under a write lock and reads
 * under a read lock, as the read model does.
 */
internal class ChainLog(private val words: FixedLog) {

    /** One history. Holds no values, only its length and marks. */
    class Chain {
        var count = 0L
            internal set
        // marks[i] = word index of the latest element whose number is a multiple of 2^i.
        internal var marks = LongArray(1) { NONE }
    }

    fun append(chain: Chain, value: Long) {
        val number = chain.count + 1
        val spans = java.lang.Long.numberOfTrailingZeros(number) // pointers of span 2^0 .. 2^spans
        val index = words.size
        words.addLong(value)
        for (i in 0..spans) words.addLong(if (number == 1L shl i) NONE else chain.marks[i])
        if (chain.marks.size <= spans) chain.marks = chain.marks.copyOf(spans + 1)
        for (i in 0..spans) chain.marks[i] = index
        chain.count = number
    }

    /** Element [number] of [chain], counting from 1. */
    fun get(chain: Chain, number: Long): Long {
        require(number in 1..chain.count) { "element $number of ${chain.count}" }
        // Start from the mark nearest above the target, then walk down.
        var level = chain.marks.size - 1
        while (level > 0 && ((chain.count ushr level) shl level) < number) level--
        var at = (chain.count ushr level) shl level
        var index = chain.marks[level]
        while (at > number) {
            val span = minOf(java.lang.Long.numberOfTrailingZeros(at), 63 - java.lang.Long.numberOfLeadingZeros(at - number))
            index = words.long(index + 1 + span)
            at -= 1L shl span
        }
        return words.long(index)
    }

    /** Visit values newest first until [visit] returns false. */
    fun walkBack(chain: Chain, visit: (Long) -> Boolean) {
        var index = if (chain.count == 0L) NONE else chain.marks[0]
        while (index != NONE) {
            if (!visit(words.long(index))) return
            index = words.long(index + 1)
        }
    }

    /** The latest [limit] values, oldest first. */
    fun tail(chain: Chain, limit: Int = Int.MAX_VALUE): LongArray {
        require(limit >= 0) { "history limit must be non-negative" }
        val n = minOf(chain.count, limit.toLong()).toInt()
        val values = LongArray(n)
        var filled = n
        walkBack(chain) { value ->
            if (filled == 0) false else { values[--filled] = value; filled > 0 }
        }
        return values
    }

    private companion object {
        const val NONE = -1L
    }
}
