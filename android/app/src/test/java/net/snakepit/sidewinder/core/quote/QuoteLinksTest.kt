package net.snakepit.sidewinder.core.quote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure link-quote detection: the RE-line primary form, the
 * last-link-permalink secondary form, mention/hashtag exclusion, the
 * trailing-paragraph strip, and local status-id extraction.
 */
class QuoteLinksTest {

    private val gtsUrl = "https://gts.example.org/@alice/statuses/01ABCDEF0123456789"
    private val mastodonUrl = "https://mastodon.social/@bob/112345678901234567"
    private val akkomaUrl = "https://akkoma.example/notice/AbCdEf123"
    private val misskeyUrl = "https://misskey.example/notes/9abcdef01"

    // ---- detect: primary (explicit RE) ----

    @Test
    fun `an explicit RE paragraph pointing at a link in the content is detected`() {
        val html = "<p>Hot take incoming.</p>" +
            "<p>RE: <a href=\"$gtsUrl\" rel=\"nofollow noopener noreferrer\">$gtsUrl</a></p>"
        val ref = QuoteLinks.detect(html, emptyList(), emptyList())
        assertEquals(gtsUrl, ref?.url)
        assertEquals(true, ref?.explicitRe)
    }

    @Test
    fun `lowercase re is detected too`() {
        val html = "<p>Look</p><p>re: <a href=\"$gtsUrl\">$gtsUrl</a></p>"
        assertEquals(gtsUrl, QuoteLinks.detect(html, emptyList(), emptyList())?.url)
    }

    @Test
    fun `a br-separated RE line is detected`() {
        val html = "<p>Look<br>RE: <a href=\"$gtsUrl\">$gtsUrl</a></p>"
        assertEquals(gtsUrl, QuoteLinks.detect(html, emptyList(), emptyList())?.url)
    }

    @Test
    fun `a plain-text RE tail with no matching link falls back to the last permalink`() {
        // Text ends with RE: but the url is not an anchor; the trailing
        // permalink link is still the secondary form.
        val html = "<p>RE: $gtsUrl</p><p><a href=\"$mastodonUrl\">$mastodonUrl</a></p>"
        val ref = QuoteLinks.detect(html, emptyList(), emptyList())
        assertEquals(mastodonUrl, ref?.url)
        assertEquals(false, ref?.explicitRe)
    }

    // ---- detect: secondary (last permalink link) ----

    @Test
    fun `a bare trailing permalink is the secondary form`() {
        val html = "<p>Worth reading.</p><p><a href=\"$mastodonUrl\">$mastodonUrl</a></p>"
        val ref = QuoteLinks.detect(html, emptyList(), emptyList())
        assertEquals(mastodonUrl, ref?.url)
        assertEquals(false, ref?.explicitRe)
    }

    @Test
    fun `akkoma notice and misskey notes shapes are permalinks`() {
        for (url in listOf(akkomaUrl, misskeyUrl)) {
            val html = "<p><a href=\"$url\">$url</a></p>"
            assertEquals(url, QuoteLinks.detect(html, emptyList(), emptyList())?.url)
        }
    }

    @Test
    fun `a users-statuses shape is a permalink`() {
        val url = "https://gts.example.org/users/alice/statuses/01ABCDEF"
        val html = "<p><a href=\"$url\">$url</a></p>"
        assertEquals(url, QuoteLinks.detect(html, emptyList(), emptyList())?.url)
    }

    // ---- detect: exclusions and false positives ----

    @Test
    fun `a mention link is never a quote`() {
        val mention = net.snakepit.sidewinder.core.model.Mention(
            id = "1", username = "alice", acct = "alice@gts.example.org", url = gtsUrl,
        )
        val html = "<p>Thanks <a href=\"$gtsUrl\">@alice</a></p>"
        assertNull(QuoteLinks.detect(html, listOf(mention), emptyList()))
    }

    @Test
    fun `a hashtag link is never a quote`() {
        val tag = net.snakepit.sidewinder.core.model.Tag(name = "cats", url = "https://gts.example.org/tags/cats")
        val html = "<p>So many <a href=\"${tag.url}\">#cats</a> today</p>"
        assertNull(QuoteLinks.detect(html, emptyList(), listOf(tag)))
    }

    @Test
    fun `an article link is not a quote`() {
        val html = "<p>Read this: <a href=\"https://example.com/articles/why-cats-rule\">Why cats rule</a></p>"
        assertNull(QuoteLinks.detect(html, emptyList(), emptyList()))
    }

    @Test
    fun `content without links is not a quote`() {
        assertNull(QuoteLinks.detect("<p>Just some text</p>", emptyList(), emptyList()))
    }

    // ---- stripReLine ----

    @Test
    fun `the trailing RE paragraph is removed`() {
        val html = "<p>Hot take incoming.</p>" +
            "<p>RE: <a href=\"$gtsUrl\">$gtsUrl</a></p>"
        assertEquals("<p>Hot take incoming.</p>", QuoteLinks.stripReLine(html, gtsUrl))
    }

    @Test
    fun `a trailing br RE line removes the br too`() {
        val html = "<p>Look<br>RE: <a href=\"$gtsUrl\">$gtsUrl</a></p>"
        assertEquals("<p>Look</p>", QuoteLinks.stripReLine(html, gtsUrl))
    }

    @Test
    fun `uppercase RE is stripped`() {
        val html = "<p>x</p><p>RE: <a href=\"$gtsUrl\">$gtsUrl</a></p>"
        assertTrue(QuoteLinks.stripReLine(html, gtsUrl).contains("x"))
        assertTrue(!QuoteLinks.stripReLine(html, gtsUrl).contains("RE:", ignoreCase = true))
    }

    @Test
    fun `a different url is left alone`() {
        val html = "<p>x</p><p>RE: <a href=\"$gtsUrl\">$gtsUrl</a></p>"
        assertEquals(html, QuoteLinks.stripReLine(html, mastodonUrl))
    }

    @Test
    fun `html without an RE tail is unchanged`() {
        val html = "<p>Hot take incoming.</p>"
        assertEquals(html, QuoteLinks.stripReLine(html, gtsUrl))
    }

    // ---- localStatusId ----

    @Test
    fun `a gts status url on the session base yields its id`() {
        val id = "01ABCDEF0123456789"
        assertEquals(id, QuoteLinks.localStatusId(gtsUrl, "https://gts.example.org"))
    }

    @Test
    fun `a users-statuses url on the session base yields its id`() {
        val url = "https://gts.example.org/users/alice/statuses/01XYZ"
        assertEquals("01XYZ", QuoteLinks.localStatusId(url, "https://gts.example.org"))
    }

    @Test
    fun `a remote host is not local`() {
        assertNull(QuoteLinks.localStatusId(mastodonUrl, "https://gts.example.org"))
    }

    @Test
    fun `a non-status path on the local host is not a status`() {
        assertNull(QuoteLinks.localStatusId("https://gts.example.org/@alice", "https://gts.example.org"))
        assertNull(QuoteLinks.localStatusId("https://gts.example.org/tags/cats", "https://gts.example.org"))
    }
}
