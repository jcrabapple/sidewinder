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
    fun postedStatusTurnsAnEmptyHomeFeedIntoContent() {
        // applyPosted used to no-op on Empty, silently dropping the post
        // the composer just published on a fresh account's empty feed.
        val home = TimelineStore.holder("s1", TimelineSource.Home).also {
            it.state = TimelineState.Empty
        }
        TimelineStore.applyPosted("s1", status("new"))
        val state = home.state as TimelineState.Content
        assertEquals(listOf("new"), state.items.map { it.id })
        assertEquals("new", state.nextMaxId)
        assertEquals(false, state.loadingMore)
    }

    @Test
    fun streamedStatusTurnsAnEmptyHomeFeedIntoContent() {
        val home = TimelineStore.holder("s1", TimelineSource.Home).also {
            it.state = TimelineState.Empty
        }

        TimelineStore.applyStreamed("s1", status("new"))

        val state = home.state as TimelineState.Content
        assertEquals(listOf("new"), state.items.map { it.id })
        assertEquals("new", state.nextMaxId)
        assertEquals(false, state.loadingMore)
        assertEquals(0, home.pendingNewPosts)
    }

    @Test
    fun streamedPostsCountEveryPrependUntilTheUiMarksItSeen() {
        val home = load("s1", TimelineSource.Home, listOf(status("p1")))

        TimelineStore.applyStreamed("s1", status("top"))
        assertEquals(1, home.pendingNewPosts)

        // TimelineScreen clears the pill immediately when that prepend is
        // already visible at the top.
        home.pendingNewPosts = 0
        home.firstVisibleOffset = 1
        TimelineStore.applyStreamed("s1", status("offset"))
        assertEquals(1, home.pendingNewPosts)

        home.firstVisibleIndex = 4
        home.firstVisibleOffset = 0
        TimelineStore.applyStreamed("s1", status("away"))
        assertEquals(2, home.pendingNewPosts)
        assertEquals(5, home.firstVisibleIndex)
        assertEquals(listOf("away", "offset", "top", "p1"), items(home).map { it.id })

        TimelineStore.applyStreamed("s1", status("away"))
        assertEquals(2, home.pendingNewPosts)
        assertEquals(5, home.firstVisibleIndex)

        home.firstVisibleIndex = 0
        home.pendingNewPosts = 0
        TimelineStore.applyStreamed("s1", status("back-at-top"))
        assertEquals(1, home.pendingNewPosts)
    }

    @Test
    fun editsThatMatchNothingLeaveHolderStateUntouched() {
        // Only holders containing the edited post may be reassigned; a
        // no-match edit must not churn every other cached feed's state.
        val home = load("s1", TimelineSource.Home, listOf(status("p1"), status("p2")))
        val stateBefore = home.state
        TimelineStore.applyEdit(status("unrelated", content = "no match anywhere"))
        assertSame(stateBefore, home.state)
        assertEquals(listOf("p1", "p2"), items(home).map { it.id })
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

    @Test
    fun expandedStatePersistsAndClears() {
        TimelineStore.setExpanded("s1", true)
        assertTrue(TimelineStore.isExpanded("s1"))
        TimelineStore.setExpanded("s1", false)
        assertTrue(!TimelineStore.isExpanded("s1"))
    }

    @Test
    fun expandedStateEvictsLeastRecentlyUsedPastTheCap() {
        repeat(200) { TimelineStore.setExpanded("e$it", true) }
        TimelineStore.setExpanded("e0", true) // touch e0: becomes most recently used
        TimelineStore.setExpanded("fresh", true)
        assertTrue(TimelineStore.isExpanded("e0"))
        assertTrue(!TimelineStore.isExpanded("e1")) // oldest untouched entry evicted first
    }
}
