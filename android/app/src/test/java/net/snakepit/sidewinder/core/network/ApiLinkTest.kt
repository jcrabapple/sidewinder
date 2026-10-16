package net.snakepit.sidewinder.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for Link-header next-page parsing ([nextMaxIdFromLink]):
 * absolute and relative next links, malformed URLs (null instead of a
 * throw), and headers without a next entry.
 */
class ApiLinkTest {

    private val root = "https://gts.example.org".toHttpUrl()

    @Test
    fun absoluteNextLink() {
        val link = """<https://gts.example.org/api/v1/timelines/home?max_id=987>; rel="next""""
        assertEquals("987", nextMaxIdFromLink(root, link))
    }

    @Test
    fun relativeNextLinkResolvesAgainstRoot() {
        val link = """</api/v1/timelines/home?max_id=42>; rel="next""""
        assertEquals("42", nextMaxIdFromLink(root, link))
    }

    @Test
    fun malformedUrlReturnsNullInsteadOfThrowing() {
        val link = """<://not a url>; rel="next""""
        assertNull(nextMaxIdFromLink(root, link))
    }

    @Test
    fun missingNextReturnsNull() {
        val link = """<https://gts.example.org/api/v1/timelines/home?max_id=5>; rel="prev""""
        assertNull(nextMaxIdFromLink(root, link))
    }

    @Test
    fun nullHeaderReturnsNull() {
        assertNull(nextMaxIdFromLink(root, null))
    }

    @Test
    fun combinedPrevAndNextPicksNext() {
        val link = """<https://gts.example.org/api?max_id=1>; rel="prev", <https://gts.example.org/api?max_id=2>; rel="next""""
        assertEquals("2", nextMaxIdFromLink(root, link))
    }
}
