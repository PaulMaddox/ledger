# Working on this repository

Instructions for coding agents (and humans) changing this project. Read it fully
before your first change: several rules protect properties that tests alone cannot.

## What this is

A cryptographically verifiable, append-only, double-entry ledger for a sportsbook
player account platform, in Kotlin on JDK 21, plus an interactive guide published
with GitHub Pages. The ledger's job is to make money movements exact, durable and
provable to a regulator. Correctness outranks speed, features and tidiness.

## Layout

```
src/main/kotlin/com/pam/ledger/
  Ledger.kt              engine: queue, single writer thread, group commit, recovery
  SportsbookWallet.kt    deposits, withdrawals, bets, exactly-once settlement
  Replay.kt              balance rules, replay verification, checkpoint checks
  Journal.kt             CRC-framed append-only file, fsync, torn-write recovery, lock
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
./gradlew test                       # full test suite (JUnit 5); gradlew.bat on Windows
./gradlew run                        # demo: concurrent betting, audit, proofs
./gradlew web                        # web console at http://localhost:8080
python3 scripts/build-readme.py      # rebuild docs/index.html after ANY source or guide change
node scripts/check-guide.mjs         # must print four PASS lines
```

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
   fixture file and reseeds a fresh demo. Never expose this operation in production
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

## Changing code

- Follow the existing comment style: explain *why*, not *what*, and keep KDoc on public
  APIs. This codebase is meant to be read by auditors as well as engineers.
- New behaviour needs tests. Concurrency changes need a race test (`TestSupport.race`);
  an intermittent failure is a real bug, not a reason to rerun until green.
- Keep error types in the sealed `LedgerException` hierarchy.

## Keeping the documentation in step

Documentation is part of every change, not a follow-up. In the same pull request as any
change it describes:

- **README.md** must describe what the code now does: commands, features, layout.
- **The guide** (`docs/readme.src.html`, then rebuild `docs/index.html`) must match the code.
- **Screenshots** must be regenerated when the visible UI changes (layout, styles,
  wording, or displayed content). Backend or API changes alone do not require new
  screenshots when the rendered UI is unchanged:

  ```
  ./gradlew web --args="--port 18080"     # terminal 1: a fresh ledger
  python3 scripts/capture-screenshots.py  # terminal 2 (needs Playwright: see the script)
  ```

  Then look at every image before committing: screenshots have caught real bugs here,
  such as every view showing at once. Commit the updated files in `docs/screenshots/`.
- A PR that changes behaviour without updating these is incomplete. Say in the PR
  description which documents and screenshots were updated, or why none needed to be.

## Changing the web console

- The web layer is a **client** of the ledger. Every money movement goes through
  `SportsbookWallet` (or `Book`, which uses it); never write ledger state any other way.
- Dashboards read from `ReadModel`, which tails the ledger. Never base a money decision on
  it: balances shown to users come from the ledger itself.
- Placing a bet and settling its fixture are coordinated by the per-fixture lock in `Book`.
  Keep it, and keep the race test in `WebAppTest`.
- Keep the front end dependency-free (no frameworks, CDNs or build step) and keep the
  in-browser verification honest: the browser must compute the hashes and check the
  signature itself, never just display the server's verdict.

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
