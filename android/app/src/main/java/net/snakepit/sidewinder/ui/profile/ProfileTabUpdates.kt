package net.snakepit.sidewinder.ui.profile

import net.snakepit.sidewinder.core.model.Status

/**
 * Pure helpers for propagating a status update across the profile tab
 * cache (K37): an edit, favourite, boost or bookmark that lands on one
 * tab must be reflected in every cached tab, including inside boost
 * wrappers where the visible post is `item.reblog`.
 */
internal object ProfileTabUpdates {

    /** Pinned posts first, then ordinary posts, with each status id appearing once. */
    fun mergedPosts(pinned: List<Status>, posts: List<Status>): List<Status> {
        val merged = ArrayList<Status>(pinned.size + posts.size)
        val ids = HashSet<String>(pinned.size + posts.size)
        for (status in pinned) {
            if (ids.add(status.id)) merged += status
        }
        for (status in posts) {
            if (ids.add(status.id)) merged += status
        }
        return merged
    }

    /**
     * [items] with [updated] applied: rows whose id matches are replaced,
     * and boost wrappers whose inner status matches get the updated inner
     * status while keeping the wrapper's own identity.
     */
    fun updatedItems(items: List<Status>, updated: Status): List<Status> =
        items.map { item ->
            when {
                item.id == updated.id -> updated
                // Boost wrapper: the visible post is item.reblog; replace
                // the inner status while keeping the wrapper's identity.
                item.reblog?.id == updated.id -> item.copy(reblog = updated)
                else -> item
            }
        }
}
