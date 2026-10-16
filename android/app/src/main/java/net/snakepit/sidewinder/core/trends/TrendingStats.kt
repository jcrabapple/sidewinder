package net.snakepit.sidewinder.core.trends

/**
 * Pure helpers for the tag-history numbers inside a [TrendingTag]. JVM
 * testable: no Android classes.
 */
object TrendingStats {

    /**
     * Sum of the `uses` counts over the last [days] day-buckets of the
     * tag's history. Servers send buckets newest-day-first, so "the last
     * seven days" is the FIRST [days] entries of the list; entries beyond
     * that are ignored. Unparseable counts count as zero.
     */
    fun totalUses(tag: TrendingTag, days: Int = 7): Long =
        tag.history.take(days.coerceAtLeast(0)).sumOf { numeric(it.uses) }

    /** Same as [totalUses] over the history's `accounts` counts. */
    fun totalAccounts(tag: TrendingTag, days: Int = 7): Long =
        tag.history.take(days.coerceAtLeast(0)).sumOf { numeric(it.accounts) }

    /** Parse a numeric string; anything unparseable (including empty) is 0. */
    fun numeric(value: String): Long = value.trim().toLongOrNull() ?: 0L
}
