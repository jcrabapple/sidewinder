package net.snakepit.sidewinder.core.text

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the status-HTML sanitizer. The original bug: the
 * allowed-scheme regex used [Regex.matches] (whole-string) with a
 * scheme-only pattern, so every link silently degraded to plain text.
 */
class HtmlTextTest {

    private val html =
        """<p>First post. This is <strong>markdown bold</strong>, a <a href="https://example.org" rel="nofollow noreferrer noopener" target="_blank">link</a>, and <code>code</code>.</p>"""

    private fun annotated() = sanitizeHtmlToAnnotatedString(
        html,
        "https://gts.example.org",
        SpanStyle(),
    )

    @Test
    fun `plain text survives sanitization`() {
        // A trailing paragraph break is part of the contract.
        assertEquals(
            "First post. This is markdown bold, a link, and code.",
            annotated().text.trimEnd(),
        )
    }

    @Test
    fun `anchor produces a tappable url annotation`() {
        val links = annotated().getLinkAnnotations(0, annotated().length).toList()
        assertEquals(1, links.size)
        val url = links.single().item as LinkAnnotation.Url
        assertEquals("https://example.org", url.url)
        // The annotation covers exactly the anchor text.
        assertEquals("link", annotated().text.substring(links.single().start, links.single().end))
    }

    @Test
    fun `strong and code map to span styles`() {
        val styles = annotated().spanStyles
        assertTrue(styles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(styles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `javascript scheme is dropped`() {
        val result = sanitizeHtmlToAnnotatedString(
            """<a href="javascript:alert(1)">x</a>""",
            "https://gts.example.org",
            SpanStyle(),
        )
        assertEquals(0, result.getLinkAnnotations(0, result.length).toList().size)
        assertEquals("x", result.text)
    }

    @Test
    fun `relative link resolves against base url`() {
        val result = sanitizeHtmlToAnnotatedString(
            """<a href="/about">about</a>""",
            "https://gts.example.org",
            SpanStyle(),
        )
        val link = result.getLinkAnnotations(0, result.length).toList().single().item as LinkAnnotation.Url
        assertEquals("https://gts.example.org/about", link.url)
    }
}
