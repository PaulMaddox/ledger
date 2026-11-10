# Working on this repository

Instructions for coding agents (and humans) changing this project. Read it fully
before your first change: several rules protect properties that tests alone cannot.

## What this is

A cryptographically verifiable, append-only, double-entry ledger for a sportsbook
player account platform, in Kotlin on JDK 27, plus an interactive guide published
with GitHub Pages. The ledger's job is to make money movements exact, durable and
provable to a regulator. Correctness outranks speed, features and tidiness.

## Layout

```
src/main/kotlin/com/pam/ledger/
  Ledger.kt              engine: queue, single writer thread, group commit, recovery
  SportsbookWallet.kt    deposits, withdrawals, bets, exactly-once settlement
  Replay.kt              balance rules, replay verification, checkpoint checks
  Journal.kt             CRC-framed append-only file, fsync, torn-write recovery, lock
  EntryStore.kt          entries read from the journal on demand and re-verified; Merkle node files
  Derived.kt             derived index files: write-once pages, MAC under an in-memory key, rebuilt each start
  HashIndex.kt           idempotency index: in-memory table, sorted runs on disk, Bloom filter
  ChainLog.kt            per-player and per-account histories in a derived file
  Merkle.kt              incremental root, inclusion and consistency proofs
  Signing.kt             signed tree heads, TreeHeadSigner interface
  EntryCodec.kt, Hashes.kt   canonical byte format and domain-separated hashing
  Model.kt, Validation.kt, Errors.kt, AppendOnlyList.kt, LedgerVerifier.kt
  demo/Demo.kt
  web/                   web console: read model, book, simulator, regulator tools, HTTP API
src/main/resources/web/  web console front end: index.html, app.js, app.css (no libraries)
src/test/kotlin/com/pam/ledger/
  MultiDeviceWalletTest.kt   regulatory properties R1-R6 under multi-device load
  ConcurrencyTest.kt, IntegrityTest.kt, MerkleTest.kt, TestSupport.kt
  ReadScalingTest.kt         read-model indexes; opt-in soak proves reads stay flat as the ledger grows
  EntryStoreTest.kt          entries read back from the journal, on-disk tamper detection, hashed key index
  DerivedIndexTest.kt        derived files: page authentication, tampering, index and filter equivalence, chains
  VerifyBeforeOpenTest.kt    owner decision: the whole ledger is verified before any customer operation
  WebAppTest.kt              web console API, simulator, bets racing settlement
docs/
  readme.src.html        SOURCE of the interactive guide (edit this)
  index.html             BUILT guide served by GitHub Pages (never edit by hand)
scripts/
  setup.sh, setup.ps1    one-command developer setup (Ubuntu, Windows); keep README in step
  build-readme.py        builds docs/index.html, embedding the real Kotlin sources and tests
  check-guide.mjs        headless check of the built guide
  capture-screenshots.py regenerates docs/screenshots/ from the running web console
docs/screenshots/        web console screenshots, used by README.md and the guide
```

## Commands

```
./gradlew test                       # regular CI suite (JUnit 5); gradlew.bat on Windows
./gradlew test -PincludeSoak          # also run the opt-in accelerated 24-hour fixture soak
./gradlew run                        # demo: concurrent betting, audit, proofs
./gradlew web                        # web console at http://localhost:8080
./gradlew installDist                # package the standalone CLI demo
python3 scripts/build-readme.py      # rebuild docs/index.html after ANY source or guide change
node scripts/check-guide.mjs         # must print four PASS lines
node scripts/check-web.mjs           # dependency-free console regressions
```

Long-running tests use the JUnit `soak` tag and are excluded unless `-PincludeSoak`
is supplied. Normal CI does not opt in. The fixture soak can take over five minutes;
the 30-minute before/after load comparison is a manual validation, not a CI test.

The setup scripts install JDK 27 if needed and download the build tools; the first
build requires internet access. On Windows, the README's `-ExecutionPolicy Bypass`
option applies only to the launched PowerShell process, not to system settings. With JDK 27
already installed, setup is optional. Setup selects a complete JDK 27 for both
`JAVA_HOME` and `PATH` within its process, so a stale `JAVA_HOME` cannot force an
unsupported Gradle daemon JVM. Keep your terminal's `JAVA_HOME` on JDK 27 for
subsequent wrapper commands. The README quick starts source setup and start the
console in the same child shell, retaining the selected JDK without changing
the caller's shell settings. In Bash, source setup as a standalone command with
errexit enabled, never on the left of `&&`, which disables its failure checks.
Kotlin 2.4 currently emits JVM 26 bytecode; both
compilers target 26 while the build, tests and runtime use JDK 27.
Run `python3 scripts/check-setup.py` with JDK 27 on `PATH` to exercise both
valid and stale `JAVA_HOME` selection; CI runs it on Ubuntu and Windows.

The CLI demo places and settles bets concurrently, checks proofs, and audits the
files it wrote. Rejected overspending attempts are expected; an audit failure
causes a non-zero exit. Test reports are in `build/reports/tests/test/index.html`.
After `installDist`, run `build/install/pam-ledger/bin/pam-ledger`; on Windows,
use `build\install\pam-ledger\bin\pam-ledger.bat`.

Guide tools require Python 3.10+ and Node.js 18+. The setup scripts can install
them with `--with-guide-tools` on Ubuntu or `-WithGuideTools` on Windows. Use
`python` instead of `python3` on Windows.

Always use the wrapper (`./gradlew`), never a locally installed `gradle`, so builds use
the same Gradle version as CI. Do not change `gradle/wrapper/` except to upgrade Gradle
deliberately, with `./gradlew wrapper --gradle-version <version>`.

If the wrapper cannot download Gradle, or Gradle cannot reach Maven Central, in your environment, say so plainly. You may
compile with a standalone `kotlinc`, but never report the test suite as passing
unless the tests actually ran.

## Invariants: never break these

Each of these is load-bearing. If a task seems to require breaking one, stop and ask.

1. **Single writer.** Only the writer thread in `Ledger.kt` mutates ledger state.
   Do not add locks, a second writer, or parallel processing of a batch. Throughput
   comes from group commit and, in the cloud design, from sharding by player.
2. **Acknowledge only what is durable.** A receipt is issued only after the batch
   is fsynced. Publication order is entries, then indexes and balances, then the head.
   Rejections are delivered only after the batch they depend on has committed (see
   the comment above `rejections` in `processBatch`).
3. **Money is conserved and exact.** Postings sum to zero per currency. Amounts are
   `Long` minor units with overflow-checked arithmetic (`Math.addExact`). Never use
   floating point for money.
4. **Append-only.** Never update or delete a journal record. Corrections are new,
   compensating transactions with their own idempotency keys
   (for example `bet:{id}:resettle:{n}`).
   The owner-approved demo-only exception is the Administrator storage reset: after
   confirmation and draining every demo service, it deletes the demo journals, torn-tail quarantine files and
   fixture state, archive and archive torn-tail files and reseeds a fresh demo. Never expose this operation in production
   or extend it to unrelated files or signing keys. The owner also permits Chaos
   Monkey and its tamper lab to attack the main demo store after explicit confirmation,
   with all services drained and the directory lock held for file mutations. Preserve
   fail-stop and recovery verification; leave damage for inspection until manual reset.
   These demonstration exceptions never authorize changing production writer rules.
5. **The byte formats are a contract.** `EntryCodec`, `Hashes` and
   `TreeHead.signingBytes` define every hash and signature. Changing them makes every
   existing ledger fail verification. Do not "tidy" them. A real format change needs
   a new version number, a migration plan and explicit approval from the owner.
6. **Exactly-once by idempotency key.** Every business operation has a deterministic
   key. All outcomes of a bet (win, loss, void, cash-out) share `bet:{id}:settle`;
   that shared key is what makes settlement exactly-once.
7. **One rulebook for writing and replay.** `Validation` and `Accounting` are used both
   for new requests and when replaying the journal. Changing a rule, or the
   `AccountPolicy`, changes which existing journals verify.
8. **Fail-stop.** After an I/O error the engine refuses further writes. Do not add retry
   loops around journal writes; clients retry with the same idempotency key instead.
9. **No new runtime dependencies.** The runtime is the JDK plus the Kotlin standard
   library. Test-only dependencies are fine.
10. **Never weaken a test to make it pass.** A failing concurrency or integrity test is
    a bug report. Regulatory tests check against the ledger's replayed history; keep it
    that way.

## Memory and history

History lives in the journal, not on the heap (issue #73). Keep it that way:

- The heap holds balances, open bets, the Merkle frontier, a Bloom filter over idempotency
  keys (about ten bits each), a few numbers per player history, and bounded caches. Do not
  add a structure that keeps an object, a decoded entry or a key string for every entry or
  every bet.
- Everything else that grows with history is a derived file (`Derived.kt`): entry offsets,
  Merkle nodes, the idempotency index, per-player histories. Three rules make those files
  safe, and all three must hold for any new one:
  1. Never trusted across a restart. The derived directory is emptied at open and rebuilt
     by the full replay. Never load a derived file in place of that replay.
  2. Every page is authenticated with a MAC under a key that exists only in memory. A page
     that fails stops the ledger.
  3. Every page is written once. Never rewrite a page in place: that is what makes putting
     back an older valid copy impossible. Changing data means a new file (as index merges do).
- The writer never reads or writes a file to add an idempotency key, and never waits for
  index maintenance. The maintenance thread touches only derived index files; it is not a
  second ledger writer. If maintenance falls far behind (a stalled derived disk), `submit`
  turns new requests away with `OverloadedException` instead; never make the writer wait.
- A background failure raised before the ledger object exists goes through `FailureAlarm`,
  which holds it until the ledger connects. Never route one through a callback that can drop it.
- A read model whose indexing failed part-way is never used again (`ReadModel.broken`) and
  stops the ledger: retrying the entry would record part of it twice.
- The Bloom filter may say "possibly present" for a new key, never "absent" for a committed
  one. Add a key to its table first, then to the filters; see `HashIndex.rebuildBloom`.
- `Ledger.entry` re-hashes what it reads from disk and compares it with the leaf hash
  verified at startup. A mismatch throws `TamperDetectedException` and fail-stops the
  ledger. Never bypass that check or cache unverified bytes.
- A hash match in the key index is only a candidate. The writer must read the entry and
  compare the full idempotency key before treating a request as a retry.
- Read long ranges with `Ledger.scan`, never with `Ledger.entry` in a loop over the whole
  ledger: `scan` does not evict the recent entries that customer requests rely on.
- Readers use their own file channel. Never hand the writer's channel to a reader thread:
  Java closes a channel when a thread blocked in it is interrupted.
- The ledger is fully verified before it opens. Never load balances, indexes or Merkle
  state from a snapshot in place of replay, never serve while verification is running,
  and never verify only the entries after the last checkpoint. This is an owner decision.

## Changing code

- Follow the existing comment style: explain *why*, not *what*, and keep KDoc on public
  APIs. This codebase is meant to be read by auditors as well as engineers.
- New behaviour needs tests. Concurrency changes need a race test (`TestSupport.race`);
  an intermittent failure is a real bug, not a reason to rerun until green.
- Keep error types in the sealed `LedgerException` hierarchy.

## Keeping the documentation in step

Documentation is part of every change, not a follow-up. In the same pull request as any
change it describes:

- **README.md** is the product front page: explain its value, show the console,
  and link to the live demo, guide and a concise local quick start. Keep CI policy,
  probe configuration, build internals, report paths and contributor tooling out
  of it; those belong here or in `deploy/README.md`, not in the product showcase.
- **The guide** (`docs/readme.src.html`, then rebuild `docs/index.html`) must match the code.
- **Screenshots** must be regenerated when the visible UI changes (layout, styles,
  wording, or displayed content). Backend or API changes alone do not require new
  screenshots when the rendered UI is unchanged:

  ```
  ./gradlew web --args="--port 18080"     # terminal 1: a fresh ledger
  python3 scripts/capture-screenshots.py  # terminal 2 (needs Playwright: see the script)
  ```

  Use `--customer-only` for Customer screenshots and mobile overflow checks at
  320, 390, 420, 430, 600 and 601 pixels, including long IDs and exact totals.

  Then look at every image before committing: screenshots have caught real bugs here,
  such as every view showing at once. Commit the updated files in `docs/screenshots/`.
- A PR that changes behaviour without updating these is incomplete. Say in the PR
  description which documents and screenshots were updated, or why none needed to be.

## Changing the web console

- The web layer is a **client** of the ledger. Every money movement goes through
  `SportsbookWallet` (or `Book`, which uses it); never write ledger state any other way.
- Dashboards read from `ReadModel`, which tails the ledger. Never base a money decision on
  it: balances shown to users come from the ledger itself.
- Keep customer reads independent of ledger size (issue #73). A new query must use an
  index the tailer maintains, never a scan of all entries, players or bets. An index may
  choose which entries a reader looks at, but anything shown as proof (the player audit)
  must still recompute from the entries and check the index against the ledger's own
  account version: count the distinct listed entries whose postings really touch the
  account, never just the length of the list. `ReadModel` queries share a read lock; only the tailer writes.
- Placing a bet and settling its fixture are coordinated by the per-fixture lock in `Book`.
  Keep it, and keep the race test in `WebAppTest`.
- Retain a slip's original player, stake, selection and bet ID while placement is
  pending or uncertain. Block price changes and Cancel, including after fixture
  polling, until acceptance or a known definitive status/error pair from the API.
  `404/not_found` and `413/payload_too_large` are definitive; never classify by
  HTTP status alone. An unknown or mismatched error code must retain the retry payload.
- Treat unreadable or truncated JSON in a successful API response as uncertain,
  never as an empty success. Parsing failures must retain the original wallet
  deposit/withdrawal or bet placement payload and idempotency key for retry.
- API POST form bodies are capped at 16 KiB (16,384 bytes) of encoded data, before
  UTF-8 or form decoding. Oversized bodies return HTTP 413 (`payload_too_large`) before
  routing, without performing the operation. Reject oversized declared lengths
  before reading and keep streaming reads bounded to 16,385 bytes (one extra byte
  detects overflow), using the buffer-taking `readNBytes(buffer, offset, length)` overload.
  Disable the JDK server's unread-body draining at startup, so closing a rejected
  unfinished upload neither reads beyond the cap nor holds the handler waiting for EOF.
- Keep the front end dependency-free (no frameworks, CDNs or build step) and keep the
  in-browser verification honest: the browser must compute the hashes and check the
  signature itself, never just display the server's verdict.

### API and wallet boundaries

On JDK 21, startup sets `sun.net.httpserver.drainAmount=0` before creating the HTTP
server. Fully consumed requests still permit keep-alive; unconsumed uploads close
instead of draining. The handler marks known-empty bodies at EOF and advertises
`Connection: close` until a form has been fully consumed; otherwise disabling
draining would silently retire empty GET connections. Never probe an unknown or
chunked body merely to preserve keep-alive.
This JDK-specific setting, like the request/response deadlines,
is read once: a hosting JVM must configure it before its first HTTP server is created.
An existing explicit property value is not overwritten; a nonzero override restores
JDK draining and forfeits the bounded-close guarantee.
Oversized-upload regressions stage the client: fixed-length bodies are withheld
after their oversized declaration, and chunked bodies stop at the 16,385-byte
overflow boundary without terminating the stream. Sending excess bytes after
early rejection can reset TCP and discard a valid response; never hide that
transport failure with catches or restore unbounded draining.
Origin-rejection clients withhold their bodies to prove validation precedes reads.
Raw test clients consume the declared response length and assert it is complete,
rather than reading beyond the HTTP frame into a connection reset.

- Nonempty API POST bodies require UTF-8 `application/x-www-form-urlencoded`
  (`415/unsupported_media_type` otherwise). Reject malformed UTF-8, percent escapes
  and fields, and duplicate decoded names, including query/body collisions
  (`400/bad_request`). Cap combined forms at 32 fields and non-liveness request
  URIs at 8,192 characters (`414/uri_too_long`).
- New deposits and placements require nonempty player IDs that fit the escrow
  account name (235 characters for a three-letter currency). New bet IDs must be
  nonblank and at most 245 characters, leaving room for settlement keys. Apply
  these admission checks only to uncommitted operations: legacy deposit,
  withdrawal and placement retries must still reach the ledger's fingerprint
  comparison, including withdrawals with empty player IDs or blank references.
  Older, longer cash accounts can still withdraw. Never change canonical byte
  formats or historical replay rules to enforce these wallet admission limits.
- Published or unresolved fixture results block new cash-outs before settlement
  finishes. Register cash-out futures under the fixture read lock, wait outside
  it, and retain the original future for same-bet retries after closure until
  its outcome is definitive. Committed retries reach the ledger's shared
  settlement-key fingerprint check. Callers sharing an admitted cash-out future
  receive replay-marked receipts; only its original caller receives the original
  receipt. The simulator uses this same book boundary.
- Amounts are positive whole `Long` minor units; the book supplies fixture odds
  and cash-out prices. Validate simulator actions, settings, booleans and event
  cursors before mutation. Web deposits cannot claim internal `sim-seed-` references.
- The owner-approved demo intentionally permits framing so About embeds the
  interactive guide within the console. Do not add anti-framing headers that
  block it. Check this browser flow and
  refresh its screenshot with `python3 scripts/capture-screenshots.py --about-only`.

The demo admission cap is not authentication or a production capacity policy.
Production still needs authenticated player/operator/regulator roles, per-action
authorization, confirmed payment integration and per-user request-rate limits.
Demo deposits do not prove that a payment was confirmed.

## Changing the guide

- Edit `docs/readme.src.html`, then run `build-readme.py` and `check-guide.mjs`.
  Commit the rebuilt `docs/index.html` with the change.
- Debugger steps point at code by quoting a distinctive line (`anchor`). If you change
  those lines in Kotlin, update the anchors; `check-guide.mjs` reports any that break.
- Never let the guide claim more than the code does. Requirement statuses mean:
  **In the code** = implemented and tested here; **Partly in the code**; **Designed** =
  specified but not built. Update a status when you implement a roadmap item.
- Every cloud component needs `svc`, `why` and `alt` for each provider (AWS, Azure,
  portable). Hedge vendor specifics you cannot verify, as the existing HSM entries do.

## Git and pull requests

- Never push to `main`. Work on a branch (`feature/<topic>` or `fix/<topic>`) and open a
  pull request; the owner reviews and merges.
- Keep commits small and focused, with an imperative subject line and a body that
  explains why.
- A PR description states what changed, why, and which checks you ran and their results.
- Before opening a PR: run the tests, rebuild the guide, and run `check-guide.mjs`.
- CI (`.github/workflows/ci.yml`) runs on every pull request: the test suite once,
  including race and stress tests, the demo with its audit, a check that `docs/index.html` was
  rebuilt, `check-guide.mjs`, and the README's one-command setup and demo on fresh Ubuntu
  and Windows machines. All CI jobs must pass before a PR can be merged. If CI
  fails, fix the cause; never skip, retry until green, or weaken the check.

## Security and data

- Never commit secrets or private keys. `Ed25519TreeHeadSigner.generate()` is for tests
  and the demo only; production signing goes through an HSM-backed `TreeHeadSigner`.
- Respect the residency design: plaintext ledger data and keys stay in the primary,
  in-country region. Do not add code or design that replicates unencrypted data or key
  material elsewhere.
