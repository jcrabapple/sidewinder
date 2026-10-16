package net.snakepit.sidewinder.core.trends

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for the pure tag-history math in [TrendingStats]. */
class TrendingStatsTest {

    private fun tag(vararg uses: String) = TrendingTag(
        name = "tag",
        history = uses.map { TagHistoryItem(day = "0", accounts = it, uses = it) },
    )

    @Test
    fun sumsAcrossSevenEntries() {
        val t = tag("10", "20", "30", "40", "50", "60", "70")
        assertEquals(280L, TrendingStats.totalUses(t))
        assertEquals(280L, TrendingStats.totalAccounts(t))
    }

    @Test
    fun unparseableCountsAsZero() {
        val t = tag("5", "", "abc", "10", " ", "0x1f", "7")
        assertEquals(22L, TrendingStats.totalUses(t))
    }

    @Test
    fun fewerThanSevenEntriesSummed() {
        assertEquals(9L, TrendingStats.totalUses(tag("4", "5")))
        assertEquals(0L, TrendingStats.totalUses(tag()))
    }

    @Test
    fun daysCapRespected() {
        val t = tag("1", "2", "3", "4", "5", "6", "7", "8", "9", "10")
        assertEquals(28L, TrendingStats.totalUses(t, days = 7))
        assertEquals(1L, TrendingStats.totalUses(t, days = 1))
        assertEquals(55L, TrendingStats.totalUses(t, days = 20))
        // Entries beyond the cap are ignored even when unparseable.
        val tail = tag("3", "junk")
        assertEquals(3L, TrendingStats.totalUses(tail, days = 1))
    }

    @Test
    fun negativeDaysCountNothing() {
        val t = tag("5", "5")
        assertEquals(0L, TrendingStats.totalUses(t, days = -1))
    }

    @Test
    fun numericEdgeCases() {
        assertEquals(0L, TrendingStats.numeric(""))
        assertEquals(0L, TrendingStats.numeric("   "))
        assertEquals(0L, TrendingStats.numeric("abc"))
        assertEquals(0L, TrendingStats.numeric("1.5"))
        assertEquals(7L, TrendingStats.numeric(" 7 "))
        assertEquals(-1L, TrendingStats.numeric("-1"))
        assertEquals(9223372036854775807L, TrendingStats.numeric("9223372036854775807"))
    }
}
