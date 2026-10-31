package com.pam.ledger

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature

/*
 * ============================================================================
 *  SIGNED TREE HEADS (checkpoints)
 * ============================================================================
 *
 *  Hashes alone do not stop an attacker with write access to the disk: they can
 *  alter entry #3 and simply RECOMPUTE every later hash. The result is a
 *  perfectly self-consistent, perfectly fraudulent ledger.
 *
 *  The fix is to periodically SIGN the head of the ledger with a private key the
 *  attacker does not have (ideally held in an HSM or cloud KMS):
 *
 *      STH = Sign( ledgerId, treeSize, merkleRoot, chainHead, timestamp )
 *
 *  Any rewrite of history covered by an STH now changes the root and breaks the
 *  signature check. Publish STHs somewhere outside your control (a regulator, a
 *  transparency log, even a newspaper) and you cannot quietly fork history
 *  either, because you would need two contradictory signed heads.
 *
 *  Ed25519 is used: fast, deterministic (no nonce to get wrong), small 64-byte
 *  signatures, and built into JDK 15+.
 */

/** The statement being signed. */
data class TreeHead(
    val ledgerId: String,
    val treeSize: Long,
    val rootHash: Digest,
    val chainHead: Digest,
    val timestampMicros: Long,
) {
    /** Canonical, domain-separated bytes. The tag stops a signature over some other message being reused here. */
    fun signingBytes(): ByteArray = ByteWriter().apply {
        str("PAM-LEDGER/tree-head/v1")
        str(ledgerId)
        long(treeSize)
        raw(rootHash.rawBytes())
        raw(chainHead.rawBytes())
        long(timestampMicros)
    }.toByteArray()
}

/** A [TreeHead] plus a signature and the id of the key that made it (supports key rotation). */
class SignedTreeHead(val head: TreeHead, val keyId: String, signature: ByteArray) {
    private val sig = signature.copyOf()
    fun signature(): ByteArray = sig.copyOf()

    fun verify(publicKey: PublicKey): Boolean = try {
        Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(head.signingBytes())
            verify(sig)
        }
    } catch (e: Exception) {
        false // malformed signature / wrong key type: simply "not valid"
    }

    internal fun encode(): ByteArray = ByteWriter().apply {
        str(keyId)
        str(head.ledgerId)
        long(head.treeSize)
        raw(head.rootHash.rawBytes())
        raw(head.chainHead.rawBytes())
        long(head.timestampMicros)
        int(sig.size)
        raw(sig)
    }.toByteArray()

    override fun toString() = "STH(size=${head.treeSize}, root=${head.rootHash}, key=$keyId)"

    companion object {
        internal fun decode(bytes: ByteArray): SignedTreeHead {
            val r = ByteReader(bytes)
            val keyId = r.str(128)
            val head = TreeHead(r.str(1024), r.long(), r.digest(), r.digest(), r.long())
            val sigLen = r.int()
            require(sigLen in 1..512) { "bad signature length" }
            val sig = r.bytes(sigLen)
            r.expectEnd()
            return SignedTreeHead(head, keyId, sig)
        }

        fun sign(head: TreeHead, signer: TreeHeadSigner) =
            SignedTreeHead(head, signer.keyId, signer.sign(head.signingBytes()))
    }
}

/**
 * Abstraction over whoever holds the private key. In production implement this
 * against an HSM / AWS KMS / GCP KMS so the key never sits in application memory.
 */
interface TreeHeadSigner {
    val publicKey: PublicKey
    val keyId: String get() = keyIdOf(publicKey)
    fun sign(message: ByteArray): ByteArray
}

/** Short, stable identifier of a public key: first 16 hex chars of SHA-256(encoded key). */
fun keyIdOf(publicKey: PublicKey): String = Hashes.sha256(publicKey.encoded).toHex().take(16)

/** In-memory Ed25519 signer. Fine for tests and demos; use an HSM-backed signer in production. */
class Ed25519TreeHeadSigner(private val keyPair: KeyPair) : TreeHeadSigner {
    override val publicKey: PublicKey get() = keyPair.public

    override fun sign(message: ByteArray): ByteArray =
        // A fresh Signature per call: the JDK class is stateful and not thread-safe.
        Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(message)
            sign()
        }

    companion object {
        fun generate() = Ed25519TreeHeadSigner(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
    }
}
