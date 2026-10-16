package net.snakepit.sidewinder.core.trends

import kotlinx.serialization.builtins.ListSerializer
import net.snakepit.sidewinder.core.network.Api

/**
 * Trending endpoints. Port of the web client's trends usage:
 *
 * - `GET /api/v1/trends/tags?limit=&offset=`
 * - `GET /api/v1/trends/links?limit=&offset=`
 *
 * `/api/v1/trends/statuses` is deliberately NOT wrapped: GTS answers it
 * with an empty-array stub.
 */
object TrendsRepository {

    /** Trending hashtags. */
    suspend fun trendingTags(api: Api, token: String, limit: Int = 20, offset: Int = 0): List<TrendingTag> =
        api.request(
            path = "/api/v1/trends/tags",
            serializer = ListSerializer(TrendingTag.serializer()),
            query = mapOf("limit" to listOf(limit.toString()), "offset" to listOf(offset.toString())),
            headers = mapOf("Authorization" to "Bearer $token"),
        )

    /** Trending links (link-preview cards). */
    suspend fun trendingLinks(api: Api, token: String, limit: Int = 20, offset: Int = 0): List<TrendingLink> =
        api.request(
            path = "/api/v1/trends/links",
            serializer = ListSerializer(TrendingLink.serializer()),
            query = mapOf("limit" to listOf(limit.toString()), "offset" to listOf(offset.toString())),
            headers = mapOf("Authorization" to "Bearer $token"),
        )
}
