package com.pam.ledger

import com.pam.ledger.web.heapUsedAfterGc
import java.lang.management.MemoryUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PerformanceTest {
    private fun usage(used: Long) = MemoryUsage(0, used, used, -1)

    @Test fun `post-GC occupancy sums every heap pool and excludes non-heap pools`() {
        val pools = mapOf(
            "eden" to usage(10), "survivor" to usage(20), "old" to usage(300),
            "metaspace" to usage(900), "code" to usage(800),
        )
        assertEquals(330L, heapUsedAfterGc(pools, setOf("eden", "survivor", "old")))
    }

    @Test fun `missing heap pools are unavailable rather than a partial or zero measurement`() {
        assertNull(heapUsedAfterGc(mapOf("eden" to usage(10)), setOf("eden", "old")))
        assertNull(heapUsedAfterGc(emptyMap(), setOf("old")))
        assertNull(heapUsedAfterGc(mapOf("old" to usage(10)), emptySet()))
    }

    @Test fun `a measured empty heap is distinct from an unavailable measurement`() {
        assertEquals(0L, heapUsedAfterGc(mapOf("old" to usage(0)), setOf("old")))
    }
}
