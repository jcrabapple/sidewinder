package net.snakepit.sidewinder.core.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [FilterCache.peek]: the non-suspending read used by
 * synchronous render paths. It must return the cached list even when stale
 * (fetchedAtMs long past), and null when the token was never fetched.
 */
class FilterCachePeekTest {

    private fun filter(id: String) = FilterV2(
        id = id,
        title = "Filter $id",
        context = listOf("home"),
        filter_action = "warn",
        keywords = listOf(FilterKeyword(id = "k1", keyword = "spoilers")),
    )

    @Test
    fun peekReturnsNullWhenNeverFetched() {
        assertNull(FilterCache.peek("token-never-fetched"))
    }

    @Test
    fun peekReturnsCachedListEvenWhenStale() {
        FilterCache.cache["token-stale"] = FilterCache.Entry(listOf(filter("f1")), fetchedAtMs = 0L)
        try {
            assertEquals(listOf("f1"), FilterCache.peek("token-stale")?.map { it.id })
        } finally {
            FilterCache.cache.remove("token-stale")
        }
    }

    @Test
    fun invalidateDropsTheEntry() {
        FilterCache.cache["token-gone"] = FilterCache.Entry(listOf(filter("f2")), fetchedAtMs = 0L)
        FilterCache.invalidate("token-gone")
        assertNull(FilterCache.peek("token-gone"))
    }
}
