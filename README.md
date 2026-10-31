# PAM Ledger

A cryptographically verifiable, append-only, double-entry ledger for a sportsbook
player account management (PAM) platform, written in Kotlin with no runtime
dependencies beyond JDK 21.

**Start with the interactive guide: [paulmaddox.github.io/ledger](https://paulmaddox.github.io/ledger/).**
It includes an architecture map, hash-chain and Merkle-proof
playgrounds, and a step-through debugger that walks six scenarios (bet placement,
double-spend race, cash-out vs result, redelivered webhook, power cut, history
rewrite) over the real source code.

## Guarantees

| | |
|---|---|
| Tamper-evident | SHA-256 hash chain, RFC 9162 Merkle tree, Ed25519 signed tree heads |
| Linearizable | Single writer thread with group commit; lock-free reads of committed data only |
| Exactly-once | Idempotency keys with content fingerprints; one settlement key per bet |
| Crash-safe | Receipts issued only after fsync; torn tails quarantined, mid-file damage refused |
| Auditable | `LedgerVerifier` re-derives everything from the files and a public key |

## Quick start

You need [Git](https://git-scm.com) to get the code. Everything else is installed by one
command. Then one more runs the demo.

**Ubuntu** (22.04 or newer):

```bash
git clone https://github.com/PaulMaddox/ledger.git && cd ledger
./scripts/setup.sh     # installs JDK 21 if needed, then does a first build
./gradlew run          # runs the demo
```

**Windows** (10 or 11), in PowerShell:

```powershell
git clone https://github.com/PaulMaddox/ledger.git; cd ledger
powershell -ExecutionPolicy Bypass -File scripts\setup.ps1   # installs JDK 21 with winget if needed, then does a first build
.\gradlew.bat run                                             # runs the demo
```

`-ExecutionPolicy Bypass` applies to that one run only: Windows blocks unsigned scripts
by default, and this does not change any setting.

The only thing installed system-wide is JDK 21. Gradle, the Kotlin compiler and the test
libraries are downloaded by the Gradle wrapper (`gradlew`) into your user folder on the
first build, which needs internet access and takes a few minutes. Both setup scripts are
safe to run again, and CI runs these exact commands on fresh Ubuntu and Windows machines.

## Running

| | Ubuntu | Windows |
|---|---|---|
| Run the demo | `./gradlew run` | `.\gradlew.bat run` |
| Run all tests | `./gradlew test` | `.\gradlew.bat test` |
| Run one test class | `./gradlew test --tests "com.pam.ledger.MultiDeviceWalletTest"` | `.\gradlew.bat test --tests "com.pam.ledger.MultiDeviceWalletTest"` |

Test reports are written to `build/reports/tests/test/index.html`.

### What the demo does

The demo (`src/main/kotlin/com/pam/ledger/demo/Demo.kt`) creates a fresh ledger in a
temporary folder. It then:

1. deposits €100 for each of 50 players;
2. has 16 threads place and settle bets concurrently;
3. proves one deposit with a Merkle inclusion proof, and the ledger's history with a consistency proof;
4. runs the independent verifier over the files it wrote.

Output from a real run (yours will differ in counts, timings and hashes):

```
Committed 3008 entries in 1721 ms (1479 bets placed, 2521 rejected for funds)
p1 cash balance: €436.50
Inclusion proof for p1's deposit: 12 hashes, valid=true, signed root valid=true
Consistency proof 100 -> 3008: true

VERIFIED ✔
  entries:            3008
  chain head:         67be1d0bf972bb7bbf18f534e604d2e1f3973b4584600985dcb6fdb5d4898e7f
  merkle root:        d7080e7e754147c2567f83b96ebe3a7ebd87e922d618fa476686cd3adff3be52
  checkpoints:        6 verified (latest covers 3008)
  currency totals:    {EUR=0} (each must be 0)

Ledger files are in /tmp/pam-ledger-demo…
```

Bets rejected for funds are expected: the threads deliberately try to overspend, and the
ledger refuses. The ledger files stay in the temporary folder so you can inspect them.
The demo exits with a non-zero status if the audit fails, which is how CI uses it.

To run the demo as a standalone program instead, build a distribution once with
`./gradlew installDist` (Windows: `.\gradlew.bat installDist`), then run
`build/install/pam-ledger/bin/pam-ledger` (Windows: `build\install\pam-ledger\bin\pam-ledger.bat`).

### Editing the interactive guide

Rebuilding and checking the guide also needs Python 3.10+ and Node.js 18+. Add them by
running the setup script with an extra option:

```
./scripts/setup.sh --with-guide-tools                                        # Ubuntu
powershell -ExecutionPolicy Bypass -File scripts\setup.ps1 -WithGuideTools   # Windows
```

Then:

```
python3 scripts/build-readme.py   # rebuild docs/index.html (Windows: python instead of python3)
node scripts/check-guide.mjs      # headless check of the guide
```

### Setting up by hand

The scripts only automate this: install JDK 21 (on Ubuntu `sudo apt install openjdk-21-jdk`;
on Windows `winget install EclipseAdoptium.Temurin.21.JDK`), then run any `gradlew` command.
Do not install Gradle separately; the wrapper fetches the exact version CI uses.

Contributing, including with coding agents: read [`AGENTS.md`](AGENTS.md) first. It lists
the invariants that must never be broken and the branch-and-pull-request workflow.

## Layout

```
src/main/kotlin/com/pam/ledger/
  Ledger.kt            engine: queue, writer thread, group commit, recovery
  SportsbookWallet.kt  deposits, withdrawals, bets, exactly-once settlement
  Replay.kt            balance rules, replay verification, checkpoint checks
  Journal.kt           CRC-framed append-only file, fsync, torn-write recovery
  Merkle.kt            incremental root, inclusion/consistency proofs
  Signing.kt           signed tree heads, pluggable signer (HSM/KMS)
  Hashes.kt, EntryCodec.kt, Validation.kt, Model.kt, Errors.kt, AppendOnlyList.kt
  LedgerVerifier.kt    offline auditor
src/test/kotlin/com/pam/ledger/
  MultiDeviceWalletTest.kt   regulatory properties R1–R6 under multi-device load
  ConcurrencyTest.kt, IntegrityTest.kt, MerkleTest.kt
docs/
  readme.src.html      template for the interactive guide
  index.html           built guide, served by GitHub Pages (python3 scripts/build-readme.py)
scripts/
  setup.sh, setup.ps1  one-command developer setup for Ubuntu and Windows
  build-readme.py       builds docs/index.html from the template and real sources
  check-guide.mjs      headless check: debugger anchors, cloud data, test links
```

## Before production

- Implement `TreeHeadSigner` against an HSM or cloud KMS.
- Publish signed tree heads to a party you do not control (regulator, transparency log).
- Move entry storage and Merkle nodes to an indexed store at large scale.
- Version `AccountPolicy` with the journal; replay enforces it.
