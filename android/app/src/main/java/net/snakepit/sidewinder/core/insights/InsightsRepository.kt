package net.snakepit.sidewinder.core.insights

import android.net.Uri
import kotlinx.serialization.builtins.ListSerializer
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api

/**
 * Client-side stats over the signed-in account. Port of web
 * `state/insights.ts` plus the panel in `components/Insights.tsx`:
 * the account's own statuses are paged once and counted on device.
 * Nothing is cached or sent anywhere.
 */
object InsightsRepository {

    const val PAGE_SIZE = 40

    /** Page cap (~480 posts) so a prolific account cannot page forever. */
    const val MAX_PAGES = 12

    /** A hashtag used by the account, with its post count. */
    data class TagCount(val name: String, val count: Int)

    /**
     * Point-in-time numbers over the fetched statuses. [fetched] counts
     * everything examined including boost wrappers; [posts] counts only
     * the account's own originals.
     */
    data class Insights(
        val fetched: Int,
        val posts: Int,
        val boostsGiven: Int,
        val favouritesReceived: Long,
        val repliesMade: Int,
        val topHashtags: List<TagCount>,
        val medianEngagement: Long,
    )

    /**
     * Page the account's own statuses (newest first) and compute the
     * stats. [onProgress] reports the running fetched count.
     */
    suspend fun load(
        api: Api,
        accountId: String,
        onProgress: suspend (Int) -> Unit = {},
    ): Insights {
        val statuses = mutableListOf<Status>()
        var maxId: String? = null
        repeat(MAX_PAGES) {
            val page = api.page(
                path = "/api/v1/accounts/${Uri.encode(accountId)}/statuses",
                serializer = ListSerializer(Status.serializer()),
                query = mapOf("limit" to listOf(PAGE_SIZE.toString())),
                maxId = maxId,
            )
            if (page.items.isEmpty()) return compute(statuses, accountId)
            statuses.addAll(page.items)
            onProgress(statuses.size)
            maxId = page.nextMaxId ?: return compute(statuses, accountId)
        }
        return compute(statuses, accountId)
    }

    /** Count posts, boosts, favourites, replies, hashtags, and engagement.
     *  [selfAccountId] excludes self-replies from [Insights.repliesMade]. */
    fun compute(statuses: List<Status>, selfAccountId: String = ""): Insights {
        var posts = 0
        var boostsGiven = 0
        var favourites = 0L
        var replies = 0
        val tagCounts = HashMap<String, Int>()
        val engagementValues = ArrayList<Long>()
        for (status in statuses) {
            val inner = status.reblog
            if (inner != null) {
                boostsGiven++
                continue
            }
            posts++
            favourites += status.favourites_count
            if (status.in_reply_to_id != null && status.in_reply_to_account_id != selfAccountId) replies++
            engagementValues.add(engagement(status))
            for (tag in status.tags) {
                tagCounts.merge(tag.name.lowercase(), 1, Int::plus)
            }
        }
        val top = tagCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(3)
            .map { TagCount(it.key, it.value) }
        return Insights(
            fetched = statuses.size,
            posts = posts,
            boostsGiven = boostsGiven,
            favouritesReceived = favourites,
            repliesMade = replies,
            topHashtags = top,
            medianEngagement = median(engagementValues),
        )
    }

    /**
     * Engagement score, port of web `insights.ts engagement()`:
     * favourites + 2×boosts + replies (boosts weighted as the stronger
     * reach signal).
     */
    fun engagement(status: Status): Long =
        status.favourites_count + 2 * status.reblogs_count + status.replies_count

    /** Port of web `insights.ts median()`. */
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
