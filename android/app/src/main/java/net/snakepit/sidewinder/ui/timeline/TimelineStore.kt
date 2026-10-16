package net.snakepit.sidewinder.ui.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.timeline.TimelineSource

internal sealed interface TimelineState {
    /** First page still loading (or a load was abandoned mid-flight). */
    data object Loading : TimelineState

    /** First page failed; nothing to show. */
    data class Error(val message: String) : TimelineState

    /** First page loaded with zero items. */
    data object Empty : TimelineState

    /** At least one page loaded. */
    data class Content(
        val items: List<Status>,
        val nextMaxId: String?,
        val loadingMore: Boolean,
    ) : TimelineState
}

/**
 * One timeline's loaded posts and scroll position, kept OUTSIDE composition
 * so they survive navigation: opening a post and coming back (or switching
 * Home/Local/Federated, or another bottom tab) restores the same posts at
 * the same place instead of refetching page 1 at the top.
 */
internal class TimelineHolder {
    var state by mutableStateOf<TimelineState>(TimelineState.Loading)
    var firstVisibleIndex: Int = 0
    var firstVisibleOffset: Int = 0
}

/**
 * Process-lifetime store of [TimelineHolder]s keyed by session + source,
 * least-recently-used first out. Also the single place edits, deletions and
 * new posts are applied, so a cached timeline never shows a stale version of
 * a post changed elsewhere in the app. Main-thread only (snapshot state).
 */
internal object TimelineStore {
    private const val MAX_HOLDERS = 12
    private const val MAX_EXPANDED = 200

    // accessOrder = true: iteration order is least- to most-recently used.
    private val holders = LinkedHashMap<String, TimelineHolder>(16, 0.75f, true)

    // Statuses the user expanded ("Show more") in a feed, so collapse state
    // survives scrolling away and feed reloads. LRU-capped.
    private val expandedStatuses = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > MAX_EXPANDED
    }

    private fun key(sessionId: String, source: TimelineSource): String = "$sessionId|$source"

    fun holder(sessionId: String, source: TimelineSource): TimelineHolder {
        val k = key(sessionId, source)
        holders[k]?.let { return it }
        val created = TimelineHolder()
        holders[k] = created
        while (holders.size > MAX_HOLDERS) {
            val eldest = holders.keys.first()
            holders.remove(eldest)
        }
        return created
    }

    /** Drop every timeline belonging to a session that no longer exists. */
    fun retainSessions(sessionIds: Set<String>) {
        holders.keys.removeAll { k -> k.substringBefore('|') !in sessionIds }
    }

    /** Whether [statusId] was expanded in a feed ("Show more"). */
    fun isExpanded(statusId: String): Boolean = expandedStatuses.containsKey(statusId)

    /** Records or clears the expanded state of a long post in feeds. */
    fun setExpanded(statusId: String, expanded: Boolean) {
        if (expanded) expandedStatuses[statusId] = Unit else expandedStatuses.remove(statusId)
    }

    /** Replace [updated] wherever it appears, directly or inside a boost. */
    fun applyEdit(updated: Status) = mapAll { items ->
        var changed = false
        val next = items.map { s ->
            when {
                s.id == updated.id -> { changed = true; updated }
                s.reblog?.id == updated.id -> { changed = true; s.copy(reblog = updated) }
                else -> s
            }
        }
        // Nothing matched: hand back the SAME list so mapAll skips the
        // holder entirely instead of rewriting every holder's state.
        if (changed) next else items
    }

    /** Remove a deleted status and any boosts of it. */
    fun applyDelete(statusId: String) = mapAll { items ->
        items.filterNot { it.id == statusId || it.reblog?.id == statusId }
    }

    /** Show a just-published top-level post at the top of that account's Home feed. */
    fun applyPosted(sessionId: String, status: Status) {
        if (status.in_reply_to_id != null) return
        val home = holders[key(sessionId, TimelineSource.Home)] ?: return
        when (val current = home.state) {
            is TimelineState.Content -> {
                if (current.items.any { it.id == status.id }) return
                home.state = current.copy(items = listOf(status) + current.items)
                // Keep the reader's place: the new row shifts everything down one.
                if (home.firstVisibleIndex > 0) home.firstVisibleIndex += 1
            }
            // An empty (but finished) feed must not drop the post: the
            // composer closed, so the user expects to see what they wrote.
            TimelineState.Empty -> {
                home.state = TimelineState.Content(listOf(status), nextMaxId = null, loadingMore = false)
            }
            else -> Unit
        }
    }

    /**
     * Live home-timeline update from the streaming socket: prepend a new
     * post from a followed account to this session's cached Home feed
     * (replies included — the home stream mirrors the home timeline, which
     * carries replies to people you follow). No-ops when the feed is not
     * currently holding content; the next refresh picks those up.
     */
    fun applyStreamed(sessionId: String, status: Status) {
        val home = holders[key(sessionId, TimelineSource.Home)] ?: return
        val current = home.state as? TimelineState.Content ?: return
        if (current.items.any { it.id == status.id }) return
        home.state = current.copy(items = listOf(status) + current.items)
        if (home.firstVisibleIndex > 0) home.firstVisibleIndex += 1
    }

    private fun mapAll(transform: (List<Status>) -> List<Status>) {
        for (holder in holders.values.toList()) {
            val current = holder.state as? TimelineState.Content ?: continue
            val next = transform(current.items)
            // Assign only when the transform actually changed something:
            // an unchanged list is returned by reference, so unaffected
            // holders are not reassigned (no recomposition churn).
            if (next !== current.items && next != current.items) {
                holder.state = if (next.isEmpty() && current.nextMaxId == null) {
                    TimelineState.Empty
                } else {
                    current.copy(items = next)
                }
            }
        }
    }
}
