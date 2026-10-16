package net.snakepit.sidewinder.core.text

import org.jsoup.Jsoup

/**
 * Cached plain-text extraction from status HTML, shared by every row that
 * needs text without markup (filter matching, collapse checks, excerpts).
 * Replaces per-recomposition `Jsoup.parse(...)` calls: statuses are parsed
 * once per content string for the whole process.
 *
 * Uses a synchronized [java.util.LinkedHashMap] with access-order eviction
 * rather than `android.util.LruCache`, which is a stub in JVM unit tests.
 */
object StatusText {

    private const val MAX_ENTRIES = 1000

    /**
     * Jsoup.parse(html).text() with runs of whitespace collapsed to a
     * single space and the result trimmed; the empty string for empty
     * input. Cached.
     */
    fun plain(html: String): String = plainCache.getOrPut(html) {
        Jsoup.parse(html).text().replace(WHITESPACE, " ").trim()
    }

    /**
     * True when the HTML would render nothing visible. Same semantics as
     * [htmlIsEmpty] (blank input, or no visible element/text outside the
     * void/structural tags). Cached.
     */
    fun isEmptyHtml(html: String): Boolean = emptyCache.getOrPut(html) { htmlIsEmpty(html) }

    /** Simple synchronized access-order LRU over a [java.util.LinkedHashMap]. */
    private class Lru<T>(private val maxEntries: Int) {
        private val entries = java.util.LinkedHashMap<String, T>(64, 0.75f, true)

        fun getOrPut(key: String, compute: (String) -> T): T = synchronized(entries) {
            val cached = entries[key]
            if (cached != null) {
                cached
            } else {
                val value = compute(key)
                entries[key] = value
                while (entries.size > maxEntries) {
                    val eldest = entries.entries.iterator()
                    eldest.next()
                    eldest.remove()
                }
                value
            }
        }

        fun size(): Int = synchronized(entries) { entries.size }
    }

    private val plainCache = Lru<String>(MAX_ENTRIES)
    private val emptyCache = Lru<Boolean>(MAX_ENTRIES)

    /** Test hook: current plain-cache entry count (internal visibility). */
    internal fun plainCacheSize(): Int = plainCache.size()

    private val WHITESPACE = Regex("\\s+")
}
