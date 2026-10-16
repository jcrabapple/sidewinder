package net.snakepit.sidewinder.ui.notifications

import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Re-entry refresh of a cached notifications list ([mergeFreshFirstPage]). */
class NotificationsMergeTest {

    private fun n(id: String) = Notification(
        id = id,
        type = "mention",
        created_at = "2026-10-16T12:00:00Z",
        account = Account(id = "a$id", username = "alice", acct = "alice@example.social"),
    )

    private fun content(vararg ids: String, next: String? = "cursor-old") =
        NotificationsState.Content(ids.map { n(it) }, next, loadingMore = false)

    @Test
    fun overlappingPagePrependsNewItemsAndKeepsCachedTail() {
        val merged = mergeFreshFirstPage(content("n3", "n2", "n1"), listOf(n("n5"), n("n4"), n("n3")), "cursor-new")
        merged as NotificationsState.Content
        assertEquals(listOf("n5", "n4", "n3", "n2", "n1"), merged.items.map { it.id })
        assertEquals("cursor-old", merged.nextMaxId)
    }

    @Test
    fun gapReplacesTheCacheWithTheFreshPage() {
        val merged = mergeFreshFirstPage(content("n3", "n2"), listOf(n("n9"), n("n8")), "cursor-new")
        merged as NotificationsState.Content
        assertEquals(listOf("n9", "n8"), merged.items.map { it.id })
        assertEquals("cursor-new", merged.nextMaxId)
    }

    @Test
    fun nothingNewKeepsTheSameState() {
        val state = content("n2", "n1")
        assertSame(state, mergeFreshFirstPage(state, listOf(n("n2"), n("n1")), "x"))
    }

    @Test
    fun emptyFreshPageKeepsCachedContent() {
        val state = content("n1")
        assertSame(state, mergeFreshFirstPage(state, emptyList(), null))
    }
}
