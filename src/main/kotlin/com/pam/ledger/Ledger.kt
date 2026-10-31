package com.pam.ledger

import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/*
 * ============================================================================
 *  THE LEDGER ENGINE: single writer, group commit, lock-free reads
 * ============================================================================
 *
 *  CONCURRENCY MODEL
 *
 *    many caller threads                 ONE writer thread
 *    ───────────────────                 ─────────────────────────────────────
 *    submit(req) ──┐                     loop:
 *    submit(req) ──┼──► bounded queue ──►  1. take a batch (up to maxBatchSize)
 *    submit(req) ──┘    (back-pressure)    2. validate each request in order
 *                                          3. assign seq, timestamp, hashes
 *                                          4. write batch + ONE fsync
 *                                          5. publish new state to readers
 *                                          6. complete the callers' futures
 *
 *  Why a single writer instead of locks?
 *
 *   - There is no lock to forget, no lock ORDER to get wrong, hence no deadlocks.
 *   - Every transaction sees the effects of every earlier one: the ledger is
 *     LINEARIZABLE by construction. A double-spend race becomes impossible,
 *     because two bets for the last £10 are checked one after the other.
 *   - A hash chain is inherently sequential (each entry embeds the previous
 *     hash), so parallel writers would have to serialise anyway.
 *   - Throughput comes from GROUP COMMIT: the expensive fsync is shared by the
 *     whole batch. Under load, batches grow and the cost per transaction falls.
 *
 *  Readers never block the writer and never see uncommitted data: the writer
 *  updates the read-side structures only AFTER fsync succeeds.
 *
 *  FAILURE MODEL: fail-stop. If a disk write or fsync fails we cannot know what
 *  reached the platter, so the engine refuses all further writes. Restarting
 *  re-derives truth from disk; clients retry with the same idempotency keys.
 */

/**
 * @property directory Folder holding the journal files. One ledger per folder.
 * @property ledgerId Name baked into the genesis hash and every checkpoint.
 * @property signer Signs checkpoints. Back it with an HSM/KMS in production.
 * @property trustedKeys Public keys accepted for existing checkpoints (allows key rotation).
 * @property queueCapacity Maximum queued submissions before back-pressure applies.
 * @property maxBatchSize Most transactions committed by one fsync.
 * @property submitTimeout How long submit() waits for queue space before [OverloadedException].
 * @property checkpointEvery Sign a checkpoint automatically every N entries.
 * @property completionExecutor Runs callers' future callbacks, so slow user code never stalls the writer.
 */
data class LedgerConfig(
    val directory: Path,
    val ledgerId: String,
    val signer: TreeHeadSigner,
    val trustedKeys: List<PublicKey> = listOf(signer.publicKey),
    val accountPolicy: AccountPolicy = AccountPolicy.PLAYER_ACCOUNTS_NON_NEGATIVE,
    val queueCapacity: Int = 65_536,
    val maxBatchSize: Int = 1_024,
    val submitTimeout: Duration = Duration.ofSeconds(2),
    val checkpointEvery: Long = 10_000,
    val clock: Clock = Clock.systemUTC(),
    val completionExecutor: Executor = ForkJoinPool.commonPool(),
) {
    init {
        require(ledgerId.isNotBlank()) { "ledgerId required" }
        require(queueCapacity > 0 && maxBatchSize > 0 && checkpointEvery > 0)
    }
}

/** What recovery found when the ledger was opened. */
data class RecoveryReport(
    val entriesReplayed: Int,
    val checkpointsVerified: Int,
    val tornBytesQuarantined: Long,
)

class Ledger private constructor(
    private val config: LedgerConfig,
    private val dirLock: DirectoryLock,
    private val entryJournal: Journal,
    private val checkpointJournal: Journal,
    recovered: Replayer,
    recoveredCheckpoint: SignedTreeHead?,
    val recoveryReport: RecoveryReport,
) : AutoCloseable {

    // ---- State shared with readers. Written ONLY by the writer thread, after fsync. ----
    private val entries = recovered.entries
    private val prover = MerkleProver { entries[it.toInt()].leafHash }
    private val idempotency = ConcurrentHashMap<String, Long>(recovered.idempotency)
    private val accounts = ConcurrentHashMap<AccountId, AccountState>(recovered.accounts)
    @Volatile private var headRef: LedgerHead = recovered.head()
    @Volatile private var latestCheckpoint: SignedTreeHead? = recoveredCheckpoint

    // ---- State owned EXCLUSIVELY by the writer thread. No other thread touches these. ----
    private val frontier = recovered.frontier

    // ---- Lifecycle ----
    @Volatile private var failure: Throwable? = null
    private val closed = AtomicBoolean(false)
    @Volatile private var writerDone = false
    private val queue = ArrayBlockingQueue<Command>(config.queueCapacity)
    private val writer = Thread(::runWriter, "pam-ledger-writer-${config.ledgerId}").apply { isDaemon = true }

    /** Messages to the writer thread. */
    private sealed interface Command
    private class Submit(val request: TransactionRequest, val fingerprint: Digest, val future: CompletableFuture<Receipt>) : Command
    private class CheckpointRequest(val future: CompletableFuture<SignedTreeHead>) : Command
    private data object Shutdown : Command

    /** A transaction that passed validation and awaits the batch fsync. */
    private class Staged(val sealed: SealedEntry, val fingerprint: Digest, val future: CompletableFuture<Receipt>) {
        val duplicates = ArrayList<CompletableFuture<Receipt>>(0) // same key submitted twice in one batch
    }

    // =========================================================================
    //  WRITE API (any thread)
    // =========================================================================

    /**
     * Queue [request] for commit. The future completes with a [Receipt] once the
     * entry is durable, or fails with a [LedgerException].
     *
     * Safe to call from any number of threads. Never blocks longer than
     * [LedgerConfig.submitTimeout] (that is the back-pressure).
     */
    fun submit(request: TransactionRequest): CompletableFuture<Receipt> {
        failure?.let { return CompletableFuture.failedFuture(LedgerFailedException(it)) }
        if (closed.get()) return CompletableFuture.failedFuture(LedgerClosedException())

        // Hash the request on the CALLER's thread to keep work off the writer.
        val fingerprint = EntryCodec.requestFingerprint(request.kind, request.postings, request.metadata)
        val internal = CompletableFuture<Receipt>()
        val cmd = Submit(request, fingerprint, internal)
        if (!queue.offer(cmd, config.submitTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
            return CompletableFuture.failedFuture(OverloadedException())
        }
        // Race guard: if close() finished draining just before our offer landed,
        // nobody would ever complete this future. Take it back and fail it.
        if (writerDone && queue.remove(cmd)) internal.completeExceptionally(LedgerClosedException())

        // Hand the caller a DERIVED future whose callbacks run on the completion
        // executor. Otherwise a caller's thenApply{} could run on (and stall) the
        // writer thread. It also means a caller cancelling their future cannot
        // cancel the commit itself.
        return internal.whenCompleteAsync({ _, _ -> }, config.completionExecutor)
    }

    /**
     * Blocking convenience wrapper around [submit]. Throws the underlying
     * [LedgerException] directly. If [timeout] elapses, throws
     * [OutcomeUnknownException]: retry with the same key to learn the outcome.
     */
    fun append(request: TransactionRequest, timeout: Duration = Duration.ofSeconds(30)): Receipt = try {
        submit(request).get(timeout.toNanos(), TimeUnit.NANOSECONDS)
    } catch (e: ExecutionException) {
        throw unwrap(e)
    } catch (e: CompletionException) {
        throw unwrap(e)
    } catch (e: TimeoutException) {
        throw OutcomeUnknownException(request.idempotencyKey)
    }

    /** Sign and persist a checkpoint of the current head, sequenced with the writes. */
    fun checkpoint(): SignedTreeHead {
        failure?.let { throw LedgerFailedException(it) }
        if (closed.get()) throw LedgerClosedException()
        val f = CompletableFuture<SignedTreeHead>()
        val cmd = CheckpointRequest(f)
        queue.put(cmd)
        if (writerDone && queue.remove(cmd)) throw LedgerClosedException()
        return try { f.get() } catch (e: ExecutionException) { throw unwrap(e) }
    }

    // =========================================================================
    //  READ API (any thread, lock-free, only ever sees committed data)
    // =========================================================================

    fun head(): LedgerHead = headRef
    fun latestCheckpoint(): SignedTreeHead? = latestCheckpoint
    fun balance(account: AccountId): Long = accounts[account]?.balance ?: 0L
    fun account(account: AccountId): AccountState? = accounts[account]
    val publicKey: PublicKey get() = config.signer.publicKey
    val isFailed: Boolean get() = failure != null

    fun entry(seq: Long): SealedEntry = entries[Math.toIntExact(seq)]

    fun findByIdempotencyKey(key: String): SealedEntry? = idempotency[key]?.let { entry(it) }

    /** Proof that entry [seq] is in the tree of [treeSize] entries (defaults to the current head). */
    fun inclusionProof(seq: Long, treeSize: Long = headRef.size): InclusionProof {
        require(treeSize <= entries.size) { "tree size $treeSize exceeds ledger size ${entries.size}" }
        return InclusionProof(seq, treeSize, entry(seq).leafHash, prover.inclusionPath(seq, treeSize), prover.root(treeSize))
    }

    /** Proof that the ledger at [oldSize] is a prefix of the ledger at [newSize]. */
    fun consistencyProof(oldSize: Long, newSize: Long = headRef.size): List<Digest> {
        require(newSize <= entries.size) { "tree size $newSize exceeds ledger size" }
        return prover.consistencyProof(oldSize, newSize)
    }

    fun merkleRoot(treeSize: Long): Digest {
        require(treeSize in 0..entries.size)
        return prover.root(treeSize)
    }

    // =========================================================================
    //  LIFECYCLE
    // =========================================================================

    /**
     * Graceful shutdown: stop accepting work, commit everything already queued,
     * write a final checkpoint, then release files and the directory lock.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.put(Shutdown)
        writer.join()
        runCatching { entryJournal.close() }
        runCatching { checkpointJournal.close() }
        dirLock.close()
    }

    // =========================================================================
    //  THE WRITER THREAD
    // =========================================================================

    private fun runWriter() {
        val batch = ArrayList<Command>(config.maxBatchSize)
        var running = true
        while (running) {
            val first = try { queue.take() } catch (e: InterruptedException) { break }
            batch.add(first)
            // Grab whatever else is waiting, without blocking: this is the group commit.
            queue.drainTo(batch, config.maxBatchSize - 1)

            val stopAt = batch.indexOf(Shutdown)
            if (stopAt >= 0) {
                // Anything that slipped in after Shutdown is refused, not silently dropped.
                batch.subList(stopAt + 1, batch.size).forEach { failCommand(it, LedgerClosedException()) }
                batch.subList(stopAt, batch.size).clear()
                running = false
            }
            try {
                processBatch(batch)
            } catch (t: Throwable) {
                // A bug or an unexpected error. Fail-stop and fail every waiter;
                // completing an already-completed future is harmless.
                failStop(t)
                batch.forEach { failCommand(it, LedgerFailedException(t)) }
            }
            batch.clear()
        }
        // Final checkpoint so a clean shutdown always leaves a signed head on disk.
        if (failure == null && headRef.size > (latestCheckpoint?.head?.treeSize ?: -1L)) {
            runCatching { writeCheckpoint() }.onFailure(::failStop)
        }
        writerDone = true
        while (true) failCommand(queue.poll() ?: break, LedgerClosedException())
    }

    private fun processBatch(batch: List<Command>) {
        failure?.let { cause ->
            batch.forEach { failCommand(it, LedgerFailedException(cause)) }
            return
        }

        val start = headRef
        var seq = start.size
        var chain = start.chainHead
        var timestamp = start.lastTimestampMicros

        // Balances as they WILL be once this batch commits. Later requests in the
        // same batch must see earlier ones, or two bets could spend the same money.
        val overlay = HashMap<AccountId, AccountState>()
        val staged = ArrayList<Staged>(batch.size)
        val stagedByKey = HashMap<String, Staged>()
        val checkpointRequests = ArrayList<CheckpointRequest>(0)

        // Rejections are NOT reported straight away. A rejection may depend on an
        // earlier request in this same batch (e.g. "already settled" by a cash-out
        // staged two lines up, or "insufficient funds" because a staged bet spent
        // the money). Telling the caller before that earlier request is durable
        // would cite a fact that might never exist: if the fsync then failed,
        // the caller was refused because of a ghost. So rejections wait for the
        // batch outcome: delivered after commit, or replaced by LedgerFailed.
        val rejections = ArrayList<Pair<CompletableFuture<Receipt>, LedgerException>>(0)

        for (cmd in batch) {
            when (cmd) {
                is CheckpointRequest -> checkpointRequests += cmd
                Shutdown -> Unit
                is Submit -> {
                    val req = cmd.request

                    // (1) Already committed under this key? Replay or reject; never write twice.
                    val committedSeq = idempotency[req.idempotencyKey]
                    if (committedSeq != null) {
                        val existing = entries[committedSeq.toInt()]
                        if (EntryCodec.fingerprintOf(existing.entry) == cmd.fingerprint) {
                            cmd.future.complete(existing.receipt(replayed = true))
                        } else {
                            cmd.future.completeExceptionally(IdempotencyConflictException(req.idempotencyKey))
                        }
                        continue
                    }
                    // (2) Same key earlier in THIS batch? Piggy-back on it or reject.
                    val sibling = stagedByKey[req.idempotencyKey]
                    if (sibling != null) {
                        if (sibling.fingerprint == cmd.fingerprint) sibling.duplicates += cmd.future
                        else rejections += cmd.future to IdempotencyConflictException(req.idempotencyKey)
                        continue
                    }
                    // (3) Accounting rules against the overlay (committed + earlier in batch).
                    val updates = try {
                        Accounting.stage(req.postings, seq, config.accountPolicy, req.expectedVersions) {
                            overlay[it] ?: accounts[it]
                        }
                    } catch (e: LedgerException) {
                        rejections += cmd.future to e // rejected; nothing changes (reported after commit)
                        continue
                    }
                    // (4) Accepted: give it a place in history and seal it into the chain.
                    timestamp = maxOf(timestamp, nowMicros()) // never decreases, even if the clock steps back
                    val entry = LedgerEntry(
                        seq = seq,
                        timestampMicros = timestamp,
                        txId = UUID.randomUUID(),
                        prevChainHash = chain,
                        idempotencyKey = req.idempotencyKey,
                        kind = req.kind,
                        postings = req.postings,
                        metadata = req.metadata,
                    )
                    val sealed = SealedEntry.seal(entry)
                    overlay.putAll(updates)
                    val s = Staged(sealed, cmd.fingerprint, cmd.future)
                    staged += s
                    stagedByKey[req.idempotencyKey] = s
                    chain = sealed.chainHash
                    seq++
                }
            }
        }

        if (staged.isNotEmpty()) {
            // (5) DURABILITY: one write + one fsync for the whole batch.
            try {
                entryJournal.appendBatch(staged.map { it.sealed.journalRecord() })
            } catch (t: Throwable) {
                failStop(t)
                val ex = LedgerFailedException(t)
                for (s in staged) {
                    s.future.completeExceptionally(ex)
                    s.duplicates.forEach { it.completeExceptionally(ex) }
                }
                rejections.forEach { (f, _) -> f.completeExceptionally(ex) }
                checkpointRequests.forEach { it.future.completeExceptionally(ex) }
                return
            }

            // (6) PUBLISH, in an order that keeps every reader consistent:
            //     entries first, so anything that points at an entry can resolve it...
            for (s in staged) {
                entries.add(s.sealed)
                frontier.append(s.sealed.leafHash)
            }
            entries.publish()
            //     ...then the indexes and balances that refer to those entries...
            for (s in staged) idempotency[s.sealed.entry.idempotencyKey] = s.sealed.entry.seq
            accounts.putAll(overlay)
            //     ...and finally the head, which summarises all of it.
            headRef = LedgerHead(seq, chain, frontier.root(), timestamp)

            // (7) Only now, with the data on disk, tell the callers.
            for (s in staged) {
                val receipt = s.sealed.receipt(replayed = false)
                s.future.complete(receipt)
                s.duplicates.forEach { it.complete(receipt.copy(replayed = true)) }
            }
        }
        // Everything any rejection could have depended on is now durable and visible.
        rejections.forEach { (f, e) -> f.completeExceptionally(e) }

        val sinceCheckpoint = headRef.size - (latestCheckpoint?.head?.treeSize ?: 0L)
        if (checkpointRequests.isNotEmpty() || sinceCheckpoint >= config.checkpointEvery) {
            try {
                val sth = writeCheckpoint()
                checkpointRequests.forEach { it.future.complete(sth) }
            } catch (t: Throwable) {
                failStop(t)
                checkpointRequests.forEach { it.future.completeExceptionally(LedgerFailedException(t)) }
            }
        }
    }

    /** Writer thread only. */
    private fun writeCheckpoint(): SignedTreeHead {
        val h = headRef
        val sth = SignedTreeHead.sign(TreeHead(config.ledgerId, h.size, h.merkleRoot, h.chainHead, nowMicros()), config.signer)
        checkpointJournal.appendBatch(listOf(sth.encode()))
        latestCheckpoint = sth
        return sth
    }

    private fun failStop(t: Throwable) {
        if (failure == null) failure = t
    }

    private fun failCommand(cmd: Command, e: Throwable) {
        when (cmd) {
            is Submit -> cmd.future.completeExceptionally(e)
            is CheckpointRequest -> cmd.future.completeExceptionally(e)
            Shutdown -> Unit
        }
    }

    private fun nowMicros(): Long {
        val i = config.clock.instant()
        return i.epochSecond * 1_000_000 + i.nano / 1_000
    }

    companion object {
        const val ENTRIES_FILE = "entries.journal"
        const val CHECKPOINTS_FILE = "checkpoints.journal"
        const val LOCK_FILE = "ledger.lock"

        /**
         * Open (or create) a ledger, fully verifying the existing journal first.
         * Throws [TamperDetectedException] if anything fails verification. A
         * ledger that cannot prove its own history refuses to start.
         */
        fun open(config: LedgerConfig): Ledger {
            Files.createDirectories(config.directory)
            val lock = DirectoryLock.acquire(config.directory.resolve(LOCK_FILE))
            var entryJournal: Journal? = null
            var checkpointJournal: Journal? = null
            try {
                // 1. Replay and verify every entry.
                val replayer = Replayer(config.ledgerId, config.accountPolicy)
                val (ej, entryScan) = Journal.openForAppend(config.directory.resolve(ENTRIES_FILE)) { replayer.apply(it) }
                entryJournal = ej

                // 2. Verify every signed checkpoint against the replayed history.
                val prover = MerkleProver { replayer.entries[it.toInt()].leafHash }
                val trusted = config.trustedKeys.associateBy(::keyIdOf)
                var latest: SignedTreeHead? = null
                val (cj, cpScan) = Journal.openForAppend(config.directory.resolve(CHECKPOINTS_FILE)) { bytes ->
                    val sth = try {
                        SignedTreeHead.decode(bytes)
                    } catch (e: IllegalArgumentException) {
                        throw TamperDetectedException("undecodable checkpoint: ${e.message}")
                    }
                    CheckpointCheck.verify(sth, config.ledgerId, trusted, replayer, prover, latest)
                    latest = sth
                }
                checkpointJournal = cj

                val report = RecoveryReport(entryScan.records, cpScan.records, entryScan.tornTailBytes + cpScan.tornTailBytes)
                return Ledger(config, lock, ej, cj, replayer, latest, report).also { it.writer.start() }
            } catch (t: Throwable) {
                runCatching { entryJournal?.close() }
                runCatching { checkpointJournal?.close() }
                lock.close()
                throw t
            }
        }

        private fun unwrap(e: Throwable): Throwable {
            var c: Throwable = e
            while ((c is ExecutionException || c is CompletionException) && c.cause != null) c = c.cause!!
            return c
        }
    }
}
