package net.snakepit.sidewinder.ui.timeline

import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.timeline.TimelineSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the timeline store behind "resume your place":
 * cached feeds must survive navigation yet never show a stale version of a
 * post that was edited, deleted, or interacted with elsewhere.
 */
class TimelineStoreTest {

    private val author = Account(id = "a1", username = "me", acct = "me")
    private val other = Account(id = "a2", username = "them", acct = "them@x.example")

    private fun status(id: String, content: String = "c$id", reblog: Status? = null, replyTo: String? = null) =
        Status(
            id = id,
            uri = "https://x.example/s/$id",
            created_at = "2026-10-16T12:00:00Z",
            account = if (reblog != null) other else author,
            content = content,
            reblog = reblog,
            in_reply_to_id = replyTo,
        )

    private fun load(session: String, source: TimelineSource, items: List<Status>): TimelineHolder =
        TimelineStore.holder(session, source).also {
            it.state = TimelineState.Content(items, nextMaxId = "next", loadingMore = false)
        }

    private fun items(holder: TimelineHolder) = (holder.state as TimelineState.Content).items

    @Before
    fun reset() = TimelineStore.retainSessions(emptySet())

    @Test
    fun holderIsReusedForSameSessionAndSource() {
        val a = TimelineStore.holder("s1", TimelineSource.Home)
        assertSame(a, TimelineStore.holder("s1", TimelineSource.Home))
        assertNotSame(a, TimelineStore.holder("s1", TimelineSource.Local))
        assertNotSame(a, TimelineStore.holder("s2", TimelineSource.Home))
    }

    @Test
    fun editReplacesPostDirectlyAndInsideBoostsAcrossFeeds() {
        val original = status("p1")
        val home = load("s1", TimelineSource.Home, listOf(original, status("p2")))
        val local = load("s1", TimelineSource.Local, listOf(status("b1", reblog = original)))

        TimelineStore.applyEdit(original.copy(content = "fixed typo"))

        assertEquals("fixed typo", items(home)[0].content)
        assertEquals("cp2", items(home)[1].content)
        val boost = items(local)[0]
        assertEquals("b1", boost.id) // the boost wrapper is kept...
        assertEquals("fixed typo", boost.reblog?.content) // ...with the edited post inside
    }

    @Test
    fun deleteRemovesPostAndBoostsOfIt() {
        val target = status("p1")
        val home = load("s1", TimelineSource.Home, listOf(target, status("b1", reblog = target), status("p2")))
        TimelineStore.applyDelete("p1")
        assertEquals(listOf("p2"), items(home).map { it.id })
    }

    @Test
    fun deletingTheLastPostOfAFinishedFeedShowsEmpty() {
        val holder = TimelineStore.holder("s1", TimelineSource.Bookmarks)
        holder.state = TimelineState.Content(listOf(status("p1")), nextMaxId = null, loadingMore = false)
        TimelineStore.applyDelete("p1")
        assertEquals(TimelineState.Empty, holder.state)
    }

    @Test
    fun newTopLevelPostLandsOnTopOfHomeAndKeepsReadingPlace() {
        val home = load("s1", TimelineSource.Home, listOf(status("p1"), status("p2")))
        home.firstVisibleIndex = 5
        TimelineStore.applyPosted("s1", status("new"))
        assertEquals(listOf("new", "p1", "p2"), items(home).map { it.id })
        assertEquals(6, home.firstVisibleIndex)
    }

    @Test
    fun repliesAndOtherAccountsDoNotLandOnHome() {
        val home = load("s1", TimelineSource.Home, listOf(status("p1")))
        TimelineStore.applyPosted("s1", status("r1", replyTo = "p1"))
        TimelineStore.applyPosted("s2", status("other-account"))
        assertEquals(listOf("p1"), items(home).map { it.id })
    }

    @Test
    fun signedOutSessionsAreDropped() {
        val kept = load("s1", TimelineSource.Home, listOf(status("p1")))
        val dropped = load("s2", TimelineSource.Home, listOf(status("p2")))
        TimelineStore.retainSessions(setOf("s1"))
        assertSame(kept, TimelineStore.holder("s1", TimelineSource.Home))
        assertNotSame(dropped, TimelineStore.holder("s2", TimelineSource.Home))
    }

    @Test
    fun leastRecentlyUsedFeedIsEvictedPastTheCap() {
        val first = TimelineStore.holder("s1", TimelineSource.Hashtag("t0"))
        repeat(12) { TimelineStore.holder("s1", TimelineSource.Hashtag("t${it + 1}")) }
        // t0 was the least recently used of 13: it must have been evicted.
        assertTrue(first !== TimelineStore.holder("s1", TimelineSource.Hashtag("t0")))
    }
}
