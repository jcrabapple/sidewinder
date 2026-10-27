package net.snakepit.sidewinder.core.insights

/**
 * Insights results cached OUTSIDE composition, keyed by session id, so
 * rotating the screen re-renders the stat grid instead of re-fetching
 * up to 12 pages of the account's statuses. Cleared by the screen's
 * explicit refresh button. Entries expire after five minutes.
 */
object InsightsCache {

    private const val MAX_ENTRIES = 8
    private const val TTL_NANOS = 5L * 60L * 1_000_000_000L

    private data class Entry(
        val insights: InsightsRepository.Insights,
        val storedAtNanos: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()

    internal var nanoTime: () -> Long = System::nanoTime

    @Synchronized
    fun get(sessionId: String): InsightsRepository.Insights? {
        val entry = entries[sessionId] ?: return null
        if (nanoTime() - entry.storedAtNanos >= TTL_NANOS) {
            entries.remove(sessionId)
            return null
        }
        return entry.insights
    }

    @Synchronized
    fun put(sessionId: String, insights: InsightsRepository.Insights) {
        entries[sessionId] = Entry(insights, nanoTime())
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
    }

    /** Explicit refresh: drop the cached stats for [sessionId]. */
    @Synchronized
    fun invalidate(sessionId: String) {
        entries.remove(sessionId)
    }
}
