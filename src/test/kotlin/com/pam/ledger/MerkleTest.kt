package com.pam.ledger

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exhaustive Merkle checks for every tree size up to 70, which covers all the
 * awkward unbalanced shapes (sizes 3, 5, 6, 7, 9...).
 */
class MerkleTest {
    private val leaves = List(70) { Hashes.leaf(Random(it).nextBytes(20)) }
    private val prover = MerkleProver { leaves[it.toInt()] }

    @Test
    fun `incremental frontier root equals RFC 9162 recursive root for every size`() {
        val frontier = MerkleFrontier()
        assertEquals(Hashes.EMPTY_ROOT, frontier.root())
        for (n in 1..leaves.size) {
            frontier.append(leaves[n - 1])
            assertEquals(prover.root(n.toLong()), frontier.root(), "size $n")
        }
    }

    @Test
    fun `inclusion proofs verify for every leaf of every tree, and forgeries fail`() {
        for (n in 1L..leaves.size) {
            val root = prover.root(n)
            for (i in 0 until n) {
                val path = prover.inclusionPath(i, n)
                assertTrue(MerkleVerify.inclusion(leaves[i.toInt()], i, n, path, root), "leaf $i of $n")
                // Wrong leaf, wrong index, tampered path: all must fail.
                assertFalse(MerkleVerify.inclusion(Hashes.leaf(byteArrayOf(1)), i, n, path, root))
                if (n > 1) assertFalse(MerkleVerify.inclusion(leaves[i.toInt()], (i + 1) % n, n, path, root))
                if (path.isNotEmpty()) {
                    val bad = path.toMutableList().also { it[0] = Hashes.leaf(byteArrayOf(9)) }
                    assertFalse(MerkleVerify.inclusion(leaves[i.toInt()], i, n, bad, root))
                }
            }
        }
    }

    @Test
    fun `consistency proofs verify for every pair of sizes, and rewritten history fails`() {
        for (n in 1L..leaves.size) {
            for (m in 1L..n) {
                val proof = prover.consistencyProof(m, n)
                assertTrue(MerkleVerify.consistency(m, n, prover.root(m), prover.root(n), proof), "$m -> $n")
                if (m < n) {
                    // A different "old" root (i.e. history was rewritten) must not verify.
                    val fakeOld = Hashes.leaf(byteArrayOf(m.toByte()))
                    assertFalse(MerkleVerify.consistency(m, n, fakeOld, prover.root(n), proof), "forged $m -> $n")
                }
            }
        }
    }
}
