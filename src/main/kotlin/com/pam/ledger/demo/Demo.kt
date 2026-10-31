package com.pam.ledger.demo

import com.pam.ledger.Ed25519TreeHeadSigner
import com.pam.ledger.InsufficientFundsException
import com.pam.ledger.Ledger
import com.pam.ledger.LedgerConfig
import com.pam.ledger.LedgerVerifier
import com.pam.ledger.MerkleVerify
import com.pam.ledger.SportsbookWallet
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * End-to-end tour: 50 players, 16 threads hammering bets concurrently, then an
 * independent audit and a Merkle inclusion proof for one bet.
 *
 * Run with:  gradle run
 */
fun main() {
    val dir = Files.createTempDirectory("pam-ledger-demo")
    val signer = Ed25519TreeHeadSigner.generate()
    val config = LedgerConfig(directory = dir, ledgerId = "sportsbook-demo", signer = signer, checkpointEvery = 500)

    val players = (1..50).map { "p$it" }
    val placed = AtomicInteger()
    val rejected = AtomicInteger()
    val started = System.nanoTime()

    Ledger.open(config).use { ledger ->
        val wallet = SportsbookWallet(ledger)
        players.forEach { wallet.deposit(it, 100_00, "EUR", paymentRef = "psp-$it") } // €100 each

        // 16 threads, each placing and settling bets for random players.
        val pool = Executors.newFixedThreadPool(16)
        repeat(16) { t ->
            pool.submit {
                val rnd = Random(t)
                repeat(250) { i ->
                    val betId = "t$t-b$i"
                    val player = players[rnd.nextInt(players.size)]
                    try {
                        wallet.placeBet(player, betId, stake = rnd.nextLong(1_00, 40_00), currency = "EUR")
                        placed.incrementAndGet()
                        when (rnd.nextInt(4)) {
                            0 -> wallet.settleWin(betId, payout = rnd.nextLong(1_00, 80_00))
                            1, 2 -> wallet.settleLoss(betId)
                            else -> wallet.voidBet(betId)
                        }
                    } catch (e: InsufficientFundsException) {
                        rejected.incrementAndGet() // the ledger refused to let a player overdraw
                    }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(5, java.util.concurrent.TimeUnit.MINUTES)
        val ms = (System.nanoTime() - started) / 1_000_000

        val head = ledger.head()
        println("Committed ${head.size} entries in $ms ms (${placed.get()} bets placed, ${rejected.get()} rejected for funds)")
        println("p1 cash balance: €%.2f".format(wallet.cashBalance("p1", "EUR") / 100.0))

        // A player (or regulator) asks: "prove my deposit is in the ledger".
        val sth = ledger.checkpoint()
        val deposit = ledger.findByIdempotencyKey("deposit:psp-p1")!!
        val proof = ledger.inclusionProof(deposit.entry.seq, sth.head.treeSize)
        println("Inclusion proof for p1's deposit: ${proof.auditPath.size} hashes, valid=${proof.verify()}, signed root valid=${sth.verify(ledger.publicKey)}")

        // And: "prove you never rewrote history between checkpoints".
        val older = 100L
        val cons = ledger.consistencyProof(older, sth.head.treeSize)
        println("Consistency proof $older -> ${sth.head.treeSize}: " +
            MerkleVerify.consistency(older, sth.head.treeSize, ledger.merkleRoot(older), sth.head.rootHash, cons))
    }

    println()
    val report = LedgerVerifier.verify(dir, "sportsbook-demo", listOf(signer.publicKey))
    println(report)
    println("Ledger files are in $dir")
    // Exit non-zero if the independent audit fails, so CI treats it as a failure.
    if (!report.ok) exitProcess(1)
}
