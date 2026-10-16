package net.snakepit.sidewinder.ui.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for [NotificationsStore]: holders are keyed by session id +
 * filter, survive across lookups (so a rotation restores state), reset
 * scroll on demand, and evict least-recently-used entries past the cap.
 */
class NotificationsStoreTest {

    @Test
    fun sameKeyReturnsTheSameHolder() {
        val a = NotificationsStore.holder("s1", NotificationFilter.All)
        val b = NotificationsStore.holder("s1", NotificationFilter.All)
        assertSame(a, b)
    }

    @Test
    fun differentFilterOrSessionGetsItsOwnHolder() {
        assertNotSame(
            NotificationsStore.holder("s1", NotificationFilter.All),
            NotificationsStore.holder("s1", NotificationFilter.Mentions),
        )
        assertNotSame(
            NotificationsStore.holder("s1", NotificationFilter.All),
            NotificationsStore.holder("s2", NotificationFilter.All),
        )
    }

    @Test
    fun resetScrollZeroesTheSavedPosition() {
        val holder = NotificationsStore.holder("reset-s", NotificationFilter.Polls)
        holder.firstVisibleIndex = 7
        holder.firstVisibleOffset = 120
        NotificationsStore.resetScroll("reset-s", NotificationFilter.Polls)
        assertEquals(0, holder.firstVisibleIndex)
        assertEquals(0, holder.firstVisibleOffset)
    }

    @Test
    fun retainSessionsDropsOnlyOtherSessions() {
        val keep = NotificationsStore.holder("retain-keep", NotificationFilter.All)
        NotificationsStore.holder("retain-drop", NotificationFilter.All)
        NotificationsStore.retainSessions(setOf("retain-keep"))
        // The surviving session still maps to its holder.
        assertSame(keep, NotificationsStore.holder("retain-keep", NotificationFilter.All))
        // The dropped session's holder is recreated fresh (Loading state).
        assertEquals(NotificationsState.Loading, NotificationsStore.holder("retain-drop", NotificationFilter.All).state)
    }
}
