package com.pam.ledger

import java.util.Collections
import java.util.SortedMap
import java.util.TreeMap
import java.util.UUID

/*
 * ============================================================================
 *  DOMAIN MODEL
 * ============================================================================
 *
 *  The ledger is DOUBLE-ENTRY. Every transaction is a list of postings, and the
 *  postings of each currency must sum to exactly zero. Money is therefore never
 *  created or destroyed inside the ledger, only moved between accounts:
 *
 *      Deposit 50.00 EUR:
 *          house:psp-clearing:EUR      -5000
 *          player:42:cash:EUR          +5000      (sum = 0)
 *
 *  Amounts are Long values in MINOR units (cents, pence, fils...). Never use
 *  floating point for money: 0.1 + 0.2 != 0.3 and regulators do not find that
 *  amusing.
 *
 *  Every object here is immutable. Immutable objects can be shared between
 *  threads without locks, which is a large part of how the engine stays safe.
 */

/**
 * Identifier of a ledger account, e.g. `player:42:cash:EUR`.
 *
 * A `value class` costs nothing at runtime (it is just a String) but stops us
 * accidentally passing, say, an idempotency key where an account was expected.
 */
@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotEmpty() && value.length <= MAX_LENGTH) { "account id must be 1..$MAX_LENGTH chars" }
        require(value.none { it.isWhitespace() || it.isISOControl() }) { "account id must not contain whitespace or control chars" }
        require(Validation.isWellFormedUnicode(value)) { "account id is not well-formed unicode" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 256
    }
}

/**
 * One leg of a transaction: [amount] minor units of [currency] added to [account]
 * (negative amounts are debits).
 */
data class Posting(val account: AccountId, val currency: String, val amount: Long) {
    init {
        require(Validation.CURRENCY.matches(currency)) { "currency must be an ISO-4217 alpha-3 code, got '$currency'" }
        require(amount != 0L) { "zero-amount postings are not allowed (they add noise without moving money)" }
        // Long.MIN_VALUE has no positive counterpart, so negating it overflows. Ban it outright.
        require(amount != Long.MIN_VALUE) { "amount out of range" }
    }
}

/**
 * Decides which accounts may never go below zero.
 *
 * A player's cash balance must never go negative (that would be the operator
 * extending unregulated credit). House accounts such as PSP clearing may be
 * negative: they represent money owed to or by third parties.
 */
fun interface AccountPolicy {
    fun mustBeNonNegative(account: AccountId): Boolean

    companion object {
        /** Default: every account whose id starts with `player:` must stay >= 0. */
        val PLAYER_ACCOUNTS_NON_NEGATIVE = AccountPolicy { it.value.startsWith("player:") }
    }
}

/**
 * What a caller asks the ledger to record. It has no sequence number, timestamp
 * or hashes yet: those are assigned by the single writer thread at commit time.
 *
 * @property idempotencyKey Globally unique business key, e.g. `bet:8812:place`.
 *   Submitting the same key twice records the transaction only ONCE. This is the
 *   cornerstone of safe retries: a client that timed out can simply resend.
 * @property kind Machine-readable transaction type, e.g. `BET_PLACED`.
 * @property expectedVersions Optional optimistic-concurrency guard. If supplied,
 *   the transaction is rejected unless each named account is still at exactly
 *   that version (i.e. nobody else touched it since the caller read it).
 *   Deliberately NOT part of the idempotency fingerprint: a retry of an already
 *   committed request must replay its receipt even though versions have moved on.
 */
class TransactionRequest(
    val idempotencyKey: String,
    val kind: String,
    postings: List<Posting>,
    metadata: Map<String, String> = emptyMap(),
    val expectedVersions: Map<AccountId, Long> = emptyMap(),
) {
    // Defensive copies: the caller cannot mutate these after validation.
    val postings: List<Posting> = Collections.unmodifiableList(postings.toList())
    val metadata: SortedMap<String, String> = Collections.unmodifiableSortedMap(TreeMap(metadata))

    init {
        Validation.checkTransaction(idempotencyKey, kind, this.postings, this.metadata)
    }

    override fun toString() = "TransactionRequest(key=$idempotencyKey, kind=$kind, postings=$postings)"
}

/**
 * A transaction as it is permanently recorded. Produced only by the writer
 * thread (or by decoding the journal during recovery).
 *
 * @property seq Position in the ledger, 0-based, gap-free.
 * @property timestampMicros Commit time, epoch microseconds, never decreasing.
 * @property txId Random UUID, handy for cross-referencing with other systems.
 * @property prevChainHash Chain hash of entry seq-1 (or the genesis hash for
 *   seq 0). Embedding it INSIDE the entry is what turns the journal into a hash
 *   chain: altering any old entry changes every hash after it.
 */
data class LedgerEntry(
    val seq: Long,
    val timestampMicros: Long,
    val txId: UUID,
    val prevChainHash: Digest,
    val idempotencyKey: String,
    val kind: String,
    val postings: List<Posting>,
    val metadata: SortedMap<String, String>,
) {
    init {
        require(seq >= 0) { "seq must be non-negative" }
        // Entries decoded from disk go through the very same validation as new
        // requests, so a tampered journal cannot smuggle in an unbalanced transaction.
        Validation.checkTransaction(idempotencyKey, kind, postings, metadata)
    }
}

/**
 * A [LedgerEntry] together with its exact canonical bytes and the hashes over them.
 *
 * - [leafHash]  = SHA-256(0x00 || bytes)            -> the Merkle tree leaf
 * - [chainHash] = SHA-256(tag || prevChain || leaf)  -> the running hash chain
 */
class SealedEntry internal constructor(
    val entry: LedgerEntry,
    private val encoded: ByteArray,
    val leafHash: Digest,
    val chainHash: Digest,
) {
    /** A copy of the canonical bytes (copy, so callers cannot corrupt ours). */
    fun encodedBytes(): ByteArray = encoded.copyOf()

    /** What is written to disk: canonical entry bytes followed by the 32-byte chain hash. */
    internal fun journalRecord(): ByteArray = encoded + chainHash.rawBytes()

    fun receipt(replayed: Boolean) = Receipt(
        seq = entry.seq,
        txId = entry.txId,
        timestampMicros = entry.timestampMicros,
        leafHash = leafHash,
        chainHash = chainHash,
        replayed = replayed,
    )

    companion object {
        internal fun seal(entry: LedgerEntry): SealedEntry = of(entry, EntryCodec.encode(entry))

        internal fun of(entry: LedgerEntry, encoded: ByteArray): SealedEntry {
            val leaf = Hashes.leaf(encoded)
            return SealedEntry(entry, encoded, leaf, Hashes.chain(entry.prevChainHash, leaf))
        }
    }
}

/**
 * Proof-of-commit handed back to the caller. Only issued AFTER the entry has been
 * fsync'd to disk, so a receipt in hand means the transaction survives a crash.
 *
 * @property replayed true if this receipt is for a request that had already been
 *   committed under the same idempotency key (i.e. nothing new was written).
 */
data class Receipt(
    val seq: Long,
    val txId: UUID,
    val timestampMicros: Long,
    val leafHash: Digest,
    val chainHash: Digest,
    val replayed: Boolean,
)

/**
 * Committed state of one account.
 * @property version Increments on every posting to the account; used for optimistic concurrency.
 * @property lastSeq Sequence number of the last entry that touched the account.
 */
data class AccountState(val currency: String, val balance: Long, val version: Long, val lastSeq: Long)

/**
 * The ledger's "tip": how many entries exist and the two cryptographic
 * commitments to all of them (the chain hash and the Merkle root).
 */
data class LedgerHead(
    val size: Long,
    val chainHead: Digest,
    val merkleRoot: Digest,
    val lastTimestampMicros: Long,
)
