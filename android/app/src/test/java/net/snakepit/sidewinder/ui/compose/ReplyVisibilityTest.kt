package net.snakepit.sidewinder.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reply-visibility demotion: a reply may only offer visibilities of
 * equal-or-lower reach than its parent (public < unlisted < private < direct).
 */
class ReplyVisibilityTest {

    @Test
    fun `ranks increase as reach narrows`() {
        assertEquals(0, visibilityRank("public"))
        assertEquals(1, visibilityRank("unlisted"))
        assertEquals(2, visibilityRank("private"))
        assertEquals(3, visibilityRank("direct"))
    }

    @Test
    fun `unknown and server-specific visibilities rank lowest`() {
        assertEquals(0, visibilityRank("local"))
        assertEquals(0, visibilityRank("mutuals_only"))
        assertEquals(0, visibilityRank(""))
    }

    @Test
    fun `reply to a public post offers every option`() {
        val options = VISIBILITY_OPTIONS.filter { visibilityRank(it.value) >= visibilityRank("public") }
        assertEquals(4, options.size)
    }

    @Test
    fun `reply to a private post excludes public and unlisted`() {
        val options = VISIBILITY_OPTIONS.filter { visibilityRank(it.value) >= visibilityRank("private") }
        assertEquals(listOf("private", "direct"), options.map { it.value })
    }

    @Test
    fun `reply to a direct post offers only direct`() {
        val options = VISIBILITY_OPTIONS.filter { visibilityRank(it.value) >= visibilityRank("direct") }
        assertEquals(listOf("direct"), options.map { it.value })
    }

    @Test
    fun `unknown parent visibility leaves every option available`() {
        val options = VISIBILITY_OPTIONS.filter { visibilityRank(it.value) >= visibilityRank("local") }
        assertEquals(4, options.size)
        assertTrue(options.any { it.value == "public" })
        assertFalse(options.isEmpty())
    }
}
