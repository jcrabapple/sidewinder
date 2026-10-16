package net.snakepit.sidewinder.core.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Negative caching after a failed filter fetch ([FilterCache.failureEntry]). */
class FilterCacheFailureTest {

    @Test
    fun failureWithNoHistoryCachesEmptyFiltersForTheRetryWindow() {
        val now = 1_000_000L
        val entry = FilterCache.failureEntry(null, now)
        assertEquals(emptyList<FilterV2>(), entry.filters)
        assertTrue(FilterCache.isFresh(entry, now))
        assertTrue(FilterCache.isFresh(entry, now + FilterCache.RETRY_AFTER_FAILURE_MS - 1))
        assertFalse(FilterCache.isFresh(entry, now + FilterCache.RETRY_AFTER_FAILURE_MS))
    }

    @Test
    fun failureKeepsTheLastKnownFilters() {
        val known = listOf<FilterV2>()
        val previous = FilterCache.Entry(known, 0L)
        val entry = FilterCache.failureEntry(previous, 500_000L)
        assertTrue(entry.filters === known)
    }
}
