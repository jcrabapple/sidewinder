package net.snakepit.sidewinder.core.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRepositoryTest {

    @Test
    fun `pasted http and https URLs resolve by default`() {
        assertTrue(shouldResolveSearch("https://example.social/@person/123"))
        assertTrue(shouldResolveSearch("http://example.test/status/123"))
    }

    @Test
    fun `URL scheme detection ignores case and surrounding whitespace`() {
        assertTrue(shouldResolveSearch("  HTTPS://EXAMPLE.SOCIAL/@person  "))
    }

    @Test
    fun `ordinary queries and lookalike schemes do not resolve`() {
        assertFalse(shouldResolveSearch("example.social/@person"))
        assertFalse(shouldResolveSearch("ftp://example.test/status/123"))
    }
}
