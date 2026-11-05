package com.pam.ledger

import java.util.Collections
import java.util.TreeMap

/** Bounded, ledger-scoped sharing of repeated immutable strings; used only by recovery and its writer. */
internal class EntryStrings {
    private val values = HashMap<String, String>()
    internal val size get() = values.size

    private fun shared(value: String): String {
        values[value]?.let { return it }
        if (values.size < MAX_STRINGS && value.length <= MAX_LENGTH) values[value] = value
        return value
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
