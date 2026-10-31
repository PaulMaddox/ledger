package com.pam.ledger

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.SortedMap
import java.util.TreeMap
import java.util.UUID

/**
 * CANONICAL binary encoding of ledger entries.
 *
 * "Canonical" means: one entry has exactly ONE valid byte representation. That is
 * essential because hashes are computed over bytes. JSON, for instance, is a
 * poor fit: `{"a":1,"b":2}` and `{"b":2,"a":1}` mean the same thing but hash
 * differently, which invites arguments with auditors.
 *
 * Layout (big-endian; every string is int32 length + UTF-8 bytes):
 *
 *   magic "PAML" | version u8 | seq i64 | timestamp i64 | prevChainHash [32]
 *   txId (msb i64, lsb i64) | idempotencyKey str
 *   kind str | postingCount i32 | { account str, currency str, amount i64 }*
 *   metadataCount i32 | { key str, value str }*   (keys sorted ascending)
 *
 * Length prefixes make the encoding unambiguous: no choice of strings can make
 * two different entries produce the same byte stream.
 */
internal object EntryCodec {
    private const val MAGIC = 0x50414D4C // ASCII "PAML"
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 64 * 1024

    fun encode(e: LedgerEntry): ByteArray {
        val w = ByteWriter()
        w.int(MAGIC)
        w.byte(VERSION)
        w.long(e.seq)
        w.long(e.timestampMicros)
        w.raw(e.prevChainHash.rawBytes())
        w.long(e.txId.mostSignificantBits)
        w.long(e.txId.leastSignificantBits)
        w.str(e.idempotencyKey)
        writeBody(w, e.kind, e.postings, e.metadata)
        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): LedgerEntry {
        val r = ByteReader(bytes)
        require(r.int() == MAGIC) { "bad magic" }
        require(r.byte() == VERSION) { "unsupported entry version" }
        val seq = r.long()
        val ts = r.long()
        val prev = r.digest()
        val txId = UUID(r.long(), r.long())
        val key = r.str(MAX_STRING_BYTES)
        val kind = r.str(MAX_STRING_BYTES)
        val postingCount = r.int()
        require(postingCount in 0..Validation.MAX_POSTINGS) { "posting count out of range" }
        val postings = List(postingCount) {
            Posting(AccountId(r.str(MAX_STRING_BYTES)), r.str(16), r.long())
        }
        val metaCount = r.int()
        require(metaCount in 0..Validation.MAX_METADATA) { "metadata count out of range" }
        val metadata = TreeMap<String, String>()
        repeat(metaCount) { metadata[r.str(MAX_STRING_BYTES)] = r.str(MAX_STRING_BYTES) }
        r.expectEnd() // trailing garbage would be a second encoding of the same entry
        return LedgerEntry(seq, ts, txId, prev, key, kind, postings, metadata)
    }

    /**
     * Fingerprint of the BUSINESS CONTENT of a request (kind, postings, metadata).
     * Used to tell an honest retry ("same key, same content" -> replay) from a
     * conflicting reuse ("same key, different content" -> reject).
     */
    fun requestFingerprint(kind: String, postings: List<Posting>, metadata: SortedMap<String, String>): Digest {
        val w = ByteWriter()
        w.str("PAM-LEDGER/request/v1")
        writeBody(w, kind, postings, metadata)
        return Hashes.sha256(w.toByteArray())
    }

    fun fingerprintOf(e: LedgerEntry) = requestFingerprint(e.kind, e.postings, e.metadata)

    private fun writeBody(w: ByteWriter, kind: String, postings: List<Posting>, metadata: SortedMap<String, String>) {
        w.str(kind)
        w.int(postings.size)
        for (p in postings) {
            w.str(p.account.value)
            w.str(p.currency)
            w.long(p.amount)
        }
        w.int(metadata.size)
        for ((k, v) in metadata) { // SortedMap iterates in key order: canonical by construction
            w.str(k)
            w.str(v)
        }
    }
}

/** Minimal big-endian writer. */
internal class ByteWriter {
    private val buffer = ByteArrayOutputStream(256)
    private val out = DataOutputStream(buffer)

    fun int(v: Int) = out.writeInt(v)
    fun long(v: Long) = out.writeLong(v)
    fun byte(v: Int) = out.writeByte(v)
    fun raw(b: ByteArray) = out.write(b)
    fun str(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    fun toByteArray(): ByteArray {
        out.flush()
        return buffer.toByteArray()
    }
}

/**
 * Minimal big-endian reader that is PARANOID: every read is bounds-checked and
 * every string must be strictly valid UTF-8. Malformed input raises
 * IllegalArgumentException rather than producing a "best effort" value.
 */
internal class ByteReader(bytes: ByteArray) {
    private val buf: ByteBuffer = ByteBuffer.wrap(bytes)

    fun int(): Int { need(4); return buf.int }
    fun long(): Long { need(8); return buf.long }
    fun byte(): Int { need(1); return buf.get().toInt() and 0xFF }

    fun digest(): Digest = Digest(bytes(Digest.SIZE))

    fun bytes(n: Int): ByteArray {
        require(n >= 0) { "negative length" }
        need(n)
        return ByteArray(n).also { buf.get(it) }
    }

    fun str(maxBytes: Int): String {
        val len = int()
        require(len in 0..maxBytes) { "string length $len out of range" }
        val raw = bytes(len)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("invalid UTF-8", e)
        }
    }

    fun expectEnd() = require(!buf.hasRemaining()) { "${buf.remaining()} trailing bytes" }

    private fun need(n: Int) = require(buf.remaining() >= n) { "truncated record" }
}
