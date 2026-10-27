package net.snakepit.sidewinder.core.search

import kotlinx.serialization.Serializable
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api

/**
 * Mastodon v2 search payload. Only the fields this app renders are
 * declared; unknown keys are ignored by the JSON parser.
 */
@Serializable
data class SearchResults(
    val statuses: List<Status> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val hashtags: List<SearchHashtag> = emptyList(),
)

/**
 * Hashtag result from /api/v2/search. Unlike the timeline Tag type this
 * carries recent-use history, which backs the "N recent posts" line.
 */
@Serializable
data class SearchHashtag(
    val name: String,
    val url: String = "",
    val history: List<HashtagHistory> = emptyList(),
)

@Serializable
data class HashtagHistory(
    /** Unix day bucket as a string, newest first per the API. */
    val day: String = "0",
    val uses: Long = 0,
    val accounts: Long = 0,
)

/**
 * Search access. All types go through `GET /api/v2/search`; status
 * pagination uses `offset` because the endpoint has no Link-header
 * max_id paging.
 */
object SearchRepository {
    const val PAGE_LIMIT = 20

    suspend fun search(
        api: Api,
        query: String,
        type: String,
        limit: Int = PAGE_LIMIT,
        offset: Int = 0,
        /** Ask the server to webfinger/fetch remote accounts or posts by URL. */
        resolve: Boolean = shouldResolveSearch(query),
    ): SearchResults = api.request(
        path = "/api/v2/search",
        serializer = SearchResults.serializer(),
        query = buildMap {
            put("q", listOf(query))
            put("type", listOf(type))
            put("limit", listOf(limit.toString()))
            put("offset", listOf(offset.toString()))
            if (resolve) put("resolve", listOf("true"))
        },
    )
}

/** Pasted web addresses need server-side resolution to find remote objects. */
internal fun shouldResolveSearch(query: String): Boolean {
    val trimmed = query.trim()
    return trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("http://", ignoreCase = true)
}

/** Sum of recent-use counts across the history buckets, for row display. */
fun SearchHashtag.recentUses(): Long =
    history.sumOf { it.uses }
