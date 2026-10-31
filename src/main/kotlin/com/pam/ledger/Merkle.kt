package com.pam.ledger

import java.util.concurrent.ConcurrentHashMap

/*
 * ============================================================================
 *  MERKLE TREE  (RFC 9162 / Certificate Transparency style)
 * ============================================================================
 *
 *  The hash chain alone proves "nothing changed", but to check ONE entry you
 *  would have to replay every entry before it. A Merkle tree fixes that:
 *
 *                       root
 *                    /        \
 *                 h01          h23            h01 = node(h0, h1)
 *                /   \        /   \
 *              h0    h1     h2    h3          h0  = leaf(entry 0 bytes)
 *
 *  To prove entry 2 is in the tree you only need h3 and h01 (the "audit path"),
 *  i.e. log2(n) hashes. For a ledger of a billion bets that is ~30 hashes.
 *
 *  Two kinds of proof are supported:
 *   - INCLUSION:   "entry #i is in the tree whose root is R"
 *   - CONSISTENCY: "the tree of size m is a PREFIX of the tree of size n",
 *                  i.e. the ledger only ever grew; nothing was rewritten.
 *
 *  Unbalanced sizes follow RFC 9162: the left subtree is always the largest
 *  power of two strictly smaller than n.
 */

/**
 * Incremental Merkle root in O(log n) memory, the "compact range" technique.
 *
 * The tree is kept as a set of PERFECT subtrees, one per set bit of [size].
 * Example, size 6 = 0b110: one subtree of 4 leaves and one of 2 leaves.
 * Appending is exactly like incrementing a binary counter: equal-sized
 * subtrees merge, just as 1 + 1 carries.
 *
 * Used only by the single writer thread, so it needs no synchronisation.
 */
internal class MerkleFrontier {
    // levels[i] = root of the perfect subtree of 2^i leaves, if bit i of size is set.
    private val levels = arrayOfNulls<Digest>(64)
    var size: Long = 0
        private set

    fun append(leaf: Digest) {
        var carry = leaf
        var i = 0
        var s = size
        while (s and 1L == 1L) {          // a subtree of this size already exists...
            carry = Hashes.node(levels[i]!!, carry) // ...so merge (older stuff is on the LEFT)
            levels[i] = null
            s = s ushr 1
            i++
        }
        levels[i] = carry
        size++
    }

    /** Fold the subtrees from smallest (rightmost) to largest (leftmost). Matches RFC 9162 exactly. */
    fun root(): Digest {
        if (size == 0L) return Hashes.EMPTY_ROOT
        var acc: Digest? = null
        for (l in levels) {
            if (l == null) continue
            acc = if (acc == null) l else Hashes.node(l, acc)
        }
        return acc!!
    }
}

/**
 * Builds roots and proofs from the stored leaf hashes.
 *
 * Perfect, aligned subtrees never change once complete (the ledger is
 * append-only!), so their hashes are cached forever. That keeps proof
 * generation close to O(log n) after warm-up instead of rehashing the world.
 *
 * Thread-safe: callers only ever ask about sizes <= the published ledger size,
 * the leaf accessor is safe for concurrent reads, and the cache is concurrent.
 */
internal class MerkleProver(private val leafAt: (Long) -> Digest) {
    private val cache = ConcurrentHashMap<Long, Digest>()

    fun root(size: Long): Digest = if (size == 0L) Hashes.EMPTY_ROOT else subtree(0, size)

    /** Merkle hash of leaves [lo, hi). */
    fun subtree(lo: Long, hi: Long): Digest {
        val n = hi - lo
        if (n == 1L) return leafAt(lo)
        val perfect = (n and (n - 1)) == 0L && lo % n == 0L
        // Only cache bigger subtrees: caching every pair would double memory for little gain.
        val key = if (perfect && n >= 16) (java.lang.Long.numberOfTrailingZeros(n).toLong() shl 56) or (lo / n) else -1L
        if (key >= 0) cache[key]?.let { return it }
        val k = largestPowerOfTwoBelow(n)
        val result = Hashes.node(subtree(lo, lo + k), subtree(lo + k, hi))
        if (key >= 0) cache[key] = result
        return result
    }

    /** RFC 9162 PATH(m, D[0:size]): the sibling hashes from leaf m up to the root. */
    fun inclusionPath(index: Long, size: Long): List<Digest> {
        require(index in 0 until size) { "index $index outside tree of size $size" }
        return path(index, 0, size)
    }

    private fun path(m: Long, lo: Long, hi: Long): MutableList<Digest> {
        val n = hi - lo
        if (n == 1L) return mutableListOf()
        val k = largestPowerOfTwoBelow(n)
        return if (m < k) path(m, lo, lo + k).apply { add(subtree(lo + k, hi)) }
        else path(m - k, lo + k, hi).apply { add(subtree(lo, lo + k)) }
    }

    /** RFC 9162 PROOF(m, D[0:n]): proves the size-m tree is a prefix of the size-n tree. */
    fun consistencyProof(m: Long, n: Long): List<Digest> {
        require(m in 1..n) { "need 1 <= m <= n" }
        return if (m == n) emptyList() else subproof(m, 0, n, true)
    }

    private fun subproof(m: Long, lo: Long, hi: Long, isCompleteOldTree: Boolean): MutableList<Digest> {
        val n = hi - lo
        if (m == n) return if (isCompleteOldTree) mutableListOf() else mutableListOf(subtree(lo, hi))
        val k = largestPowerOfTwoBelow(n)
        return if (m <= k) subproof(m, lo, lo + k, isCompleteOldTree).apply { add(subtree(lo + k, hi)) }
        else subproof(m - k, lo + k, hi, false).apply { add(subtree(lo, lo + k)) }
    }

    private fun largestPowerOfTwoBelow(n: Long): Long = java.lang.Long.highestOneBit(n - 1)
}

/** Everything a third party needs to check that one entry is in a signed tree. */
data class InclusionProof(
    val leafIndex: Long,
    val treeSize: Long,
    val leafHash: Digest,
    val auditPath: List<Digest>,
    val rootHash: Digest,
) {
    fun verify(): Boolean = MerkleVerify.inclusion(leafHash, leafIndex, treeSize, auditPath, rootHash)
}

/**
 * Stateless proof verification (RFC 9162 sections 2.1.3.2 and 2.1.4.2).
 *
 * This code needs NOTHING from the ledger: an auditor, a regulator or a
 * player's app can run it with only the proof and a signed root. That is the
 * whole point: you do not have to trust the operator's servers.
 */
object MerkleVerify {
    fun inclusion(leaf: Digest, index: Long, size: Long, path: List<Digest>, root: Digest): Boolean {
        if (index < 0 || index >= size) return false
        var fn = index       // our position at the current level
        var sn = size - 1    // position of the last node at the current level
        var r = leaf
        for (p in path) {
            if (sn == 0L) return false // proof is longer than the tree is tall
            if (fn and 1L == 1L || fn == sn) {
                // We are a right child (or the lone rightmost node): sibling is on the left.
                r = Hashes.node(p, r)
                // Skip levels where we were promoted without a sibling.
                while (fn and 1L == 0L && fn != 0L) { fn = fn ushr 1; sn = sn ushr 1 }
            } else {
                r = Hashes.node(r, p) // we are a left child: sibling on the right
            }
            fn = fn ushr 1
            sn = sn ushr 1
        }
        return sn == 0L && r == root
    }

    fun consistency(oldSize: Long, newSize: Long, oldRoot: Digest, newRoot: Digest, proof: List<Digest>): Boolean {
        if (oldSize <= 0 || oldSize > newSize) return false
        if (oldSize == newSize) return proof.isEmpty() && oldRoot == newRoot
        if (proof.isEmpty()) return false
        // If the old tree is a perfect power of two, its root is itself a node of the new tree.
        val path = if (oldSize and (oldSize - 1) == 0L) listOf(oldRoot) + proof else proof
        var fn = oldSize - 1
        var sn = newSize - 1
        while (fn and 1L == 1L) { fn = fn ushr 1; sn = sn ushr 1 }
        // We rebuild BOTH roots simultaneously from the same hashes:
        // if both come out right, the old tree must be a prefix of the new one.
        var fr = path[0]
        var sr = path[0]
        for (c in path.drop(1)) {
            if (sn == 0L) return false
            if (fn and 1L == 1L || fn == sn) {
                fr = Hashes.node(c, fr)
                sr = Hashes.node(c, sr)
                while (fn and 1L == 0L && fn != 0L) { fn = fn ushr 1; sn = sn ushr 1 }
            } else {
                sr = Hashes.node(sr, c) // only the NEW tree has nodes to our right
            }
            fn = fn ushr 1
            sn = sn ushr 1
        }
        return fr == oldRoot && sr == newRoot && sn == 0L
    }
}
