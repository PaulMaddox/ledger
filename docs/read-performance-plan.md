# Read performance plan

Status: **proposed, not implemented.** This document is a plan for review. Nothing in
it describes the code as it is today except the "Current state" section.

Tracking issue: [#73](https://github.com/PaulMaddox/ledger/issues/73), which holds the
measurements and the description of each problem.

## Goal

Keep every customer-facing read independent of total ledger size, and raise the
number of entries one node can hold from about 3.5 million to tens of millions,
without weakening any ledger guarantee.

## Constraints

These are fixed. A step that cannot respect them is dropped, not negotiated.

1. **The ledger is fully verified before it opens.** Every entry, the hash chain,
   the accounting rules and every signed checkpoint are checked before any customer
   operation is served. This is an owner decision: restart time is not to be bought
   by loading state that has not been verified. There are no trusted snapshots in
   this plan.
2. **The byte formats do not change.** `EntryCodec`, `Hashes`, `TreeHead.signingBytes`
   and the journal framing stay exactly as they are. Everything added here is derived
   data that can be thrown away and rebuilt from the journal.
3. **Single writer, fail-stop, acknowledge only what is durable.** No step adds a
   lock, a second writer or a retry to the write path.
4. **No new runtime dependencies.** JDK and Kotlin standard library only.
5. **The read model is never used for a money decision.** Balances still come from
   the ledger. New indexes may choose *which* entries a reader looks at; they never
   replace recomputation from the entries themselves.

## Symbols

| Symbol | Meaning |
|---|---|
| N | entries in the ledger |
| P | players |
| B | bets ever placed |
| F | fixtures |
| h | entries belonging to one player |
| k | open bets of one player or one fixture |
| L | page size (40 in the console) |
| Δ | entries committed since the reader last looked |

## Current state

Measured on JDK 27, 4 vCPUs, in process, at 105k to 4.2M entries. Method and full
figures are in #73.

| Read | Cost | Measured, 105k → 4.2M |
|---|---|---|
| Customer balance | O(1) | 0.31 → 0.62 µs |
| Customer's open bets | O(k) | 0.24 → 0.82 µs |
| Customer history page | O(L) | about 1 µs |
| Customer bet list | O(L) typical, O(h) worst | worst case 36 µs → 6.5 ms |
| Player count | O(P) | 0.23 → 4.5 ms |
| Player search | O(P) copy, filter and sort | not measured separately |
| Player audit | O(N) | 46 → 396 ms (at 1.68M) |
| Regulator report | O(N + B) per request | not measured |
| Inclusion proof, warm | O(log N) | 10–35 µs |
| Inclusion proof, first after start | O(N) | 48 → 716 ms (at 1.68M) |
| Fixture restore at startup | O(B) | 0.05 → 2.1 s |
| Restart | O(N) | 1.5 → 41.8 s, plus 0.18 → 7.7 s read-model rebuild |
| Heap | O(N) at about 1.1 KB per entry | 107 MB → 4.6 GB |

The two reads that matter most to a customer, balance and open bets, are already
flat. The limits are heap first, then the O(N) and O(h) reads above.

## Target state

| Read | Today | Target | Step |
|---|---|---|---|
| Customer balance | O(1) | O(1) | unchanged |
| Customer's open bets | O(k) | O(k), without queueing behind other readers | 1 |
| Customer history page | O(L) | O(L) | unchanged |
| Customer bet list | O(h) worst | O(L) | 1 |
| Player count | O(P) | O(1) | 1 |
| Player search | O(P) with copy and sort | O(P) scan of a prepared list, no copy or sort | 1 |
| Player audit | O(N) | O(h) | 1 |
| Inclusion proof, first after start | O(N) | O(log N) | 1 |
| Fixture restore at startup | O(B) | O(F) | 1 |
| Regulator report | O(N + B) per request | O(N + B) once per checkpoint, O(1) repeat | 3 |
| Heap | O(N) × about 1.1 KB | O(N) × about 70–100 B (estimate) | 2 |
| Restart | O(N) | O(N), full verification kept | 4, constant factor only |

Two rows stay linear by design:

- **Restart** stays O(N) because of constraint 1.
- **Player search** stays O(P) because the console searches by substring (the
  placeholder suggests `00042` to find `sim-00042`), which no sorted index can
  serve. The plan removes the per-request copy and sort, which is where the measured
  time goes. A real search index is not justified at the player counts one node can
  hold.

## Step 1: read-model and proof fixes

Small changes, each independently shippable, none touching the write path.

### 1a. Per-player bet index

- **Problem:** `ReadModel.betsForPlayer` walks all of a player's history to find
  placements.
- **Change:** keep a second `LongHistory` per player holding only `BET_PLACED`
  sequence numbers, appended in `ReadModel.apply`. `betsForPlayer` reads its last L
  entries.
- **Result:** O(h) worst case becomes O(L).
- **Tests:** a player with one early bet and a long tail of deposits returns the same
  page as today; page contents and order are unchanged for existing fixtures.

### 1b. Player count and search

- **Problem:** `players()` copies the whole set for a count and for every search.
- **Change:** add `playerCount()` returning the index size. Keep an immutable, sorted
  list of player IDs that is replaced only when a new player first appears;
  `searchPlayers` scans that list in place.
- **Result:** count is O(1). Search is an O(P) scan with no allocation proportional
  to P and no per-request sort.
- **Tests:** search results and their order are identical to today's for the same
  inputs, including the automated-players-last ordering.

### 1c. Per-account index for the player audit

- **Problem:** `replayPlayerAudit` reads every entry in the ledger.
- **Change:** `ReadModel.apply` records, for each posting, the entry's sequence
  number against that account. The audit catches the read model up to the committed
  size it captured, then reads only the indexed entries for the player's cash and
  escrow accounts.
- **Keeping it honest:** the audit still recomputes both balances from the postings
  in the entries and compares them with the ledger's own `AccountState`. In addition
  it checks that the number of indexed entries per account equals
  `AccountState.version`. `Validation` rejects a transaction that names an account
  twice, so the version is exactly the number of entries that touched the account.
  A missing or extra index row therefore fails the audit instead of passing quietly.
- **Result:** O(N) becomes O(h).
- **Tests:** audit output is identical to the full scan for every player in the
  multi-device regulatory fixtures; an index with a row removed is reported as not
  reconciling.

### 1d. Keep the Merkle cache that recovery already built

- **Problem:** `Ledger.open` fills a `MerkleProver` cache while verifying checkpoints
  and then discards it.
- **Change:** pass that prover to the `Ledger` instance instead of constructing a
  second one. Both read leaf hashes from the same entry list.
- **Result:** the first proof after a restart is O(log N).
- **Tests:** proofs before and after a restart are equal; existing `MerkleTest` and
  `IntegrityTest` pass unchanged.

### 1e. Fixture restore from per-fixture summaries

- **Problem:** `Book.restoreFixtures` builds a view of every bet ever placed to learn
  three things per fixture: the odds seen per selection, which outcomes exist per
  selection, and whether any bet is still open.
- **Change:** `ReadModel` maintains exactly that summary per fixture as it tails
  (selection → odds, selection → set of outcomes, open count). `restoreFixtures`
  reads the summaries.
- **Result:** O(B) becomes O(F). Memory is O(F × selections).
- **Tests:** restore decisions (result kept, marked unresolved, completion flag) are
  identical to today's across the existing restart and legacy-fixture tests.

### 1f. Stop readers queueing

- **Problem:** every query and `catchUp` share one monitor.
- **Change:** replace it with a read-write lock, so queries run concurrently and only
  the tailer excludes them. Give `catchUp` a fast path that returns without taking
  the write lock when the read model is already at the ledger head.
- **Result:** no change in complexity; readers no longer wait for each other.
- **Tests:** a race test with `TestSupport.race` running queries against a ledger
  under write load, asserting every returned view is consistent with a committed
  prefix. If measurement after this change still shows contention, move to published
  immutable snapshots in a follow-up; do not do so speculatively.

### 1g. A growth regression test

- Commit the benchmark behind #73 as a `soak`-tagged test.
- It builds ledgers at two sizes a factor of four apart and asserts that the
  per-operation cost of the flat reads grows by less than a fixed ratio.
- It reports heap per entry so step 2 has a baseline.

## Step 2: move entry bodies off the heap

This removes the memory ceiling. It is the largest change and should be reviewed as
its own pull request, after step 1.

### What stays in memory

| Structure | Today | After |
|---|---|---|
| Entry | decoded entry, canonical bytes, leaf hash, chain hash | journal offset (8 B) and leaf hash (32 B), in chunked primitive arrays |
| Idempotency index | `String` key → sequence | 64-bit hash of the key → sequence, in a primitive table |
| Read-model bet map | one object per bet ever placed | 64-bit hash of the bet ID → placement and settlement sequences, in a primitive table |
| Recently used entries | everything | a bounded cache |

Estimated cost is 70–100 bytes per entry against about 1,100 today. That estimate is
arithmetic, not measurement; step 1g provides the measurement.

### Reading an entry

`Ledger.entry(seq)` becomes:

1. Return the entry from the bounded cache if present.
2. Otherwise read the record at its offset with a positional `FileChannel.read`,
   which does not share a file position with the writer.
3. Check the frame CRC, decode, re-encode canonically, hash, and compare with the
   leaf hash held in memory.
4. On any mismatch, throw `TamperDetectedException` and fail-stop the ledger.

Step 3 means an entry read back from disk is checked against the hash that was
verified at startup. Today a file altered after startup is not noticed until the
next restart or audit; after this change it is noticed on the first read.

Publication order is unchanged: an offset becomes visible to readers only after the
batch containing it has been fsynced.

### Idempotency lookups

A lookup hashes the key, finds candidate sequence numbers, and confirms by reading
the candidate entry and comparing the full key. A hash collision costs one extra
entry read and can never produce a wrong answer. The writer's fingerprint comparison
is unchanged.

### Settled bets

`ReadModel` keeps one object per bet ever placed so that `bet(betId)` can find the
placement and settlement entries. That becomes a primitive table from a hash of the
bet ID to the two sequence numbers, confirmed by reading the placement entry and
comparing its `betId`. Open bets keep their decoded views as today.

The lookup must stay keyed by bet ID, not by the wallet's idempotency keys:
`HeapOptimizationTest` requires bets written under nonstandard keys to keep their
attribution.

### Other changes in this step

- Widen in-memory indexing from `Int` to `Long`, removing the cap at about 2.1
  billion entries.
- `Journal` scanning reports each record's offset to its callback. This is an
  internal API change; the file format is untouched.
- Whole-ledger readers (the regulator report, the offline audit) switch from
  per-sequence lookups to a sequential journal scan, so they do not turn into N
  random reads.
- The read model is fed during recovery through a listener, so it does not re-read
  the whole journal after the ledger opens.

### Costs and risks

- A cold entry read costs a disk read, a decode and a SHA-256. Customer pages read
  at most L entries; the cache absorbs repeated reads of recent history.
- Memory is still O(N), with a constant roughly a tenth of today's. Holding a ledger
  whose indexes exceed one node's memory is the job of sharding by player in the
  cloud design, not of this plan.
- The guide describes entries as held in memory. It must be updated in the same pull
  request, and any debugger anchors that quote changed lines must be fixed.

### Tests

- Every existing concurrency, integrity and regulatory test passes unchanged.
- New: a record altered on disk after startup is detected on read and stops the
  ledger.
- New: forced idempotency hash collisions still replay or reject correctly.
- New: a race test reading entries while the writer appends.
- The step 1g test shows heap per entry at or below the estimate.

## Step 3: regulator reports

- **Problem:** each report request scans the whole ledger and every bet.
- **Change:** a report is computed against a signed checkpoint, which never changes.
  Cache the computed aggregates in a small bounded cache keyed by checkpoint, currency,
  population and period. Evidence pages and proofs are still read from the ledger on
  each request.
- **Result:** O(N + B) once per distinct report, O(1) for repeats.
- **Not proposed now:** maintaining report aggregates incrementally in the tailer.
  It would make the first request cheap too, but it adds a second implementation of
  the report arithmetic that must be proven equal to the scan. Revisit only if the
  first-request cost is a problem in practice. Related: #50.
- **Tests:** cached and uncached responses are byte-identical; the cache is bounded;
  a storage reset clears it.

## Step 4: restart time, with verification intact

Restart stays O(N). Nothing here skips, defers or samples a check.

### What is guaranteed

- `Ledger.open` returns only after every entry and every checkpoint has verified.
- The web console creates its services, and therefore serves customer operations,
  only after `Ledger.open` returns.
- Add a regression test that pins this: while verification is in progress no
  customer operation is accepted, and a journal that fails verification never
  serves one.

### What may be made faster

Each item keeps every existing check and is only worth doing if profiling shows it
matters.

1. **Buffered sequential scanning** of the journal during recovery (#51).
2. **One pass instead of two.** Feed the read model during recovery (step 2), which
   removes the separate rebuild that took 7.7 s at 4.2M entries.
3. **Hashing ahead of the verifier.** Decoding, canonical re-encoding and leaf
   hashing of a record depend only on that record, so worker threads can prepare
   records in order ahead of the single thread that checks the sequence, the chain
   link, timestamps, idempotency and accounting. The verifier still checks every
   record, in order, on one thread. This touches recovery code and needs explicit
   owner approval before it is built.

### What is ruled out

- Loading balances, indexes or Merkle state from a snapshot in place of replay.
- Serving any customer operation while verification is still running.
- Verifying only the entries after the last checkpoint.

### Bounding restart time operationally

Restart time is proportional to the entries one node must verify. In the cloud
design that number is bounded by sharding by player, and a standby that has already
verified its copy can take over without a cold start. Those are deployment topics
and are out of scope here.

## Order of work

| Order | Step | Why here |
|---|---|---|
| 1 | Step 1 (1a–1g) | Low risk, removes every O(N) and O(h) customer-facing read, provides the benchmark |
| 2 | Step 2 | Removes the heap ceiling; needs the benchmark from 1g |
| 3 | Step 3 | Independent; small |
| 4 | Step 4 | Only after profiling; item 3 needs approval |

Each step is one or more pull requests with its own tests, guide update where the
guide describes the changed behaviour, and screenshots only if the rendered console
changes. No step in this plan is expected to change the rendered console.

## Decisions

| Decision | Status |
|---|---|
| Trusted snapshots to shorten restart | **Rejected by the owner.** Full verification before opening is a constraint of this plan. |
| Keep substring player search | Proposed: keep, and remove the per-request copy and sort. |
| Fail-stop when an entry read from disk does not match its verified hash (step 2) | Proposed; needs owner confirmation. |
| Parallel preparation of records during recovery (step 4, item 3) | Not to be built without owner approval. |
