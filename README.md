<div align="center">

# Sportsbook Ledger

### Money moves. Proof follows.

An append-only, double-entry ledger for sportsbook player accounts.<br>
Exact balances. Safe concurrency. Evidence you can verify yourself.

**[Try the live console](https://paulmaddox-ledger-demo.swedencentral.cloudapp.azure.com/)** &nbsp;&middot;&nbsp; [Explore the interactive guide](https://paulmaddox.github.io/ledger/)

</div>

## Every balance has a history

A wallet is more than its latest number. This ledger records the movements behind
it, protects the money under competing requests, and lets you inspect the evidence.

- **Account for every cent.** Money is conserved, and player balances cannot go negative.
- **Compete without double spending.** Simultaneous bets and withdrawals cannot spend the same funds twice.
- **Retry without paying twice.** Repeating an operation cannot move money again.
- **Keep the receipt.** An accepted transaction survives a restart.
- **Check the history.** Independent audits and cryptographic proofs reveal tampering.

## One ledger. Six perspectives.

### Operator

Follow revenue and exposure, settle fixtures, and watch transactions arrive.

[![Operator dashboard with revenue, exposure, fixtures and live transactions](docs/screenshots/operator.png)](https://paulmaddox-ledger-demo.swedencentral.cloudapp.azure.com/)

### Customer

Manage a wallet, place bets and cash out. Race five devices for the same balance,
then inspect the receipts.

![Customer wallet, five-device race, betting markets and transaction history](docs/screenshots/customer.png)

### Regulator

Audit the ledger independently, check signed checkpoints, and verify that entries
belong to an unaltered history.

![Regulator dashboard with signed checkpoints, independent audit and proof tools](docs/screenshots/regulator.png)

### Simulator

Put hundreds of automated customers to work making bets, deposits, withdrawals
and cash-outs. Follow each wallet's latest actions as they happen.

![Simulator controls and automated customers streaming their latest actions](docs/screenshots/simulator.png)

### Administrator

Monitor performance and storage, and reset the demo when you want a fresh start.

![Administrator dashboard with throughput, CPU, memory, storage and reset controls](docs/screenshots/administrator.png)

### Chaos Monkey

Test conflicting settlements and failure recovery. Inspect the damage and see
what the ledger refuses to accept.

![Chaos Monkey experiments and their accounting and integrity results](docs/screenshots/chaos.png)

## Don't take the dashboard's word for it

Open a receipt and verify it in your own browser. The browser checks the
transaction, its proof and its signature itself. The **Regulator** view goes
further, independently auditing the ledger files and checking signed checkpoints.

![A cash-out receipt with balanced postings and a successful independent browser verification](docs/screenshots/customer-verify.png)

**Go inside the ledger:** the [interactive guide](https://paulmaddox.github.io/ledger/)
connects the architecture to real code, with proof playgrounds and step-through
explanations.

> [!IMPORTANT]
> **Public demo. Synthetic money. No authentication.**
> Anyone can use every view, damage history with Chaos Monkey, or permanently
> clear it with Administrator reset. History resets daily and simulator sessions
> are time-limited. Never use real accounts, private customer data or production keys.

## Run it locally

With [Git](https://git-scm.com) installed, choose your platform. After starting
the console, open **<http://localhost:8080>**.

<details>
<summary><strong>Ubuntu 22.04 or newer</strong></summary>

```bash
git clone https://github.com/PaulMaddox/ledger.git && cd ledger
./scripts/setup.sh
./gradlew web
```

</details>

<details>
<summary><strong>Windows 10 or 11 · PowerShell</strong></summary>

```powershell
git clone https://github.com/PaulMaddox/ledger.git; cd ledger
powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
.\gradlew.bat web
```

</details>

---

[Architecture, proofs and source](https://paulmaddox.github.io/ledger/) &nbsp;&middot;&nbsp;
[Contributing](AGENTS.md) &nbsp;&middot;&nbsp;
[Host your own demo](deploy/README.md)
