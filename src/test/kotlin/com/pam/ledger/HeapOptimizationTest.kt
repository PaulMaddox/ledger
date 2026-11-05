package com.pam.ledger

import com.pam.ledger.TestSupport.config
import com.pam.ledger.TestSupport.race
import com.pam.ledger.TestSupport.tempDir
import com.pam.ledger.web.LongHistory
import com.pam.ledger.web.ReadModel
import java.util.TreeMap
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeapOptimizationTest {
    private fun text(value: String) = String(value.toCharArray())
    private fun entry(seq: Long, account: String = "house:sample:EUR") = LedgerEntry(
        seq, seq, UUID(0, seq), Hashes.genesis("strings"), "key-$seq", text("ADJUSTMENT"),
        listOf(Posting(AccountId(text(account)), text("EUR"), 1),
            Posting(AccountId(text("house:other:EUR")), text("EUR"), -1)),
        TreeMap(mapOf(text("playerId") to text("alice"), text("reference") to "reference-$seq")),
    )

    @Test
    fun `string sharing preserves canonical bytes hashes and immutable entry fields`() {
        val strings = EntryStrings()
        val original = entry(0)
        val first = strings.share(original)
        val second = strings.share(entry(1))
        assertContentEquals(EntryCodec.encode(original), EntryCodec.encode(first))
        assertEquals(SealedEntry.seal(original).leafHash, SealedEntry.seal(first).leafHash)
        assertEquals(SealedEntry.seal(original).chainHash, SealedEntry.seal(first).chainHash)
        assertTrue(first.kind === second.kind)
        assertTrue(first.postings[0].account.value === second.postings[0].account.value)
        assertTrue(first.postings[0].currency === second.postings[0].currency)
        assertTrue(first.metadata.getValue("playerId") === second.metadata.getValue("playerId"))
        assertTrue(first.metadata.keys.first() === second.metadata.keys.first())
        assertEquals("reference-1", second.metadata["reference"])
        original.metadata["playerId"] = "changed"
        assertEquals("alice", first.metadata["playerId"])
        assertFailsWith<UnsupportedOperationException> { first.metadata["playerId"] = "changed" }
    }

    @Test
    fun `string sharing remains bounded under high cardinality input`() {
        val strings = EntryStrings()
        repeat(EntryStrings.MAX_STRINGS + 100) { strings.share(entry(it.toLong(), "house:unique-$it:EUR")) }
        assertEquals(EntryStrings.MAX_STRINGS, strings.size)
        val original = entry(100_000, "house:after-limit:EUR")
        assertContentEquals(EntryCodec.encode(original), EntryCodec.encode(strings.share(original)))
        assertEquals(EntryStrings.MAX_STRINGS, strings.size)
    }

    @Test
    fun `recovery and subsequent writes share strings without changing retries or proofs`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        var bytes = byteArrayOf()
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit(text("alice"), 100, text("EUR"), "first")
            wallet.deposit(text("alice"), 100, text("EUR"), "second")
            assertTrue(ledger.entry(0).entry.kind === ledger.entry(1).entry.kind)
            assertTrue(ledger.entry(0).entry.postings[1].account.value === ledger.entry(1).entry.postings[1].account.value)
            bytes = ledger.entry(0).encodedBytes()
        }
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit(text("alice"), 100, text("EUR"), "third")
            assertTrue(ledger.entry(0).entry.kind === ledger.entry(2).entry.kind)
            assertTrue(ledger.entry(0).entry.postings[1].account.value === ledger.entry(2).entry.postings[1].account.value)
            assertContentEquals(bytes, ledger.entry(0).encodedBytes())
            assertTrue(wallet.deposit("alice", 100, "EUR", "first").replayed)
            assertFailsWith<IdempotencyConflictException> { wallet.deposit("alice", 101, "EUR", "first") }
            assertEquals(3L, ledger.head().size)
            assertTrue(ledger.inclusionProof(0).verify())
        }
        assertTrue(LedgerVerifier.verify(dir, TestSupport.LEDGER_ID, listOf(signer.publicKey)).ok)
    }

    @Test
    fun `primitive histories cross chunk boundaries preserve Long precision and return independent snapshots`() {
        val history = LongHistory()
        val base = 9_007_199_254_740_993L
        repeat(4_099) { history.add(base + it) }
        assertEquals(4_099, history.size)
        assertEquals((0 until 4_099).map { base + it }, history.tail())
        val tail = history.tail(40)
        history.add(Long.MAX_VALUE)
        assertEquals((4_059 until 4_099).map { base + it }, tail)
        assertEquals(listOf(Long.MAX_VALUE), history.tail(1))
        assertTrue(history.tail(0).isEmpty())
        assertFailsWith<IllegalArgumentException> { history.tail(-1) }
        val unordered = LongHistory()
        listOf(Long.MAX_VALUE, base, -1L, base - 1).forEach(unordered::add)
        assertEquals(listOf(-1L, base - 1, base, Long.MAX_VALUE), unordered.sorted())
    }

    @Test
    fun `settled views reconstruct complete history and retain only open decoded views`() {
        val dir = tempDir()
        val signer = Ed25519TreeHeadSigner.generate()
        Ledger.open(config(dir, signer)).use { ledger ->
            val wallet = SportsbookWallet(ledger)
            wallet.deposit("alice", 100_000, "EUR", "views")
            repeat(300) { wallet.placeBet("alice", "view-$it", 100, "EUR", "F1|HOME|200") }
            ReadModel(ledger).use { model ->
                model.catchUp()
                val pending = model.bet("view-0")!!
                race(2) { worker ->
                    if (worker == 0) repeat(299) { i ->
                        when (i % 4) {
                            0 -> wallet.settleWin("view-$i", 200)
                            1 -> wallet.settleLoss("view-$i")
                            2 -> wallet.voidBet("view-$i")
                            else -> wallet.cashOut("view-$i", 85)
                        }
                    } else repeat(100) {
                        model.catchUp()
                        for (bet in model.betsForPlayer("alice", 40)) {
                            assertEquals(bet.betId, ledger.entry(bet.placedSeq).entry.metadata["betId"])
                            bet.settledSeq?.let { seq ->
                                assertEquals("bet:${bet.betId}:settle", ledger.entry(seq).entry.idempotencyKey)
                            }
                        }
                    }
                }.forEach { it.getOrThrow() }
                model.catchUp()
                assertTrue(pending.isOpen, "a previously returned view remains an immutable snapshot")
                assertEquals("WON", model.bet("view-0")!!.outcome)
                assertEquals("CASHED_OUT", model.bet("view-3")!!.outcome)
                assertEquals(85L, model.bet("view-3")!!.payout)
                assertEquals(listOf("view-299"), model.openBets().map { it.betId })
                assertEquals((0 until 300).map { "view-$it" }, model.allBets().map { it.betId })
                assertEquals((260 until 300).map { "view-$it" }, model.betsForPlayer("alice", 40).map { it.betId })
                val history = model.historyForPlayer("alice", 40)
                assertEquals(600, history.entries)
                assertEquals(model.seqsForPlayer("alice").takeLast(40), history.seqs)
                val cache = ReadModel::class.java.getDeclaredField("openViews").apply { isAccessible = true }
                assertEquals(1, (cache.get(model) as Map<*, *>).size, "settled decoded views must not remain cached")
            }
        }
        Ledger.open(config(dir, signer)).use { ledger ->
            ReadModel(ledger).use { model ->
                model.catchUp()
                assertEquals(300, model.allBets().size)
                assertFalse(model.bet("view-3")!!.isOpen)
                assertEquals(85L, model.bet("view-3")!!.payout)
                assertEquals(listOf("view-299"), model.openBetsForPlayer("alice").map { it.betId })
            }
        }
    }

    @Test
    fun `historical views preserve attribution for nonstandard business keys`() {
        Ledger.open(config(tempDir(), Ed25519TreeHeadSigner.generate())).use { ledger ->
            ledger.append(TransactionRequest("legacy-place", "BET_PLACED", listOf(
                Posting(AccountId("house:funds:EUR"), "EUR", -100),
                Posting(AccountId("player:alice:open-bets:EUR"), "EUR", 100)),
                mapOf("playerId" to "alice", "betId" to "legacy", "selection" to "F1|HOME|200")))
            ledger.append(TransactionRequest("legacy-outcome", "BET_VOIDED", listOf(
                Posting(AccountId("player:alice:open-bets:EUR"), "EUR", -100),
                Posting(AccountId("player:alice:cash:EUR"), "EUR", 100)),
                mapOf("playerId" to "alice", "betId" to "legacy")))
            ReadModel(ledger).use { model ->
                model.catchUp()
                assertEquals("VOIDED", model.bet("legacy")!!.outcome)
                assertEquals(1L, model.bet("legacy")!!.settledSeq)
                assertEquals(100L, model.bet("legacy")!!.payout)
            }
        }
    }
}
