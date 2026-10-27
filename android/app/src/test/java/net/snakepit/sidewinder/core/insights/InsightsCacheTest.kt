package net.snakepit.sidewinder.core.insights

import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for the insights cache that survives rotation. */
class InsightsCacheTest {

    @After
    fun clear() {
        InsightsCache.invalidate("a")
        InsightsCache.invalidate("b")
        InsightsCache.nanoTime = System::nanoTime
    }

    private fun insights(fetched: Int) = InsightsRepository.Insights(
        fetched = fetched,
        posts = fetched,
        boostsGiven = 0,
        favouritesReceived = 0L,
        repliesMade = 0,
        topHashtags = emptyList(),
        medianEngagement = 0L,
    )

    @Test
    fun roundTripBySession() {
        InsightsCache.put("a", insights(5))
        assertEquals(5, InsightsCache.get("a")?.fetched)
        assertNull(InsightsCache.get("b"))
    }

    @Test
    fun freshEntryIsReusedBeforeFiveMinutes() {
        var now = 0L
        InsightsCache.nanoTime = { now }
        InsightsCache.put("a", insights(5))

        now = TimeUnit.MINUTES.toNanos(5) - 1L

        assertEquals(5, InsightsCache.get("a")?.fetched)
    }

    @Test
    fun entryExpiresAtFiveMinutes() {
        var now = 0L
        InsightsCache.nanoTime = { now }
        InsightsCache.put("a", insights(5))

        now = TimeUnit.MINUTES.toNanos(5)

        assertNull(InsightsCache.get("a"))
    }

    @Test
    fun invalidateDropsOnlyThatSession() {
        var now = 0L
        InsightsCache.nanoTime = { now }
        InsightsCache.put("a", insights(5))
        InsightsCache.put("b", insights(7))

        now = TimeUnit.MINUTES.toNanos(1)
        InsightsCache.invalidate("a")
        assertNull(InsightsCache.get("a"))
        assertEquals(7, InsightsCache.get("b")?.fetched)
    }

    @Test
    fun putOverwritesPreviousResult() {
        InsightsCache.put("a", insights(5))
        InsightsCache.put("a", insights(9))
        assertEquals(9, InsightsCache.get("a")?.fetched)
    }
}
