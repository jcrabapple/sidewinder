package net.snakepit.sidewinder.ui.app

import net.snakepit.sidewinder.core.model.Mention
import net.snakepit.sidewinder.core.model.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for in-app content-link routing. The reported bug:
 * tapping a hashtag opened the tag page on the post's origin server in a
 * browser instead of the instance's own hashtag timeline.
 */
class LinkRoutingTest {

    private class Recorder {
        val events = mutableListOf<String>()
        val actions = AppActions(
            openThread = { events += "thread:$it" },
            openProfile = { events += "profile:$it" },
            openTag = { events += "tag:$it" },
            openCompose = { events += "compose:$it" },
            openRemoteAccount = { url, acct -> events += "remote:$acct:$url" },
            openUrl = { events += "url:$it" },
        )
    }

    private fun route(
        url: String,
        text: String,
        tags: List<Tag> = emptyList(),
        mentions: List<Mention> = emptyList(),
    ): List<String> {
        val r = Recorder()
        routeContentLink(url, text, tags, mentions, r.actions)
        return r.events
    }

    @Test
    fun remoteHashtagOpensLocalTagTimeline() {
        val url = "https://mastodon.example/tags/Photography"
        val events = route(url, "#Photography", tags = listOf(Tag("photography", url)))
        // Uses the server-normalised tag name, never the origin URL.
        assertEquals(listOf("tag:photography"), events)
    }

    @Test
    fun hashtagWinsOverMentionWhenBothMatch() {
        // Some servers class hashtag links as "mention hashtag"; a URL that is
        // in the tags list must route as a tag even if text looks odd.
        val url = "https://gts.example/tags/art"
        val events = route(
            url,
            "#art",
            tags = listOf(Tag("art", url)),
            mentions = listOf(Mention("1", "art", "art@gts.example", url)),
        )
        assertEquals(listOf("tag:art"), events)
    }

    @Test
    fun hashtagTextWithoutTagListStillRoutesInApp() {
        // Profile notes and fields have no tags list: fall back to the text.
        assertEquals(listOf("tag:caturday"), route("https://other.example/tag/caturday", "#caturday"))
    }

    @Test
    fun knownMentionOpensLocalProfileById() {
        val url = "https://remote.example/@alice"
        val events = route(url, "@alice", mentions = listOf(Mention("01ABC", "alice", "alice@remote.example", url)))
        assertEquals(listOf("profile:01ABC"), events)
    }

    @Test
    fun unknownMentionTextResolvesRemotely() {
        val url = "https://remote.example/@bob"
        assertEquals(listOf("remote:bob:$url"), route(url, "@bob"))
    }

    @Test
    fun plainLinkOpensBrowser() {
        val url = "https://example.org/article"
        assertEquals(listOf("url:$url"), route(url, "example.org/article"))
    }

    @Test
    fun tagPathAloneDoesNotHijackOrdinaryLinks() {
        // A blog URL ending in /tags/x without "#" link text is just a link.
        val url = "https://blog.example/tags/kotlin"
        assertEquals(listOf("url:$url"), route(url, "my kotlin posts"))
    }

    @Test
    fun tagNameFromPathDecodesAndRejectsNonTags() {
        assertEquals("café", tagNameFromPath("https://x.example/tags/caf%C3%A9"))
        assertEquals("news", tagNameFromPath("https://x.example/tag/news/"))
        assertNull(tagNameFromPath("https://x.example/@user/123"))
    }
}
