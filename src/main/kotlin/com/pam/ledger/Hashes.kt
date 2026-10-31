package com.pam.ledger

import java.security.MessageDigest

/**
 * An immutable 32-byte SHA-256 digest.
 *
 * Wrapping the raw ByteArray matters: Kotlin/Java arrays compare by IDENTITY,
 * not content, so `a == b` on two arrays would almost always be false. Digest
 * gives value equality and a readable hex form.
 */
class Digest(bytes: ByteArray) {
    private val b: ByteArray = bytes.copyOf()

    init {
        require(b.size == SIZE) { "a digest is exactly $SIZE bytes, got ${b.size}" }
    }

    fun bytes(): ByteArray = b.copyOf()

    /** Internal zero-copy access for hashing. Callers must never modify the result. */
    internal fun rawBytes(): ByteArray = b

    fun toHex(): String = b.joinToString("") { "%02x".format(it) }

    // MessageDigest.isEqual runs in constant time, which avoids leaking via timing
    // how many leading bytes of a guessed hash were correct.
    override fun equals(other: Any?) = other is Digest && MessageDigest.isEqual(b, other.b)
    override fun hashCode() = b.contentHashCode()
    override fun toString() = toHex().take(16) + "…"

    companion object {
        const val SIZE = 32
        fun fromHex(hex: String): Digest {
            require(hex.length == SIZE * 2) { "expected ${SIZE * 2} hex chars" }
            return Digest(ByteArray(SIZE) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        }
    }
}

/**
 * All hashing in one place, with DOMAIN SEPARATION.
 *
 * Domain separation means each purpose hashes a different prefix, so a value
 * that is valid in one role can never be passed off as valid in another:
 *
 *  - Merkle leaf : SHA-256(0x00 || entry bytes)        (RFC 6962/9162)
 *  - Merkle node : SHA-256(0x01 || left || right)       (RFC 6962/9162)
 *  - Chain link  : SHA-256("PAM-LEDGER/chain/v1" || prevChain || leaf)
 *  - Genesis     : SHA-256("PAM-LEDGER/genesis/v1" || ledgerId)
 *
 * Without the 0x00/0x01 prefixes an attacker could present an interior node
 * as if it were a leaf (a "second-preimage" attack on Merkle trees).
 */
object Hashes {
    // MessageDigest is NOT thread-safe, but creating one per hash is wasteful.
    // One instance per thread gives the best of both.
    private val sha256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    private val LEAF_PREFIX = byteArrayOf(0x00)
    private val NODE_PREFIX = byteArrayOf(0x01)
    private val CHAIN_TAG = "PAM-LEDGER/chain/v1".toByteArray(Charsets.UTF_8)
    private val GENESIS_TAG = "PAM-LEDGER/genesis/v1".toByteArray(Charsets.UTF_8)

    fun sha256(vararg parts: ByteArray): Digest {
        val md = sha256.get()
        md.reset()
        for (p in parts) md.update(p)
        return Digest(md.digest())
    }

    /** Root of an empty Merkle tree, per RFC 9162: the hash of the empty string. */
    val EMPTY_ROOT: Digest = sha256()

    fun leaf(entryBytes: ByteArray): Digest = sha256(LEAF_PREFIX, entryBytes)

    fun node(left: Digest, right: Digest): Digest = sha256(NODE_PREFIX, left.rawBytes(), right.rawBytes())

    fun chain(prev: Digest, leaf: Digest): Digest = sha256(CHAIN_TAG, prev.rawBytes(), leaf.rawBytes())

    /**
     * The "previous hash" of entry #0. Mixing in the ledger id means entries cut
     * from one ledger (say, the EUR staging ledger) can never be spliced into
     * another (production) and still verify.
     */
    fun genesis(ledgerId: String): Digest = sha256(GENESIS_TAG, ledgerId.toByteArray(Charsets.UTF_8))
}
