package net.snakepit.sidewinder.ui.profile

import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Status
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for K37: a status update on one profile tab must be
 * applied to every cached tab, and inside boost wrappers the update
 * replaces the inner status, not the wrapper.
 */
class ProfileTabUpdatesTest {

    private val author = Account(id = "a1", username = "me", acct = "me")

    private fun status(id: String, favourited: Boolean = false, reblog: Status? = null) =
        Status(
            id = id,
            uri = "https://x.example/s/$id",
            created_at = "2026-10-16T12:00:00Z",
            account = if (reblog != null) author else author,
            content = "post $id",
            favourited = favourited,
            reblog = reblog,
        )

    @Test
    fun `update replaces the matching row by id`() {
        val items = listOf(status("1"), status("2"))
        val updated = status("2", favourited = true)
        val result = ProfileTabUpdates.updatedItems(items, updated)
        assertEquals(listOf(status("1"), status("2", favourited = true)), result)
    }

    @Test
    fun `update replaces the inner status inside a boost wrapper`() {
        val original = status("inner", favourited = false)
        val wrapper = status("wrapper-1", reblog = original)
        val updated = status("inner", favourited = true)

        val result = ProfileTabUpdates.updatedItems(listOf(wrapper), updated)

        val newWrapper = result.single()
        assertEquals("wrapper-1", newWrapper.id) // wrapper identity preserved
        assertEquals(true, newWrapper.reblog?.favourited) // inner status updated
        assertEquals("inner", newWrapper.reblog?.id)
    }

    @Test
    fun `unrelated rows and wrappers are left untouched`() {
        val wrapper = status("w", reblog = status("other-inner"))
        val plain = status("plain")
        val updated = status("inner", favourited = true)

        val result = ProfileTabUpdates.updatedItems(listOf(wrapper, plain), updated)

        assertEquals(listOf(wrapper, plain), result)
    }
}
