package net.snakepit.sidewinder.core.trends

import kotlinx.serialization.Serializable

/** One day-bucket of a trending tag's history. Mastodon/GTS encode the counts as STRINGS. */
@Serializable
data class TagHistoryItem(
    val day: String = "",
    val accounts: String = "",
    val uses: String = "",
)

/** Payload of GET /api/v1/trends/tags. */
@Serializable
data class TrendingTag(
    val name: String,
    val url: String? = null,
    val history: List<TagHistoryItem> = emptyList(),
)

/**
 * Payload of GET /api/v1/trends/links (the link-preview card shape).
 * Fields beyond [url] are tolerated missing: servers differ.
 */
@Serializable
data class TrendingLink(
    val url: String,
    val title: String = "",
    val description: String = "",
    val image: String? = null,
    val provider_name: String? = null,
)
