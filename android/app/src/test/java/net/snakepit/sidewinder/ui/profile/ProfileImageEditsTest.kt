package net.snakepit.sidewinder.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileImageEditsTest {

    @Test
    fun `unchanged descriptions and images are clean and send no description fields`() {
        val edits = ProfileImageEdits(
            initialAvatarDescription = "A portrait",
            initialHeaderDescription = "Mountains",
            avatarDescription = "A portrait",
            headerDescription = "Mountains",
        )

        assertFalse(edits.dirty)
        assertTrue(edits.descriptionFields().isEmpty())
    }

    @Test
    fun `text-only avatar description edit is dirty and sends its field`() {
        val edits = ProfileImageEdits(avatarDescription = "A new portrait")

        assertTrue(edits.dirty)
        assertEquals(mapOf("avatar_description" to "A new portrait"), edits.descriptionFields())
    }

    @Test
    fun `clearing an existing header description is dirty and sends an empty value`() {
        val edits = ProfileImageEdits(
            initialHeaderDescription = "Mountains at dawn",
            headerDescription = "",
        )

        assertTrue(edits.dirty)
        assertEquals(mapOf("header_description" to ""), edits.descriptionFields())
    }

    @Test
    fun `replacement image includes an intentionally empty description`() {
        val edits = ProfileImageEdits(avatarSelected = true)

        assertTrue(edits.dirty)
        assertEquals(mapOf("avatar_description" to ""), edits.descriptionFields())
    }
}
