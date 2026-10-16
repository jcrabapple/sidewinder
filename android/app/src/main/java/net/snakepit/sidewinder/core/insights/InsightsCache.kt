package net.snakepit.sidewinder.core.insights

/**
 * Insights results cached OUTSIDE composition, keyed by session id, so
 * rotating the screen re-renders the stat grid instead of re-fetching
 * up to 12 pages of the account's statuses. Cleared by the screen's
 * explicit refresh button.
 */
object InsightsCache {

    private const val MAX_ENTRIES = 8

    private val entries = LinkedHashMap<String, InsightsRepository.Insights>()

    @Synchronized
    fun get(sessionId: String): InsightsRepository.Insights? = entries[sessionId]

    @Synchronized
    fun put(sessionId: String, insights: InsightsRepository.Insights) {
        entries[sessionId] = insights
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
