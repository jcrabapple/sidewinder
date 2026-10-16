package net.snakepit.sidewinder.ui.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import net.snakepit.sidewinder.core.filters.FilterKeyword
import net.snakepit.sidewinder.core.filters.FilterV2
import net.snakepit.sidewinder.core.filters.FilteredStatus
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Poll
import net.snakepit.sidewinder.core.model.PollOption
import net.snakepit.sidewinder.core.model.Status

/**
 * JVM tests for the pure status-card logic: the synchronous initial
 * filter verdict (K6) and the toggle revert that touches only the
 * toggled field on the latest status (K9/G2-11).
 */
class StatusCardLogicTest {

    private fun account() = Account(id = "a1", username = "jason", acct = "jason@example.org")

    private fun status(
        content: String = "<p>hello world</p>",
        favourited: Boolean = false,
        favouritesCount: Long = 0,
        reblogged: Boolean = false,
        reblogsCount: Long = 0,
        bookmarked: Boolean = false,
        filtered: List<FilteredStatus> = emptyList(),
        poll: Poll? = null,
    ) = Status(
        id = "s1",
        uri = "https://example.org/s/1",
        created_at = "2026-10-16T12:00:00Z",
        account = account(),
        content = content,
        favourited = favourited,
        favourites_count = favouritesCount,
        reblogged = reblogged,
        reblogs_count = reblogsCount,
        bookmarked = bookmarked,
        filtered = filtered,
        poll = poll,
    )

    private fun homeFilter(
        keyword: String,
        action: String = "warn",
    ) = FilterV2(
        id = "f1",
        title = "Spoilers",
        context = listOf("home"),
        filter_action = action,
        keywords = listOf(FilterKeyword(id = "k1", keyword = keyword)),
    )

    // ---- B2-1: synchronous initial verdict ----

    @Test
    fun `server filtered array wins over client match`() {
        val s = status(
            content = "<p>totally innocent</p>",
            filtered = listOf(
                FilteredStatus(filter = homeFilter("innocent", action = "blur"), keyword_matches = listOf("innocent")),
            ),
        )
        val hit = StatusCardLogic.initialFilterHit(
            filtered = s.filtered,
            contentHtml = s.content,
            spoiler = s.spoiler_text,
            pollOptions = emptyList(),
            mediaDescriptions = emptyList(),
            peekedFilters = emptyList(), // even an empty cache must not override the server verdict
            context = "home",
            nowMs = 0,
        )
        assertNotNull(hit)
        assertEquals("blur", hit!!.action)
        assertEquals("Spoilers", hit.title)
    }

    @Test
    fun `client match over peeked filters hits the plain text of html content`() {
        val s = status(content = "<p>spoiler: the <b>dog</b> dies</p>")
        val hit = StatusCardLogic.initialFilterHit(
            filtered = s.filtered,
            contentHtml = s.content,
            spoiler = s.spoiler_text,
            pollOptions = emptyList(),
            mediaDescriptions = emptyList(),
            peekedFilters = listOf(homeFilter("dog dies")),
            context = "home",
            nowMs = 0,
        )
        assertNotNull(hit)
        assertEquals("warn", hit!!.action)
    }

    @Test
    fun `no peeked cache and no server verdict means pending`() {
        val s = status(content = "<p>anything</p>")
        val hit = StatusCardLogic.initialFilterHit(
            filtered = s.filtered,
            contentHtml = s.content,
            spoiler = s.spoiler_text,
            pollOptions = emptyList(),
            mediaDescriptions = emptyList(),
            peekedFilters = null,
            context = "home",
            nowMs = 0,
        )
        assertNull(hit)
    }

    @Test
    fun `filters outside the context do not match`() {
        val s = status(content = "<p>hello</p>")
        val hit = StatusCardLogic.initialFilterHit(
            filtered = s.filtered,
            contentHtml = s.content,
            spoiler = s.spoiler_text,
            pollOptions = emptyList(),
            mediaDescriptions = emptyList(),
            peekedFilters = listOf(homeFilter("hello")),
            context = "thread",
            nowMs = 0,
        )
        assertNull(hit)
    }

    @Test
    fun `hide beats warn when both match`() {
        val s = status(content = "<p>both words here</p>")
        val hit = StatusCardLogic.initialFilterHit(
            filtered = s.filtered,
            contentHtml = s.content,
            spoiler = s.spoiler_text,
            pollOptions = emptyList(),
            mediaDescriptions = emptyList(),
            peekedFilters = listOf(homeFilter("words", action = "warn"), homeFilter("both", action = "hide")),
            context = "home",
            nowMs = 0,
        )
        assertEquals("hide", hit!!.action)
    }

    @Test
    fun `poll options and media descriptions are matched too`() {
        val s = status(
            content = "<p>clean body</p>",
            poll = Poll(id = "p1", options = listOf(PollOption(title = "secret option"))),
        )
        val hit = StatusCardLogic.initialFilterHit(
            filtered = s.filtered,
            contentHtml = s.content,
            spoiler = s.spoiler_text,
            pollOptions = s.poll!!.options.map { it.title },
            mediaDescriptions = listOf("secret alt text"),
            peekedFilters = listOf(homeFilter("secret alt text", action = "blur")),
            context = "home",
            nowMs = 0,
        )
        assertNotNull(hit)
        assertEquals("blur", hit!!.action)
    }

    // ---- B2-5: revert touches only the toggled field on the latest status ----

    @Test
    fun `favourite revert flips only the favourite field`() {
        val latest = status(
            favourited = true, // optimistic value that must be undone
            favouritesCount = 9,
            reblogged = true,
            reblogsCount = 3,
            bookmarked = true,
        )
        val reverted = StatusCardLogic.revertToggle(latest, "favourite", previousActive = false)
        assertEquals(false, reverted.favourited)
        // 9 includes the optimistic +1; the revert must undo it (delta review G2).
        assertEquals(8L, reverted.favourites_count)
        assertEquals(true, reverted.reblogged)
        assertEquals(3L, reverted.reblogs_count)
        assertEquals(true, reverted.bookmarked)
    }

    @Test
    fun `revert keeps concurrent edits on the latest status`() {
        // The click-time status was empty; while the request flew, the
        // post was edited to have different text and counts. The revert
        // must not restore the old snapshot.
        val latest = status(content = "<p>edited body</p>", favourited = true, favouritesCount = 4)
        val reverted = StatusCardLogic.revertToggle(latest, "favourite", previousActive = false)
        assertEquals("<p>edited body</p>", reverted.content)
        assertEquals(false, reverted.favourited)
        assertEquals(3L, reverted.favourites_count) // optimistic +1 undone
    }

    @Test
    fun `revert leaves counts alone when a refresh already restored the flag`() {
        // A server refresh landed first: flag already false, count is authoritative.
        val latest = status(favourited = false, favouritesCount = 7)
        val reverted = StatusCardLogic.revertToggle(latest, "favourite", previousActive = false)
        assertEquals(7L, reverted.favourites_count)
    }

    @Test
    fun `unboost failure restores the boost and its count`() {
        val latest = status(reblogged = false, reblogsCount = 2) // optimistic unboost: 3 -> 2
        val reverted = StatusCardLogic.revertToggle(latest, "reblog", previousActive = true)
        assertEquals(true, reverted.reblogged)
        assertEquals(3L, reverted.reblogs_count)
    }

    @Test
    fun `revert works for boost and bookmark`() {
        val latest = status(reblogged = true, bookmarked = true)
        assertEquals(false, StatusCardLogic.revertToggle(latest, "reblog", previousActive = false).reblogged)
        assertEquals(true, StatusCardLogic.revertToggle(latest, "reblog", previousActive = false).bookmarked)
        assertEquals(false, StatusCardLogic.revertToggle(latest, "bookmark", previousActive = false).bookmarked)
    }

    @Test
    fun `revert on a boost wrapper unwraps nothing`() {
        // revertToggle always receives the INNER status; it must not
        // touch the wrapper shape.
        val inner = status(favourited = true)
        val reverted = StatusCardLogic.revertToggle(inner, "favourite", false)
        assertNull(reverted.reblog)
    }
}
