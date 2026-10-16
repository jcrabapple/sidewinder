package net.snakepit.sidewinder.core.filters

import kotlinx.serialization.Serializable

/** One keyword inside a v2 filter (`/api/v2/filters` payload subset). */
@Serializable
data class FilterKeyword(
    val id: String = "",
    val keyword: String,
    /** Match only at word boundaries. */
    val whole_word: Boolean = false,
)

/** One status pinned to a filter. GTS carries the status id in `phrase`. */
@Serializable
data class FilterStatusEntry(
    val id: String = "",
    val status_id: String? = null,
    val phrase: String? = null,
)

/** A v2 filter as returned by `/api/v2/filters`. */
@Serializable
data class FilterV2(
    val id: String,
    val title: String,
    /** home, notifications, public, thread */
    val context: List<String>,
    val expires_at: String? = null,
    /** warn, blur, or hide */
    val filter_action: String = "warn",
    val keywords: List<FilterKeyword> = emptyList(),
    val statuses: List<FilterStatusEntry> = emptyList(),
)

/**
 * One entry of the `filtered` array the server embeds on statuses it
 * matched. Mirrors the Mastodon FilterResult shape; the owning filter is
 * inlined under `filter`.
 */
@Serializable
data class FilteredStatus(
    val filter: FilterV2? = null,
    val keyword_matches: List<String> = emptyList(),
    val status_matches: List<String> = emptyList(),
)

/** All plain text extracted from a status, for client-side filter matching. */
data class FilterableText(
    val text: String,
    val spoiler: String,
    val pollOptions: List<String>,
    val mediaDescriptions: List<String>,
)

/** The outcome of filter evaluation: which filter matched and how severely. */
data class FilterHit(
    val filterId: String,
    val title: String,
    /** warn, blur, or hide */
    val action: String,
    val matchedKeywords: List<String>,
)
