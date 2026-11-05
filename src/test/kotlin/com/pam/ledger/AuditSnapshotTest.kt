package com.pam.ledger

import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import com.pam.ledger.web.Audit
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuditSnapshotTest {
    @Test
    fun `concurrent independent audits and tamper copies preserve the live ledger`() {
        val directory = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(directory, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            repeat(37) { wallet.deposit("p", 100, "EUR", "audit-$it") }
            val before = ledger.head()
            val entries = Files.readAllBytes(directory.resolve(Ledger.ENTRIES_FILE))
            Audit(ledger, directory, TestSupport.LEDGER_ID, listOf(signer.publicKey)).use { audit ->
                race(6) { worker ->
                    if (worker % 2 == 0) assertTrue(audit.fullAudit().report.ok)
                    else assertFalse(audit.tamper(listOf("edit", "rewrite", "truncate")[worker / 2]).report.ok)
                }.forEach { it.getOrThrow() }
                assertEquals(before, ledger.head())
                assertContentEquals(entries, Files.readAllBytes(directory.resolve(Ledger.ENTRIES_FILE)))
                assertEquals(3_700L, wallet.cashBalance("p", "EUR"))
                assertTrue(audit.fullAudit().report.ok)
            }
        }
    }

    @Test
    fun `snapshot copies exact signed byte prefixes while later entries and checkpoints append`() {
        val directory = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(directory, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            race(8) { worker ->
                repeat(129) { wallet.deposit("p", 1, "EUR", "prefix-$worker-$it") }
            }.forEach { it.getOrThrow() }
            Audit(ledger, directory, TestSupport.LEDGER_ID, listOf(signer.publicKey)).use { audit ->
                val anchor = audit.checkpointNow()
                val entries = Files.readAllBytes(directory.resolve(Ledger.ENTRIES_FILE))
                val checkpoints = Files.readAllBytes(directory.resolve(Ledger.CHECKPOINTS_FILE))
                assertTrue(entries.size > 64 * 1024, "the copy must cross buffer boundaries")
                wallet.deposit("p", 1, "EUR", "after-anchor")
                ledger.checkpoint()
                race(2) { worker ->
                    if (worker == 0) {
                        repeat(32) {
                            wallet.deposit("p", 1, "EUR", "concurrent-$it")
                            ledger.checkpoint()
                        }
                    } else {
                        val snapshot = audit.snapshot(anchor)
                        try {
                            assertContentEquals(entries, Files.readAllBytes(snapshot.resolve(Ledger.ENTRIES_FILE)))
                            assertContentEquals(checkpoints, Files.readAllBytes(snapshot.resolve(Ledger.CHECKPOINTS_FILE)))
                            val report = LedgerVerifier.verify(snapshot, TestSupport.LEDGER_ID, listOf(signer.publicKey))
                            assertTrue(report.ok, report.problems.toString())
                            assertEquals(anchor.head.treeSize, report.entries)
                            assertContentEquals(anchor.encode(), assertNotNull(report.latestCheckpoint).encode())
                        } finally {
                            snapshot.toFile().deleteRecursively()
                        }
                    }
                }.forEach { it.getOrThrow() }
                assertEquals(anchor.head.treeSize + 33, ledger.head().size)
                assertTrue(Files.size(directory.resolve(Ledger.ENTRIES_FILE)) > entries.size)
                assertTrue(audit.fullAudit().report.ok)
            }
        }
    }

    @Test
    fun `an empty signed prefix remains empty after subsequent money movements`() {
        val directory = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(directory, signer)).use { ledger ->
            Audit(ledger, directory, TestSupport.LEDGER_ID, listOf(signer.publicKey)).use { audit ->
                val anchor = audit.checkpointNow()
                val checkpoints = Files.readAllBytes(directory.resolve(Ledger.CHECKPOINTS_FILE))
                SportsbookWallet(ledger).deposit("p", 1, "EUR", "after-empty-anchor")
                ledger.checkpoint()
                val snapshot = audit.snapshot(anchor)
                try {
                    assertEquals(0L, Files.size(snapshot.resolve(Ledger.ENTRIES_FILE)))
                    assertContentEquals(checkpoints, Files.readAllBytes(snapshot.resolve(Ledger.CHECKPOINTS_FILE)))
                    val report = LedgerVerifier.verify(snapshot, TestSupport.LEDGER_ID, listOf(signer.publicKey))
                    assertTrue(report.ok, report.problems.toString())
                    assertEquals(0L, report.entries)
                    assertContentEquals(anchor.encode(), assertNotNull(report.latestCheckpoint).encode())
                } finally {
                    snapshot.toFile().deleteRecursively()
                }
                assertEquals(1L, ledger.head().size)
            }
        }
    }
}
