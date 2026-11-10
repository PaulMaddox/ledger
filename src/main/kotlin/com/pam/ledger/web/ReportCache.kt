package com.pam.ledger.web

/**
 * Ledger-derived report sections, kept per signed checkpoint and request shape.
 *
 * A supervisory report is computed against a signed checkpoint. That prefix of the
 * ledger can never change, so computing the same report against it twice gives the
 * same answer; the second request does not need to scan the journal again.
 *
 * The cache is small and bounded: it holds the most recently used reports and drops
 * the least recently used. It belongs to one opened ledger and must be discarded
 * with it (a demo reset starts a new history, and with it a new cache).
 */
internal class ReportCache(private val capacity: Int = 4) {
    /** One report: which signed prefix, and which slice of it was asked for. */
    data class Key(
        val treeSize: Long, val rootHash: String, val currency: String, val population: String,
        val from: Long, val to: Long, val timezone: String,
    )

    // Access order, so the eldest entry is the least recently used.
    private val reports = object : LinkedHashMap<Key, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Any>) = size > capacity
    }
    var hits = 0L
        private set
    var misses = 0L
        private set

    val size: Int get() = synchronized(reports) { reports.size }

    /** The value cached for [key], computing and keeping it first if absent. [compute] runs outside the lock. */
    fun <T : Any> get(key: Key, compute: () -> T): T {
        synchronized(reports) {
            @Suppress("UNCHECKED_CAST")
            (reports[key] as T?)?.let { hits++; return it }
        }
        val computed = compute()
        synchronized(reports) {
            misses++
            reports[key] = computed
        }
        return computed
    }
}
