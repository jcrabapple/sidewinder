package net.snakepit.sidewinder.core.catchup

import net.snakepit.sidewinder.core.model.Status

/**
 * Digest results cached OUTSIDE composition, keyed by session id and
 * range hours, so rotating the screen re-renders from the cache instead
 * of re-walking up to 20 pages of home timeline. Cleared for a session
 * by the screen's explicit refresh button; a different range is a
 * different key and walks fresh.
 */
object DigestCache {

    /** Cap so many ranges/sessions cannot grow the map without bound. */
    private const val MAX_ENTRIES = 16

    private val entries = LinkedHashMap<String, List<Status>>()

    @Synchronized
    fun get(sessionId: String, hours: Int): List<Status>? = entries[key(sessionId, hours)]

    @Synchronized
    fun put(sessionId: String, hours: Int, statuses: List<Status>) {
        entries[key(sessionId, hours)] = statuses
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
    }

    /** Explicit refresh: drop every cached range for [sessionId]. */
    @Synchronized
    fun invalidate(sessionId: String) {
        entries.keys.removeAll { it.startsWith(prefix(sessionId)) }
    }

    private fun key(sessionId: String, hours: Int) = "${prefix(sessionId)}$hours"

    private fun prefix(sessionId: String) = "$sessionId|"
}
