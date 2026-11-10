package com.pam.ledger

import java.security.PublicKey

/**
 * Balance arithmetic, shared by the live writer and by replay.
 *
 * Computes the NEW state of every account a transaction touches, or throws
 * without changing anything. It never mutates: the caller decides whether to
 * apply the result. That "all or nothing" shape is what makes each transaction
 * atomic: either every posting lands or none does.
 */
internal object Accounting {
    fun stage(
        postings: List<Posting>,
        seq: Long,
        policy: AccountPolicy,
        expectedVersions: Map<AccountId, Long>,
        lookup: (AccountId) -> AccountState?,
    ): Map<AccountId, AccountState> {
        // Optimistic concurrency: check BEFORE computing anything.
        for ((account, expected) in expectedVersions) {
            val actual = lookup(account)?.version ?: 0L
            if (actual != expected) throw VersionConflictException(account, expected, actual)
        }
        val updates = LinkedHashMap<AccountId, AccountState>(postings.size * 2)
        for (p in postings) {
            val current = lookup(p.account)
            // An account is bound to the currency of its first posting, forever.
            if (current != null && current.currency != p.currency) {
                throw CurrencyMismatchException(p.account, current.currency, p.currency)
            }
            val balance = current?.balance ?: 0L
            val newBalance = try {
                Math.addExact(balance, p.amount)
            } catch (e: ArithmeticException) {
                throw AmountOverflowException(p.account)
            }
            if (newBalance < 0 && policy.mustBeNonNegative(p.account)) {
                throw InsufficientFundsException(p.account, balance, -p.amount)
            }
            updates[p.account] = AccountState(p.currency, newBalance, (current?.version ?: 0L) + 1, seq)
        }
        return updates
    }
}

/**
 * Told about each entry as recovery verifies it, in order, before the ledger opens.
 * Lets a read model build its indexes in the same pass as verification instead of
 * reading the whole journal a second time. It only ever sees entries that passed
 * every check; if a later entry fails, the ledger does not open and whatever the
 * listener built must be discarded with it.
 */
fun interface RecoveryListener {
    fun recovered(entry: SealedEntry)
}

/**
 * A [RecoveryListener] that keeps its own indexes in the ledger's derived files.
 * [Ledger.open] hands it the storage before the first entry is replayed.
 */
internal interface DerivedIndexUser {
    fun useStorage(storage: DerivedStorage, indexFlushSize: Int)
}

/**
 * Where [Ledger.open] has recovery put what the running ledger keeps: an offset and
 * leaf hash per entry in [store] and a hash of each idempotency key in [keys],
 * instead of the entries and key strings themselves.
 */
internal class RecoveryIndex(val store: EntryStore, val keys: HashIndex, val listener: RecoveryListener?)

/**
 * Rebuilds ledger state from journal records, checking EVERYTHING on the way.
 *
 * Used in two places:
 *  - [Ledger.open], to recover state after a restart or crash;
 *  - [LedgerVerifier], to audit a ledger directory offline.
 *
 * For each record it checks: canonical encoding, gap-free sequence, the hash
 * chain link, the stored chain hash, monotonic timestamps, unique idempotency
 * keys, double-entry balance, and account rules (currency, non-negativity).
 * The first failure throws [TamperDetectedException] naming the entry.
 */
internal class Replayer(
    ledgerId: String,
    private val policy: AccountPolicy,
    private val retainEntries: Boolean = true,
    private val index: RecoveryIndex? = null,
    val entryStrings: EntryStrings = EntryStrings(),
) {
    val entries = AppendOnlyList<SealedEntry>()
    // Offline audits need historical hashes, not a second copy of every decoded entry and its bytes.
    private val leafHashes = AppendOnlyList<Digest>()
    private val chainHashes = AppendOnlyList<Digest>()
    // With an index, each completed tree node goes straight to the store's node files.
    val frontier = MerkleFrontier(index?.let { it.store.nodes::add })
    val idempotency = HashMap<String, Long>()
    val accounts = HashMap<AccountId, AccountState>()
    val genesis: Digest = Hashes.genesis(ledgerId)

    var chain: Digest = genesis
        private set
    var lastTimestamp: Long = 0
        private set
    var size: Long = 0
        private set

    /** [offset] is where the record's header starts in the journal; only [RecoveryIndex] recovery needs it. */
    fun apply(record: ByteArray, offset: Long = -1) {
        val seq = size
        fun tamper(msg: String): Nothing = throw TamperDetectedException("entry #$seq: $msg")

        if (record.size <= Digest.SIZE) tamper("record too short")
        val body = record.copyOfRange(0, record.size - Digest.SIZE)
        val storedChain = Digest(record.copyOfRange(record.size - Digest.SIZE, record.size))

        val decoded = try {
            EntryCodec.decode(body)
        } catch (e: IllegalArgumentException) {
            tamper("undecodable or invalid: ${e.message}")
        }
        // Decode-then-re-encode must give back the identical bytes. Otherwise two
        // byte strings would map to one entry, and the hash would not pin it down.
        if (!EntryCodec.encode(decoded).contentEquals(body)) tamper("non-canonical encoding")
        val entry = if (retainEntries || index != null) entryStrings.share(decoded) else decoded
        if (entry.seq != seq) tamper("sequence gap: found seq ${entry.seq}")
        if (entry.prevChainHash != chain) tamper("broken hash chain (prev hash does not match entry #${seq - 1})")
        if (entry.timestampMicros < lastTimestamp) tamper("timestamp went backwards")

        val sealed = SealedEntry.of(entry, body)
        if (sealed.chainHash != storedChain) tamper("stored chain hash does not match recomputed hash")
        // With an index, key strings are not kept: a matching hash names candidate entries, and
        // only an entry whose full key is equal counts. Those entries are read back from the journal.
        val keyHash = if (index != null) KeyIndex.hashOf(entry.idempotencyKey) else 0L
        val duplicate = if (index != null) {
            index.keys.anyCandidate(keyHash) { index.store[it].entry.idempotencyKey == entry.idempotencyKey }
        } else idempotency.containsKey(entry.idempotencyKey)
        if (duplicate) tamper("duplicate idempotency key '${entry.idempotencyKey}'")

        val updates = try {
            Accounting.stage(entry.postings, seq, policy, emptyMap()) { accounts[it] }
        } catch (e: LedgerException) {
            tamper("violates accounting rules: ${e.message}")
        }

        accounts.putAll(updates)
        // The leaf must be in the tree before the entry is published: a read proves an entry against it.
        frontier.append(sealed.leafHash)
        if (index != null) {
            index.store.stage(offset)
            index.store.remember(sealed)
            index.store.publish()
            index.keys.put(keyHash, seq)
            index.listener?.recovered(sealed)
        } else idempotency[entry.idempotencyKey] = seq
        if (index != null) {
            // Nothing else to retain: the store above is the ledger's memory of this entry.
        } else if (retainEntries) {
            entries.add(sealed)
            entries.publish()
        } else {
            leafHashes.add(sealed.leafHash)
            chainHashes.add(sealed.chainHash)
            leafHashes.publish()
            chainHashes.publish()
        }
        chain = sealed.chainHash
        lastTimestamp = entry.timestampMicros
        size++
    }

    fun head() = LedgerHead(size, chain, frontier.root(), lastTimestamp)

    fun leafHash(seq: Long): Digest = when {
        index != null -> index.store.leafHash(seq)
        retainEntries -> entries[Math.toIntExact(seq)].leafHash
        else -> leafHashes[Math.toIntExact(seq)]
    }

    fun chainHash(seq: Long): Digest = when {
        index != null -> index.store[seq].chainHash // read back from the journal; needed once per checkpoint
        retainEntries -> entries[Math.toIntExact(seq)].chainHash
        else -> chainHashes[Math.toIntExact(seq)]
    }
}

/** Checks a signed checkpoint against the replayed journal. */
internal object CheckpointCheck {
    fun verify(
        sth: SignedTreeHead,
        ledgerId: String,
        trustedKeys: Map<String, PublicKey>,
        replayer: Replayer,
        prover: MerkleProver,
        previous: SignedTreeHead?,
    ) {
        val size = sth.head.treeSize
        fun tamper(msg: String): Nothing = throw TamperDetectedException("checkpoint at size $size: $msg")

        val key = trustedKeys[sth.keyId] ?: tamper("signed by untrusted key ${sth.keyId}")
        if (!sth.verify(key)) tamper("invalid signature")
        if (sth.head.ledgerId != ledgerId) tamper("belongs to ledger '${sth.head.ledgerId}'")
        if (previous != null && size < previous.head.treeSize) tamper("checkpoints went backwards")
        if (size > replayer.size) {
            // The signer vouched for entries that are no longer on disk: a rollback.
            tamper("journal has only ${replayer.size} entries; committed history was truncated or rolled back")
        }
        val expectedChain = if (size == 0L) replayer.genesis else replayer.chainHash(size - 1)
        if (sth.head.chainHead != expectedChain) tamper("chain head does not match journal (history rewritten)")
        if (sth.head.rootHash != prover.root(size)) tamper("Merkle root does not match journal (history rewritten)")
    }
}
