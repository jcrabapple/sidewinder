package net.snakepit.sidewinder.ui.timeline

import net.snakepit.sidewinder.core.filters.FilterEngine
import net.snakepit.sidewinder.core.filters.FilterHit
import net.snakepit.sidewinder.core.filters.FilterableText
import net.snakepit.sidewinder.core.filters.FilteredStatus
import net.snakepit.sidewinder.core.filters.FilterV2
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.text.StatusText

/**
 * Pure decision logic extracted from [StatusCard] so the filter verdict
 * and toggle-revert behaviour stay JVM-testable. No Compose, no Android
 * framework types here.
 */
object StatusCardLogic {

    /**
     * The initial filter verdict, computed synchronously so a filtered
     * post never renders its content before the verdict is known.
     *
     * The server-provided `filtered` array wins; otherwise the
     * client-side match runs over the peeked (non-suspending) filter
     * cache. When [peekedFilters] is null — cache not populated yet —
     * this returns null and the caller must treat the verdict as
     * PENDING (show a neutral placeholder, resolve asynchronously with
     * the suspending [net.snakepit.sidewinder.core.filters.FilterCache.current]).
     */
    fun initialFilterHit(
        filtered: List<FilteredStatus>,
        contentHtml: String,
        spoiler: String,
        pollOptions: List<String>,
        mediaDescriptions: List<String>,
        peekedFilters: List<FilterV2>?,
        context: String,
        nowMs: Long,
    ): FilterHit? {
        FilterEngine.verdict(filtered, context, nowMs)?.let { return it }
        val filters = peekedFilters ?: return null
        return clientMatch(filters, contentHtml, spoiler, pollOptions, mediaDescriptions, context, nowMs)
    }

    /**
     * Client-side match over [filters], with the status HTML turned into
     * plain text via the cached [StatusText.plain] (never a fresh Jsoup
     * parse per recomposition). Runs CPU-bound keyword scans; callers
     * dispatch it off the main thread when it happens inside a coroutine.
     */
    fun clientMatch(
        filters: List<FilterV2>,
        contentHtml: String,
        spoiler: String,
        pollOptions: List<String>,
        mediaDescriptions: List<String>,
        context: String,
        nowMs: Long,
    ): FilterHit? = FilterEngine.match(
        filters,
        FilterableText(
            text = StatusText.plain(contentHtml),
            spoiler = spoiler,
            pollOptions = pollOptions,
            mediaDescriptions = mediaDescriptions,
        ),
        context,
        nowMs,
    )

    /** The plain-text fields of a status, for the async client-side match. */
    fun filterableText(inner: Status): FilterableText = FilterableText(
        text = StatusText.plain(inner.content),
        spoiler = inner.spoiler_text,
        pollOptions = inner.poll?.options?.map { it.title }.orEmpty(),
        mediaDescriptions = inner.media_attachments.mapNotNull { it.description?.takeIf(String::isNotBlank) },
    )

    /**
     * Revert exactly the field [action] toggled on the NEWEST status, so
     * a late error response cannot clobber concurrent edits (favourite
     * count, content) that landed while the request was in flight.
     * [previousActive] is the field's value at click time.
     */
    fun revertToggle(inner: Status, action: String, previousActive: Boolean): Status {
        // Undo the optimistic count delta only while the latest status still
        // carries the optimistic flag; if a server refresh already put the
        // flag back, its counts are authoritative and stay untouched.
        val undo = if (previousActive) 1L else -1L
        return when (action) {
            "favourite" ->
                if (inner.favourited != previousActive) {
                    inner.copy(
                        favourited = previousActive,
                        favourites_count = (inner.favourites_count + undo).coerceAtLeast(0),
                    )
                } else inner
            "bookmark" -> inner.copy(bookmarked = previousActive)
            else ->
                if (inner.reblogged != previousActive) {
                    inner.copy(
                        reblogged = previousActive,
                        reblogs_count = (inner.reblogs_count + undo).coerceAtLeast(0),
                    )
                } else inner
        }
    }
}
