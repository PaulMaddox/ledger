package com.pam.ledger

/**
 * Structural rules every transaction must satisfy, applied both to NEW requests
 * and to entries READ BACK from disk. Having one rulebook for both directions
 * means a tampered journal is held to exactly the same standard as live traffic.
 */
internal object Validation {
    val CURRENCY = Regex("[A-Z]{3}")
    private val KIND = Regex("[A-Z][A-Z0-9_]{0,63}")

    const val MAX_POSTINGS = 64
    const val MAX_METADATA = 64
    const val MAX_KEY_LENGTH = 256
    const val MAX_META_KEY_LENGTH = 128
    const val MAX_META_VALUE_LENGTH = 2048

    fun checkTransaction(
        idempotencyKey: String,
        kind: String,
        postings: List<Posting>,
        metadata: Map<String, String>,
    ) {
        require(idempotencyKey.isNotEmpty() && idempotencyKey.length <= MAX_KEY_LENGTH) {
            "idempotency key must be 1..$MAX_KEY_LENGTH chars"
        }
        require(isWellFormedUnicode(idempotencyKey)) { "idempotency key is not well-formed unicode" }
        require(KIND.matches(kind)) { "kind must match ${KIND.pattern}, got '$kind'" }

        // At least two legs: a single posting cannot balance (amounts are non-zero).
        require(postings.size in 2..MAX_POSTINGS) { "a transaction needs 2..$MAX_POSTINGS postings" }

        // Each account at most once per transaction. Keeps the balance arithmetic
        // and the per-account version counter trivially correct.
        val seen = HashSet<AccountId>(postings.size * 2)
        for (p in postings) require(seen.add(p.account)) { "account ${p.account} appears twice in one transaction" }

        // THE double-entry rule: postings of each currency sum to zero.
        // Math.addExact throws on overflow instead of silently wrapping around,
        // so a crafted pair like (Long.MAX, Long.MAX, -2) cannot fake a zero sum.
        val sums = HashMap<String, Long>()
        for (p in postings) {
            val next = try {
                Math.addExact(sums[p.currency] ?: 0L, p.amount)
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("posting amounts overflow", e)
            }
            sums[p.currency] = next
        }
        for ((currency, sum) in sums) require(sum == 0L) { "postings in $currency sum to $sum, must be 0" }

        require(metadata.size <= MAX_METADATA) { "at most $MAX_METADATA metadata entries" }
        for ((k, v) in metadata) {
            require(k.isNotEmpty() && k.length <= MAX_META_KEY_LENGTH) { "metadata key length out of range" }
            require(v.length <= MAX_META_VALUE_LENGTH) { "metadata value too long" }
            require(isWellFormedUnicode(k) && isWellFormedUnicode(v)) { "metadata is not well-formed unicode" }
        }
    }

    /**
     * True if [s] contains no unpaired UTF-16 surrogates.
     *
     * Why care? Java silently replaces an unpaired surrogate with '?' when
     * encoding to UTF-8. Two DIFFERENT strings could then encode to the SAME
     * bytes, and therefore the same hash. A cryptographic ledger must make
     * "different content" always mean "different bytes", so such strings are banned.
     */
    fun isWellFormedUnicode(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return false
            i++
        }
        return true
    }
}
