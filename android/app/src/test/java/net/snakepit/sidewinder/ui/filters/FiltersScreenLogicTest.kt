package net.snakepit.sidewinder.ui.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K55 revert semantics and K56 expiry formatting. */
class FiltersScreenLogicTest {

    private fun filter(id: String) = net.snakepit.sidewinder.core.filters.FilterV2(
        id = id,
        title = "Filter $id",
        context = listOf("home"),
    )

    @Test
    fun `failed delete revert of a non-empty list restores the previous content`() {
        val previous = listOf(filter("1"), filter("2"))
        val reverted = stateAfterRevert(previous)
        assertTrue(reverted is FiltersState.Content)
        assertEquals(previous, (reverted as FiltersState.Content).items)
    }

    @Test
    fun `failed delete revert of an emptied list goes back to Empty not Content of nothing`() {
        assertTrue(stateAfterRevert(emptyList()) is FiltersState.Empty)
    }

    @Test
    fun `expires_at renders as a localized date time, not the raw ISO string`() {
        val formatted = formatExpiresAt("2026-11-01T14:30:00.000Z")
        // The exact rendering is locale-dependent, but it must parse, be
        // non-blank, and no longer carry the ISO separators.
        assertNotEquals("2026-11-01T14:30:00.000Z", formatted)
        assertTrue(formatted.orEmpty().isNotBlank())
        assertTrue("ISO T separator must be gone", formatted.orEmpty().none { it == 'T' })
    }

    @Test
    fun `unparseable expires_at returns null so the row can fall back to the raw text`() {
        assertNull(formatExpiresAt("not-a-date"))
    }
}
