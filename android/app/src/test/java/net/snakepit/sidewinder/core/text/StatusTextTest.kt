package net.snakepit.sidewinder.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StatusText]: plain-text extraction semantics, empty-HTML
 * semantics matching [htmlIsEmpty], and the LRU cache (hit consistency and
 * eviction at ~1000 entries).
 */
class StatusTextTest {

    @Test
    fun plainStripsTags() {
        assertEquals("hello world", StatusText.plain("<p>hello</p> world"))
    }

    @Test
    fun plainCollapsesWhitespaceAndTrims() {
        assertEquals("a b c", StatusText.plain("<p>  a\n  b\t </p> c  "))
    }

    @Test
    fun plainEmptyInput() {
        assertEquals("", StatusText.plain(""))
        assertEquals("", StatusText.plain("<p></p>"))
    }

    @Test
    fun cacheHitReturnsEqualValue() {
        val html = "<p>cached value</p>"
        val first = StatusText.plain(html)
        val second = StatusText.plain(html)
        assertEquals(first, second)
        assertTrue(StatusText.isEmptyHtml(html) == false)
    }

    @Test
    fun evictionCapsTheCache() {
        // Insert well past the ~1000 entry cap; the cache must not grow
        // unbounded and earlier lookups must stay consistent.
        for (i in 0 until 1200) {
            StatusText.plain("<p>v$i</p>")
        }
        assertTrue(StatusText.plainCacheSize() <= 1000)
        // An evicted entry recomputes to the same value.
        assertEquals("v0", StatusText.plain("<p>v0</p>"))
    }

    @Test
    fun emptyHtmlCases() {
        assertTrue(StatusText.isEmptyHtml(""))
        assertTrue(StatusText.isEmptyHtml("   "))
        assertTrue(StatusText.isEmptyHtml("<p>  </p>"))
        assertFalse(StatusText.isEmptyHtml("<p>hi</p>"))
        assertFalse(StatusText.isEmptyHtml("<br>"))
        assertFalse(StatusText.isEmptyHtml("<img src=\"x\">"))
        assertTrue(StatusText.isEmptyHtml("<span></span>"))
    }
}
