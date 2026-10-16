package net.snakepit.sidewinder.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure quote-mode composer logic: the posted text and visibility demotion. */
class QuoteComposeTest {

    @Test
    fun `the posted text is commentary, blank line, and the RE line`() {
        assertEquals(
            "Good point about cats\n\nRE: https://gts.example.org/@alice/statuses/01ABC",
            quotePostText("Good point about cats", "https://gts.example.org/@alice/statuses/01ABC"),
        )
    }

    @Test
    fun `trailing whitespace on the commentary is trimmed`() {
        assertEquals(
            "Nice\n\nRE: https://example.com/@a/1",
            quotePostText("Nice \n", "https://example.com/@a/1"),
        )
    }

    @Test
    fun `the RE line costs url weight plus RE and the blank line`() {
        assertEquals(25 + 4 + 2, QUOTE_OVERHEAD)
    }

    @Test
    fun `a quote default more public than the quoted post demotes`() {
        assertEquals("unlisted", demoteVisibility(default = "public", parent = "unlisted"))
        assertEquals("private", demoteVisibility(default = "public", parent = "private"))
        assertEquals("direct", demoteVisibility(default = "unlisted", parent = "direct"))
    }

    @Test
    fun `a quoted post at least as public keeps the user default`() {
        assertEquals("public", demoteVisibility(default = "public", parent = "public"))
        assertEquals("unlisted", demoteVisibility(default = "unlisted", parent = "public"))
        assertEquals("direct", demoteVisibility(default = "direct", parent = "unlisted"))
    }

    @Test
    fun `no quoted post leaves the user default`() {
        assertEquals("public", demoteVisibility(default = "public", parent = null))
    }
}
