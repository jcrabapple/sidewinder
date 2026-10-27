package net.snakepit.sidewinder.ui.notifications

import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Notification
import net.snakepit.sidewinder.core.model.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationGroupingTest {

    private fun notification(
        id: String,
        type: String = "favourite",
        statusId: String? = "s1",
        actorId: String = "actor-$id",
    ) = Notification(
        id = id,
        type = type,
        created_at = "2026-10-25T12:00:00Z",
        account = Account(id = actorId, username = actorId, acct = actorId),
        status = statusId?.let {
            Status(
                id = it,
                uri = "https://example.social/statuses/$it",
                created_at = "2026-10-25T11:00:00Z",
                account = Account(id = "author", username = "author", acct = "author"),
            )
        },
    )

    private fun content(items: List<Notification>) = NotificationsState.Content(
        items = items,
        nextMaxId = "older",
        loadingMore = true,
        loadMoreError = "Could not load older notifications. Retry below.",
    )

    @Test
    fun adjacentReactionsForOneStatusShareARowInNewestFirstOrder() {
        val groups = groupNotifications(
            listOf(
                notification("n4"),
                notification("n3"),
                notification("n2", type = "mention"),
                notification("n1", type = "reblog"),
                notification("n0", type = "reblog"),
            ),
        )

        assertEquals(listOf(2, 1, 2), groups.map { it.notifications.size })
        assertEquals(listOf("n4", "n3"), groups[0].notifications.map { it.id })
        assertEquals(listOf("n1", "n0"), groups[2].notifications.map { it.id })
        assertEquals(listOf("n4", "n2", "n1"), groups.map { it.key })
    }

    @Test
    fun typeStatusAndMissingStatusBoundariesStaySeparate() {
        val groups = groupNotifications(
            listOf(
                notification("n5"),
                notification("n4", type = "reblog"),
                notification("n3", statusId = "s2"),
                notification("n2", statusId = null),
                notification("n1", statusId = null),
                notification("n0"),
            ),
        )

        assertEquals(6, groups.size)
    }

    @Test
    fun streamedReactionJoinsOnlyTheCurrentTopBoundary() {
        val initial = NotificationsState.Content(
            items = listOf(
                notification("n3"),
                notification("n2"),
                notification("n1", type = "mention"),
            ),
            nextMaxId = "older",
            loadingMore = false,
        )

        val streamed = applyStreamedNotification(
            initial,
            NotificationFilter.All,
            notification("n4"),
        ) as NotificationsState.Content
        val groups = groupNotifications(streamed.items)

        assertEquals(listOf("n4", "n3", "n2", "n1"), streamed.items.map { it.id })
        assertEquals(listOf(3, 1), groups.map { it.notifications.size })
        assertEquals("older", streamed.nextMaxId)
    }

    @Test
    fun streamedReactionDoesNotCrossAnInterveningNotification() {
        val initial = NotificationsState.Content(
            items = listOf(notification("n2", type = "mention"), notification("n1")),
            nextMaxId = null,
            loadingMore = false,
        )

        val streamed = applyStreamedNotification(
            initial,
            NotificationFilter.All,
            notification("n3"),
        ) as NotificationsState.Content

        assertEquals(listOf(1, 1, 1), groupNotifications(streamed.items).map { it.notifications.size })
    }

    @Test
    fun olderPageDeduplicatesAndJoinsTheBottomBoundary() {
        val appended = appendNotificationPage(
            content(listOf(notification("n3"), notification("n2", type = "mention"), notification("n1"))),
            listOf(notification("n1"), notification("n0")),
            nextMaxId = "oldest",
        )
        val groups = groupNotifications(appended.items)

        assertEquals(listOf("n3", "n2", "n1", "n0"), appended.items.map { it.id })
        assertEquals(listOf(1, 1, 2), groups.map { it.notifications.size })
        assertEquals(listOf("n1", "n0"), groups.last().notifications.map { it.id })
        assertEquals("oldest", appended.nextMaxId)
        assertEquals(false, appended.loadingMore)
        assertNull(appended.loadMoreError)
    }

    @Test
    fun freshFirstPageMergesIntoTheCachedTopReactionGroup() {
        val cached = NotificationsState.Content(
            items = listOf(notification("n2"), notification("n1", type = "mention")),
            nextMaxId = "older",
            loadingMore = false,
        )

        val merged = mergeFreshFirstPage(
            cached,
            listOf(notification("n3"), notification("n2")),
            freshNextMaxId = "newer",
        ) as NotificationsState.Content

        assertEquals(listOf(2, 1), groupNotifications(merged.items).map { it.notifications.size })
        assertEquals("older", merged.nextMaxId)
    }
}
