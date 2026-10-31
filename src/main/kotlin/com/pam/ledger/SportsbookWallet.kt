package com.pam.ledger

import java.util.concurrent.CompletionException

/*
 * ============================================================================
 *  SPORTSBOOK WALLET: the PAM-facing API on top of the generic ledger
 * ============================================================================
 *
 *  Chart of accounts (per currency):
 *
 *    player:{id}:cash:{CUR}        Withdrawable balance. Must stay >= 0.
 *    player:{id}:open-bets:{CUR}   Stakes held in escrow while a bet is open. >= 0.
 *    house:revenue:{CUR}           Operator's gross gaming revenue (wins minus losses).
 *    house:psp-clearing:{CUR}      Mirror of money in transit with payment providers.
 *
 *  Lifecycle of a bet, as ledger transactions:
 *
 *    placeBet   cash -stake                      open-bets +stake
 *    win        open-bets -stake   cash +payout  revenue -(payout - stake)
 *    loss       open-bets -stake                 revenue +stake
 *    void       open-bets -stake   cash +stake
 *    cash-out   open-bets -stake   cash +offer   revenue +(stake - offer)
 *
 *  EXACTLY-ONCE SETTLEMENT. Every way of resolving a bet uses the SAME
 *  idempotency key, `bet:{betId}:settle`. Therefore:
 *   - the settlement feed redelivering "WON" twice  -> second call is a harmless replay;
 *   - a race between "WON" and "VOID" (or a cash-out) -> exactly one wins, the
 *     other gets IdempotencyConflictException.
 *  No locks, no "is it settled yet?" check-then-act race. The ledger's
 *  idempotency index IS the guard.
 *
 *  Stakes, players and currencies for settlement are read back FROM THE LEDGER
 *  (the placement entry), never trusted from the caller, so a buggy settlement
 *  service cannot release more from escrow than was actually staked.
 *
 *  Re-settlement (a result changed after settlement) would be a NEW compensating
 *  transaction with its own key, e.g. `bet:{id}:resettle:{n}`. Entries are never edited.
 */
class SportsbookWallet(private val ledger: Ledger) {

    object Accounts {
        fun cash(playerId: String, currency: String) = AccountId("player:$playerId:cash:$currency")
        fun openBets(playerId: String, currency: String) = AccountId("player:$playerId:open-bets:$currency")
        fun revenue(currency: String) = AccountId("house:revenue:$currency")
        fun pspClearing(currency: String) = AccountId("house:psp-clearing:$currency")
    }

    /** Credit a confirmed deposit. [paymentRef] is the PSP's unique id, so webhook redeliveries are no-ops. */
    fun deposit(playerId: String, amount: Long, currency: String, paymentRef: String): Receipt {
        require(amount > 0) { "deposit must be positive" }
        return ledger.append(
            TransactionRequest(
                idempotencyKey = "deposit:$paymentRef",
                kind = "DEPOSIT",
                postings = listOf(
                    Posting(Accounts.pspClearing(currency), currency, -amount),
                    Posting(Accounts.cash(playerId, currency), currency, amount),
                ),
                metadata = mapOf("playerId" to playerId, "paymentRef" to paymentRef),
            ),
        )
    }

    /** Debit a withdrawal. Fails with [InsufficientFundsException] rather than overdrawing. */
    fun withdraw(playerId: String, amount: Long, currency: String, withdrawalId: String): Receipt {
        require(amount > 0) { "withdrawal must be positive" }
        return ledger.append(
            TransactionRequest(
                idempotencyKey = "withdrawal:$withdrawalId",
                kind = "WITHDRAWAL",
                postings = listOf(
                    Posting(Accounts.cash(playerId, currency), currency, -amount),
                    Posting(Accounts.pspClearing(currency), currency, amount),
                ),
                metadata = mapOf("playerId" to playerId, "withdrawalId" to withdrawalId),
            ),
        )
    }

    /**
     * Move [stake] from cash into escrow. Two concurrent bets for the same last
     * tenner are serialised by the writer thread: one succeeds, one gets
     * [InsufficientFundsException]. There is no window for a double spend.
     */
    fun placeBet(playerId: String, betId: String, stake: Long, currency: String, selection: String = ""): Receipt {
        return ledger.append(placementRequest(playerId, betId, stake, currency, selection))
    }

    /**
     * Await the original submission without timing out or abandoning it on interruption.
     * The book holds a fixture lock until this outcome is definitive; retry admission
     * failures must never release that lock while an earlier submission is still pending.
     */
    internal fun placeBetUntilDefinitive(playerId: String, betId: String, stake: Long, currency: String, selection: String): Receipt = try {
        ledger.submit(placementRequest(playerId, betId, stake, currency, selection)).join()
    } catch (e: CompletionException) {
        throw (e.cause as? LedgerException ?: e)
    }

    private fun placementRequest(playerId: String, betId: String, stake: Long, currency: String, selection: String): TransactionRequest {
        require(stake > 0) { "stake must be positive" }
        return TransactionRequest(
            idempotencyKey = placementKey(betId),
            kind = "BET_PLACED",
            postings = listOf(
                Posting(Accounts.cash(playerId, currency), currency, -stake),
                Posting(Accounts.openBets(playerId, currency), currency, stake),
            ),
            metadata = buildMap {
                put("betId", betId)
                put("playerId", playerId)
                if (selection.isNotEmpty()) put("selection", selection)
            },
        )
    }

    /** Bet won: return [payout] (stake included) to the player. */
    fun settleWin(betId: String, payout: Long) = settle(betId, "BET_WON", payout)

    /** Bet lost: the stake goes to the house. */
    fun settleLoss(betId: String) = settle(betId, "BET_LOST", 0)

    /** Bet voided (e.g. event abandoned): refund the stake exactly. */
    fun voidBet(betId: String): Receipt = settle(betId, "BET_VOIDED", placement(betId).stake)

    /** Player accepted a cash-out offer of [amount]. */
    fun cashOut(betId: String, amount: Long) = settle(betId, "BET_CASHED_OUT", amount)

    fun cashBalance(playerId: String, currency: String) = ledger.balance(Accounts.cash(playerId, currency))
    fun openBetsBalance(playerId: String, currency: String) = ledger.balance(Accounts.openBets(playerId, currency))

    /** True once the bet has been resolved in any way. */
    fun isSettled(betId: String) = ledger.findByIdempotencyKey(settlementKey(betId)) != null

    // ---------------------------------------------------------------------

    private fun settle(betId: String, kind: String, payout: Long): Receipt {
        require(payout >= 0) { "payout cannot be negative" }
        val p = placement(betId)
        val cur = p.currency
        // Revenue change = what the house keeps. Negative when the player wins more than they staked.
        val houseDelta = Math.subtractExact(p.stake, payout)
        val postings = buildList {
            add(Posting(Accounts.openBets(p.playerId, cur), cur, -p.stake)) // release escrow
            if (payout > 0) add(Posting(Accounts.cash(p.playerId, cur), cur, payout))
            if (houseDelta != 0L) add(Posting(Accounts.revenue(cur), cur, houseDelta))
        }
        return ledger.append(
            TransactionRequest(
                idempotencyKey = settlementKey(betId), // ONE key for every outcome: exactly-once
                kind = kind,
                postings = postings,
                metadata = mapOf("betId" to betId, "playerId" to p.playerId, "placementSeq" to p.seq.toString()),
            ),
        )
    }

    private data class Placement(val seq: Long, val playerId: String, val stake: Long, val currency: String)

    /** Reconstruct the bet from its placement entry, the single source of truth. */
    private fun placement(betId: String): Placement {
        val sealed = ledger.findByIdempotencyKey(placementKey(betId)) ?: throw NotFoundException("bet $betId was never placed")
        val e = sealed.entry
        val playerId = e.metadata["playerId"] ?: throw TamperDetectedException("placement of $betId has no playerId")
        val escrow = e.postings.single { it.amount > 0 && it.account.value.startsWith("player:$playerId:open-bets:") }
        return Placement(e.seq, playerId, escrow.amount, escrow.currency)
    }

    private fun placementKey(betId: String) = "bet:$betId:place"
    private fun settlementKey(betId: String) = "bet:$betId:settle"
}
