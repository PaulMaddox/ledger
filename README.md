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

## Developer setup

| Tool | Version | Needed for |
|---|---|---|
| JDK | 21 (Eclipse Temurin recommended) | building, tests, the demo |
| Gradle | 8.14.x (CI uses 8.14.3) | building, tests, the demo |
| Git | any recent | cloning |
| Python | 3.10 or newer | only to rebuild the interactive guide |
| Node.js | 18 or newer | only to check the interactive guide |

The repository has no Gradle wrapper, so Gradle must be installed. The ledger has no
runtime dependencies beyond the JDK; Gradle downloads the Kotlin compiler and the test
libraries from Maven Central on the first build, so that build needs internet access.

### Windows

In PowerShell, using `winget` (built into Windows 10 and 11):

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK
winget install --id Git.Git
winget install --id Python.Python.3.12        # optional: guide rebuild
winget install --id OpenJS.NodeJS.LTS         # optional: guide check
```

Install Gradle 8.14.3 with [Chocolatey](https://chocolatey.org):

```powershell
choco install gradle --version=8.14.3   # run as Administrator
```

Alternatively, download `gradle-8.14.3-bin.zip` from
[gradle.org/releases](https://gradle.org/releases/), unzip it to `C:\Gradle`, and add
`C:\Gradle\gradle-8.14.3\bin` to your `Path`. Open a new terminal afterwards so the
updated `Path` and `JAVA_HOME` are picked up.

### Ubuntu

On Ubuntu 22.04 or 24.04:

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk git python3 unzip curl zip
```

Do not use Ubuntu's own `gradle` package, which is far too old. Install Gradle 8.14.3
with [SDKMAN!](https://sdkman.io):

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install gradle 8.14.3
```

For the optional guide check you need Node.js 18 or newer. On Ubuntu 24.04,
`sudo apt install -y nodejs` is enough; Ubuntu 22.04's package is too old, so use
[nvm](https://github.com/nvm-sh/nvm) or the [NodeSource](https://github.com/nodesource/distributions)
packages there.

### Check the installation

Both platforms:

```
java -version     # should report version 21
gradle --version  # should report Gradle 8.14.x and JVM 21
```

## Running

From the repository root, on Windows or Ubuntu:

```
gradle test   # the full suite: Merkle, integrity, concurrency, multi-device regulatory tests
gradle run    # the demo
```

Test reports are written to `build/reports/tests/test/index.html`. To run one test
class, for example the regulatory suite:

```
gradle test --tests "com.pam.ledger.MultiDeviceWalletTest"
```

### Running the demo

The demo (`src/main/kotlin/com/pam/ledger/demo/Demo.kt`) creates a fresh ledger in a
temporary folder. It then:

1. deposits €100 for each of 50 players;
2. has 16 threads place and settle bets concurrently;
3. proves one deposit with a Merkle inclusion proof, and the ledger's history with a consistency proof;
4. runs the independent verifier over the files it wrote.

`gradle run` builds and runs it in one step. To run it as a standalone program instead,
build a distribution once and then call its launcher:

```bash
gradle installDist
build/install/pam-ledger/bin/pam-ledger          # Ubuntu
```

```powershell
gradle installDist
build\install\pam-ledger\bin\pam-ledger.bat      # Windows
```

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

### Rebuilding the interactive guide

```
python3 scripts/build-readme.py   # rebuild docs/index.html (on Windows: python instead of python3)
node scripts/check-guide.mjs      # headless check of the guide
```

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
  build-readme.py       builds docs/index.html from the template and real sources
  check-guide.mjs      headless check: debugger anchors, cloud data, test links
```

## Before production

- Implement `TreeHeadSigner` against an HSM or cloud KMS.
- Publish signed tree heads to a party you do not control (regulator, transparency log).
- Move entry storage and Merkle nodes to an indexed store at large scale.
- Version `AccountPolicy` with the journal; replay enforces it.
