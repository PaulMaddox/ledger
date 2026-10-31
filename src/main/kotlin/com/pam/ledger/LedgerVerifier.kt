package com.pam.ledger

import java.nio.file.Path
import java.security.PublicKey

/**
 * OFFLINE AUDITOR. Point it at a copy of a ledger directory plus the trusted
 * public key(s), and it re-derives everything from scratch:
 *
 *  1. every entry: encoding, sequence, hash chain, accounting rules;
 *  2. every signed checkpoint: signature, Merkle root, chain head;
 *  3. conservation of money: per currency, all balances sum to exactly zero.
 *
 * It opens files READ-ONLY and takes no lock, so it can audit a live ledger's
 * backup, a regulator's copy, or (carefully) the live directory itself.
 *
 * For maximum independence an auditor may reimplement the documented byte
 * formats in another language; nothing here depends on the running engine.
 */
object LedgerVerifier {

    data class Report(
        val ok: Boolean,
        val entries: Long,
        val head: LedgerHead?,
        val checkpointsVerified: Int,
        val latestCheckpoint: SignedTreeHead?,
        val tornTailBytes: Long,
        val totalsByCurrency: Map<String, Long>,
        val problems: List<String>,
    ) {
        override fun toString() = buildString {
            appendLine(if (ok) "VERIFIED ✔" else "VERIFICATION FAILED ✘")
            appendLine("  entries:            $entries")
            head?.let {
                appendLine("  chain head:         ${it.chainHead.toHex()}")
                appendLine("  merkle root:        ${it.merkleRoot.toHex()}")
            }
            appendLine("  checkpoints:        $checkpointsVerified verified" + (latestCheckpoint?.let { " (latest covers ${it.head.treeSize})" } ?: ""))
            appendLine("  currency totals:    $totalsByCurrency (each must be 0)")
            if (tornTailBytes > 0) appendLine("  torn tail ignored:  $tornTailBytes bytes")
            problems.forEach { appendLine("  PROBLEM: $it") }
        }
    }

    fun verify(
        directory: Path,
        ledgerId: String,
        trustedKeys: Collection<PublicKey>,
        policy: AccountPolicy = AccountPolicy.PLAYER_ACCOUNTS_NON_NEGATIVE,
    ): Report {
        val problems = ArrayList<String>()
        val replayer = Replayer(ledgerId, policy)
        var torn = 0L
        var checkpoints = 0
        var latest: SignedTreeHead? = null

        try {
            torn += Journal.scanReadOnly(directory.resolve(Ledger.ENTRIES_FILE)) { replayer.apply(it) }.tornTailBytes

            val prover = MerkleProver { replayer.entries[it.toInt()].leafHash }
            val trusted = trustedKeys.associateBy(::keyIdOf)
            val cpScan = Journal.scanReadOnly(directory.resolve(Ledger.CHECKPOINTS_FILE)) { bytes ->
                val sth = SignedTreeHead.decode(bytes)
                CheckpointCheck.verify(sth, ledgerId, trusted, replayer, prover, latest)
                latest = sth
                checkpoints += 1
            }
            torn += cpScan.tornTailBytes
            if (latest == null && replayer.size > 0) {
                problems += "no signed checkpoint: history is hash-chained but not anchored to a signature"
            }
        } catch (e: TamperDetectedException) {
            problems += e.message ?: "tamper detected"
        } catch (e: Exception) {
            problems += "unreadable ledger: ${e.message}"
        }

        // Conservation of money. Implied by per-transaction balance, but auditors
        // like to see the books close with their own eyes.
        val totals = replayer.accounts.values.groupBy { it.currency }.mapValues { (_, accts) -> accts.sumOf { it.balance } }
        totals.filterValues { it != 0L }.forEach { (cur, sum) -> problems += "money not conserved in $cur: accounts sum to $sum" }

        return Report(problems.isEmpty(), replayer.size, replayer.head(), checkpoints, latest, torn, totals, problems)
    }
}
