package com.pam.ledger

import java.util.Collections
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded, ledger-scoped sharing of repeated immutable strings (kinds, account ids,
 * player ids). Used by recovery, by the writer, and by reader threads that load an
 * entry back from the journal, so it is safe for concurrent use.
 */
internal class EntryStrings {
    private val values = ConcurrentHashMap<String, String>()
    private val count = AtomicInteger()
    internal val size get() = count.get()

    private fun shared(value: String): String {
        values[value]?.let { return it }
        if (value.length > MAX_LENGTH) return value
        // Reserve a slot before inserting, so concurrent callers can never exceed the bound.
        while (true) {
            val n = count.get()
            if (n >= MAX_STRINGS) return value
            if (count.compareAndSet(n, n + 1)) break
        }
        val existing = values.putIfAbsent(value, value) ?: return value
        count.decrementAndGet() // another thread inserted the same string first
        return existing
    }

    fun share(entry: LedgerEntry): LedgerEntry {
        val kind = shared(entry.kind)
        val postings = entry.postings.map { p ->
            val account = shared(p.account.value)
            val currency = shared(p.currency)
            if (account === p.account.value && currency === p.currency) p
            else Posting(AccountId(account), currency, p.amount)
        }
        val metadata = TreeMap<String, String>()
        for ((key, value) in entry.metadata) {
            // Payment references, bet IDs and sequence strings are predominantly unique.
            metadata[shared(key)] = if (key == "playerId" || key == "selection") shared(value) else value
        }
        return entry.copy(
            kind = kind,
            postings = Collections.unmodifiableList(postings),
            metadata = Collections.unmodifiableSortedMap(metadata),
        )
    }

    companion object {
        internal const val MAX_STRINGS = 65_536
        private const val MAX_LENGTH = 256
    }
}
