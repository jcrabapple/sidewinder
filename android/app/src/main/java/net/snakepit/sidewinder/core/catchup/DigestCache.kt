package net.snakepit.sidewinder.core.catchup

import net.snakepit.sidewinder.core.model.Status

/**
 * Digest results cached OUTSIDE composition, keyed by session id and
 * range hours, so rotating the screen re-renders from the cache instead
 * of re-walking up to 20 pages of home timeline. Cleared for a session
 * by the screen's explicit refresh button; a different range is a
 * different key and walks fresh. Entries expire after five minutes.
 */
object DigestCache {

    /** Cap so many ranges/sessions cannot grow the map without bound. */
    private const val MAX_ENTRIES = 16
    private const val TTL_NANOS = 5L * 60L * 1_000_000_000L

    private data class Entry(
        val statuses: List<Status>,
        val storedAtNanos: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()

    internal var nanoTime: () -> Long = System::nanoTime

    @Synchronized
    fun get(sessionId: String, hours: Int): List<Status>? {
        val key = key(sessionId, hours)
        val entry = entries[key] ?: return null
        if (nanoTime() - entry.storedAtNanos >= TTL_NANOS) {
            entries.remove(key)
            return null
        }
        return entry.statuses
    }

    @Synchronized
    fun put(sessionId: String, hours: Int, statuses: List<Status>) {
        entries[key(sessionId, hours)] = Entry(statuses, nanoTime())
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
