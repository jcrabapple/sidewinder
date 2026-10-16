package net.snakepit.sidewinder.ui.notifications

import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [applyStreamedNotification]: a streamed notification
 * must turn an Empty unfiltered list into Content (previously it was
 * dropped), prepend deduped into Content, and never touch a filtered
 * view or the Loading/Error states.
 */
class NotificationsStreamTest {

    private fun notification(id: String) = Notification(
        id = id,
        type = "mention",
        created_at = "2026-10-16T12:00:00Z",
        account = Account(id = "a$id", username = "alice", acct = "alice@example.social"),
    )

    @Test
    fun emptyUnfilteredListBecomesContentWithTheNewNotification() {
        val next = applyStreamedNotification(NotificationsState.Empty, NotificationFilter.All, notification("n1"))
        assertTrue(next is NotificationsState.Content)
        assertEquals(listOf("n1"), (next as NotificationsState.Content).items.map { it.id })
        assertNullMaxId(next)
    }

    @Test
    fun emptyFilteredListIsNotChanged() {
        val next = applyStreamedNotification(NotificationsState.Empty, NotificationFilter.Mentions, notification("n1"))
        assertEquals(NotificationsState.Empty, next)
    }

    @Test
    fun contentStatePrependsTheNotification() {
        val state = NotificationsState.Content(
            items = listOf(notification("n2")),
            nextMaxId = "cursor",
            loadingMore = false,
        )
        val next = applyStreamedNotification(state, NotificationFilter.All, notification("n1"))
        next as NotificationsState.Content
        assertEquals(listOf("n1", "n2"), next.items.map { it.id })
        assertEquals("cursor", next.nextMaxId)
    }

    @Test
    fun duplicateNotificationIsNotAddedTwice() {
        val state = NotificationsState.Content(
            items = listOf(notification("n1")),
            nextMaxId = null,
            loadingMore = false,
        )
        val next = applyStreamedNotification(state, NotificationFilter.All, notification("n1"))
        assertEquals(1, (next as NotificationsState.Content).items.size)
    }

    @Test
    fun loadingAndErrorStatesAreUntouched() {
        assertEquals(
            NotificationsState.Loading,
            applyStreamedNotification(NotificationsState.Loading, NotificationFilter.All, notification("n1")),
        )
        val error = NotificationsState.Error("Could not load notifications. Retry.")
        assertEquals(error, applyStreamedNotification(error, NotificationFilter.All, notification("n1")))
    }

    private fun assertNullMaxId(state: NotificationsState) {
        assertEquals(null, (state as NotificationsState.Content).nextMaxId)
    }
}
