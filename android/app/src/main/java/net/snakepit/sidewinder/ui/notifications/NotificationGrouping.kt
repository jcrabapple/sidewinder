package net.snakepit.sidewinder.ui.notifications

import net.snakepit.sidewinder.core.model.Notification

/** One rendered notification row. Reactions are newest first, like the source list. */
internal data class NotificationGroup(
    val notifications: List<Notification>,
) {
    init {
        require(notifications.isNotEmpty())
    }

    /** A deterministic LazyColumn key that does not depend on the row position. */
    val key: String get() = notifications.first().id
}

/**
 * Collapse adjacent favourites or boosts of the same displayed status.
 * Every other notification, and every non-adjacent reaction, remains its own row.
 */
internal fun groupNotifications(notifications: List<Notification>): List<NotificationGroup> {
    if (notifications.isEmpty()) return emptyList()
    val groups = ArrayList<NotificationGroup>(notifications.size)
    for (notification in notifications) {
        val previous = groups.lastOrNull()
        if (previous != null && canGroup(previous.notifications.last(), notification)) {
            groups[groups.lastIndex] = NotificationGroup(previous.notifications + notification)
        } else {
            groups += NotificationGroup(listOf(notification))
        }
    }
    return groups
}

private fun canGroup(previous: Notification, next: Notification): Boolean {
    if (previous.type != next.type || previous.type !in GROUPABLE_TYPES) return false
    val previousStatus = previous.displayedStatusId()
    val nextStatus = next.displayedStatusId()
    return previousStatus != null && previousStatus == nextStatus
}

private fun Notification.displayedStatusId(): String? = (status?.reblog ?: status)?.id

private val GROUPABLE_TYPES = setOf("favourite", "reblog")

/** Append a deduplicated older page and clear any previous paging error. */
internal fun appendNotificationPage(
    state: NotificationsState.Content,
    page: List<Notification>,
    nextMaxId: String?,
): NotificationsState.Content {
    val loadedIds = state.items.mapTo(HashSet()) { it.id }
    return state.copy(
        items = state.items + page.filterNot { it.id in loadedIds },
        nextMaxId = nextMaxId,
        loadingMore = false,
        loadMoreError = null,
    )
}
