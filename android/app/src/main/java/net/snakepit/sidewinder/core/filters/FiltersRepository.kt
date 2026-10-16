package net.snakepit.sidewinder.core.filters

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.snakepit.sidewinder.core.network.Api
import java.net.URLEncoder

/**
 * CRUD for `/api/v2/filters`. Mutations invalidate [FilterCache] so the
 * next read sees the server's truth. The [token] parameter exists for
 * the [FilterCache] key; requests authenticate through the token already
 * carried by [Api] (the same convention as ListsRepository).
 *
 * Bodies are JSON objects (GTS accepts `application/json` on these
 * endpoints). The Mastodon form encoding (`keywords_attributes[][keyword]`
 * as repeated form keys) cannot be expressed through [Api.postForm],
 * which only supports POST with a flat `Map<String, String>`; JSON
 * carries the same fields with a `keywords_attributes` array instead.
 */
object FiltersRepository {

    /** All of the signed-in user's v2 filters, keywords and statuses included. */
    suspend fun list(api: Api, token: String): List<FilterV2> =
        api.request(
            path = "/api/v2/filters",
            serializer = ListSerializer(FilterV2.serializer()),
        )

    /** Create a filter with [keywords] as (keyword, wholeWord) pairs. */
    suspend fun create(
        api: Api,
        token: String,
        title: String,
        contexts: List<String>,
        expiresInSeconds: Long?,
        action: String,
        keywords: List<Pair<String, Boolean>>,
    ): FilterV2 =
        api.request(
            path = "/api/v2/filters",
            serializer = FilterV2.serializer(),
            method = "POST",
            body = payload(title, contexts, expiresInSeconds, action, keywords.map { Triple(it.first, it.second, null as String?) }),
        ).also { FilterCache.invalidate(token) }

    /**
     * Replace a filter's full field set. Existing keywords must be sent
     * with their id (third element of each [keywords] triple) so the
     * server keeps rather than duplicates them.
     */
    suspend fun update(
        api: Api,
        token: String,
        id: String,
        title: String,
        contexts: List<String>,
        expiresInSeconds: Long?,
        action: String,
        keywords: List<Triple<String, Boolean, String?>>,
    ): FilterV2 =
        api.request(
            path = "/api/v2/filters/${URLEncoder.encode(id, "UTF-8")}",
            serializer = FilterV2.serializer(),
            method = "PUT",
            body = payload(title, contexts, expiresInSeconds, action, keywords),
        ).also { FilterCache.invalidate(token) }

    /** Delete a filter; the response body is unused. */
    suspend fun delete(api: Api, token: String, id: String) {
        api.request(
            path = "/api/v2/filters/${URLEncoder.encode(id, "UTF-8")}",
            serializer = JsonElement.serializer(),
            method = "DELETE",
        )
        FilterCache.invalidate(token)
    }

    private fun payload(
        title: String,
        contexts: List<String>,
        expiresInSeconds: Long?,
        action: String,
        keywords: List<Triple<String, Boolean, String?>>,
    ) = buildJsonObject {
        put("title", title)
        put("context", buildJsonArray { contexts.forEach { add(JsonPrimitive(it)) } })
        expiresInSeconds?.let { put("expires_in", it) }
        put("filter_action", action)
        put(
            "keywords_attributes",
            buildJsonArray {
                keywords.forEach { (keyword, wholeWord, existingId) ->
                    add(
                        buildJsonObject {
                            existingId?.let { put("id", it) }
                            put("keyword", keyword)
                            put("whole_word", wholeWord)
                        },
                    )
                }
            },
        )
    }
}

/**
 * In-memory per-token cache of the user's filters, shared by the
 * timeline and status rendering paths so every screen sees the same
 * list. 60-second TTL; a mutex guarantees one in-flight refresh.
 */
object FilterCache {

    private const val TTL_MS = 60_000L

    internal class Entry(val filters: List<FilterV2>, val fetchedAtMs: Long)

    /** After a failed fetch, wait this long before trying the network again. */
    internal const val RETRY_AFTER_FAILURE_MS = 15_000L

    /**
     * The entry to store after a failed fetch: last known filters (empty when
     * never fetched), timestamped so it goes stale [RETRY_AFTER_FAILURE_MS]
     * from [now].
     */
    internal fun failureEntry(previous: Entry?, now: Long): Entry =
        Entry(previous?.filters ?: emptyList(), now - TTL_MS + RETRY_AFTER_FAILURE_MS)

    /** True when [entry] is fresh enough to serve without a fetch at [now]. */
    internal fun isFresh(entry: Entry, now: Long): Boolean = now - entry.fetchedAtMs < TTL_MS

    private val mutex = Mutex()

    /**
     * Internal so tests can seed entries: peek() reads this map directly.
     * Concurrent access is safe (ConcurrentHashMap); racing a refresh only
     * costs one extra re-fetch and never corrupts an entry.
     */
    internal val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    /**
     * Cached filters for [token], refreshed when missing, stale after
     * 60 seconds, or when [force] is set. Concurrent callers share one
     * refresh.
     */
    suspend fun current(api: Api, token: String, force: Boolean = false): List<FilterV2> {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val entry = cache[token]
            if (!force && entry != null && isFresh(entry, now)) {
                return entry.filters
            }
            val fresh = try {
                FiltersRepository.list(api, token)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Negative cache: keep the last known filters (none if never
                // fetched) and retry after RETRY_AFTER_FAILURE_MS, so one outage
                // does not make every card re-fetch serially behind the mutex.
                cache[token] = failureEntry(entry, now)
                throw e
            }
            cache[token] = Entry(fresh, now)
            return fresh
        }
    }

    /**
     * The cached filters for [token], even when stale — or null when they
     * have never been fetched. Non-suspending so synchronous render paths
     * (the initial filter verdict) can use it without a coroutine.
     */
    fun peek(token: String): List<FilterV2>? = cache[token]?.filters

    /** Drop the cached filters for [token]; called after every mutation. */
    fun invalidate(token: String) {
        // No mutex needed: racing a concurrent refresh only costs one
        // extra re-fetch and never corrupts the entry.
        cache.remove(token)
    }
}
