package net.snakepit.sidewinder.ui.search

import net.snakepit.sidewinder.core.model.Status

/**
 * Statuses tab payload: accumulated items plus the next offset, if any.
 * Lives beside [SearchMerge] so both the screen and the pure helpers
 * (and their tests) see one definition.
 */
internal data class StatusResults(
    val items: List<Status>,
    val nextOffset: Int?,
    val loadingMore: Boolean = false,
)

/**
 * Pure helpers behind the search results state machine (K46 / G3-6):
 * appending a page must dedupe by id (a LazyColumn keyed by status id
 * crashes on duplicates), and a failed load-more must keep the LIVE list
 * (so concurrent favourites survive) instead of restoring the stale
 * snapshot captured when the request started.
 */
internal object SearchMerge {

    /**
     * A failed load-more: keep [live] (the state as it is now, including
     * any concurrent updates) and clear the in-flight flag. A null [live]
     * stays null — the tab was reset while the request ran, and restoring
     * the stale captured snapshot would resurrect cleared results.
     */
    fun failureState(captured: StatusResults, live: StatusResults?): StatusResults? =
        live?.copy(loadingMore = false)

    /**
     * [base] with one more [page] appended, deduped by status id both
     * against the base and within the page itself.
     */
    fun append(base: List<Status>, page: List<Status>): List<Status> {
        val baseIds = base.mapTo(HashSet()) { it.id }
        val fresh = page.filterNot { it.id in baseIds }.distinctBy { it.id }
        return base + fresh
    }

    /** First-page results deduped by status id. */
    fun deduped(items: List<Status>): List<Status> = items.distinctBy { it.id }
}
