package com.pam.ledger

/**
 * Every failure the ledger reports on purpose. It is a `sealed` hierarchy, so a
 * `when` over it is checked by the compiler for completeness.
 *
 * The errors fall into two families:
 *
 *  - BUSINESS rejections (insufficient funds, version conflict...). Nothing was
 *    written; the caller can show a message or try something else.
 *  - INFRASTRUCTURE failures (overloaded, failed, outcome unknown...). For these
 *    the correct client behaviour is almost always: retry with the SAME
 *    idempotency key. The ledger guarantees that is safe.
 */
sealed class LedgerException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A non-negative account (e.g. player cash) would go below zero. */
class InsufficientFundsException(val account: AccountId, val balance: Long, val requested: Long) :
    LedgerException("insufficient funds in $account: balance=$balance, requested=$requested")

/** Account already holds a different currency than the posting. */
class CurrencyMismatchException(val account: AccountId, expected: String, actual: String) :
    LedgerException("account $account holds $expected, posting is in $actual")

/** Optimistic concurrency check failed: someone else changed the account first. */
class VersionConflictException(val account: AccountId, val expected: Long, val actual: Long) :
    LedgerException("account $account is at version $actual, caller expected $expected")

/**
 * The idempotency key was already used for a DIFFERENT transaction.
 * In the sportsbook this is what stops a bet being settled as both WON and LOST.
 */
class IdempotencyConflictException(val key: String) :
    LedgerException("idempotency key '$key' was already used for a different transaction")

/** A balance would overflow a 64-bit Long. Astronomically unlikely, still checked. */
class AmountOverflowException(val account: AccountId) : LedgerException("balance overflow on $account")

/** Back-pressure: the submission queue stayed full for the whole submit timeout. Nothing was written. */
class OverloadedException : LedgerException("ledger is overloaded, retry later with the same idempotency key")

/** The ledger has been closed. */
class LedgerClosedException : LedgerException("ledger is closed")

/**
 * The ledger hit an I/O or internal error and has entered FAIL-STOP mode: it
 * refuses all further writes until restarted. A ledger that keeps going after
 * a failed disk write is a ledger whose memory and disk may disagree. Refusing
 * service is the safe choice; reopening re-derives state from disk.
 */
class LedgerFailedException(cause: Throwable) : LedgerException("ledger failed and is now read-only: ${cause.message}", cause)

/**
 * The caller stopped waiting before learning the outcome. The transaction MAY
 * have been committed. Retry with the same idempotency key to find out.
 */
class OutcomeUnknownException(val key: String) :
    LedgerException("timed out waiting for '$key'; outcome unknown, retry with the same key")

/** The stored data fails a cryptographic or accounting check. Do not trust it. */
class TamperDetectedException(message: String) : LedgerException(message)

/** A referenced entity (e.g. a bet) does not exist in the ledger. */
class NotFoundException(message: String) : LedgerException(message)
