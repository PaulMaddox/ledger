package com.pam.ledger.web

import com.pam.ledger.Hashes
import java.nio.ByteBuffer

/**
 * Where each bet was placed and settled, for every bet ever made, without keeping
 * a bet ID string or an object per bet.
 *
 * Each bet is five primitives: a 128-bit hash of its ID, the sequence numbers of
 * its placement and settlement entries, and a small code for its market and
 * outcome. The hash is the first sixteen bytes of SHA-256, so two different bet
 * IDs sharing a slot is not a practical possibility and cannot be arranged by
 * choosing IDs. That is sufficient here because this table only serves displays:
 * no money decision reads it.
 *
 * Not thread-safe; the read model guards it with its own lock. The table is split
 * into segments that grow independently, so adding a bet never rehashes them all.
 */
internal class BetTable {
    /** A bet's row. [settledSeq] is -1 and [outcome] is 0 while the bet is open. */
    class Row(val placedSeq: Long, val settledSeq: Long, val market: Int, val outcome: Int)

    private class Segment(capacity: Int) {
        val high = LongArray(capacity)
        val low = LongArray(capacity)
        val placed = LongArray(capacity) { EMPTY }
        val settled = LongArray(capacity)
        val codes = IntArray(capacity)
        val mask = capacity - 1
        var count = 0
    }

    private val segments = Array(SEGMENTS) { Segment(INITIAL_CAPACITY) }
    var size = 0L
        private set

    /** Record a placement. A bet ID placed again replaces the earlier row, as the newest placement defines the bet. */
    fun place(betId: String, placedSeq: Long, market: Int) {
        val (high, low) = hash(betId)
        var segment = segments[segmentOf(high)]
        val existing = find(segment, high, low)
        if (existing < 0 && (segment.count + 1) * 4L > segment.placed.size * 3L) {
            check(segment.placed.size < MAX_CAPACITY) { "bet table segment is full" }
            val grown = Segment(segment.placed.size * 2)
            for (i in segment.placed.indices) if (segment.placed[i] != EMPTY) {
                val slot = free(grown, segment.high[i])
                grown.high[slot] = segment.high[i]; grown.low[slot] = segment.low[i]
                grown.placed[slot] = segment.placed[i]; grown.settled[slot] = segment.settled[i]; grown.codes[slot] = segment.codes[i]
            }
            grown.count = segment.count
            segments[segmentOf(high)] = grown
            segment = grown
        }
        val slot = if (existing >= 0) existing else free(segment, high).also { segment.count++; size++ }
        segment.high[slot] = high; segment.low[slot] = low
        segment.placed[slot] = placedSeq; segment.settled[slot] = -1; segment.codes[slot] = market shl OUTCOME_BITS
    }

    /** Record a settlement and return the row as it was BEFORE, or null if the bet is unknown. */
    fun settle(betId: String, settledSeq: Long, outcome: Int): Row? {
        val (high, low) = hash(betId)
        val segment = segments[segmentOf(high)]
        val slot = find(segment, high, low)
        if (slot < 0) return null
        val before = row(segment, slot)
        segment.settled[slot] = settledSeq
        segment.codes[slot] = (before.market shl OUTCOME_BITS) or outcome
        return before
    }

    operator fun get(betId: String): Row? {
        val (high, low) = hash(betId)
        val segment = segments[segmentOf(high)]
        val slot = find(segment, high, low)
        return if (slot < 0) null else row(segment, slot)
    }

    /** Every row, in no particular order. */
    fun forEach(visit: (Row) -> Unit) {
        for (segment in segments) for (i in segment.placed.indices) if (segment.placed[i] != EMPTY) visit(row(segment, i))
    }

    private fun row(s: Segment, i: Int) = Row(s.placed[i], s.settled[i], s.codes[i] ushr OUTCOME_BITS, s.codes[i] and OUTCOME_MASK)

    private fun find(s: Segment, high: Long, low: Long): Int {
        var i = slotOf(high, s.mask)
        while (s.placed[i] != EMPTY) {
            if (s.high[i] == high && s.low[i] == low) return i
            i = (i + 1) and s.mask
        }
        return -1
    }

    private fun free(s: Segment, high: Long): Int {
        var i = slotOf(high, s.mask)
        while (s.placed[i] != EMPTY) i = (i + 1) and s.mask
        return i
    }

    private fun segmentOf(high: Long) = (high ushr (64 - SEGMENT_BITS)).toInt()
    private fun slotOf(high: Long, mask: Int) = (high xor (high ushr 31)).toInt() and mask

    private fun hash(betId: String): Pair<Long, Long> {
        val digest = ByteBuffer.wrap(Hashes.sha256(TAG, betId.toByteArray(Charsets.UTF_8)).bytes())
        return digest.long to digest.long
    }

    companion object {
        const val OUTCOME_BITS = 3
        const val OUTCOME_MASK = (1 shl OUTCOME_BITS) - 1
        /** Most markets (fixture and selection pairs) the code column can number. */
        const val MAX_MARKETS = 1 shl (31 - OUTCOME_BITS)
        private const val SEGMENT_BITS = 6
        private const val SEGMENTS = 1 shl SEGMENT_BITS
        private const val INITIAL_CAPACITY = 16
        private const val MAX_CAPACITY = 1 shl 30
        private const val EMPTY = -1L // no entry has a negative sequence number
        // Never stored or signed: this hash exists only inside the running process.
        private val TAG = "PAM-LEDGER/bet-table".toByteArray(Charsets.UTF_8)
    }
}
