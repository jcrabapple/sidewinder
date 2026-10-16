package net.snakepit.sidewinder.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure composer logic: the dirty-check fingerprint and the edit-mode
 * media_ids guard.
 */
class ComposeLogicTest {

    private fun fingerprint(
        text: String = "hello",
        cwOn: Boolean = false,
        spoiler: String = "",
        markSensitive: Boolean = false,
        media: List<String> = listOf("a1:cat"),
        pollOn: Boolean = false,
        pollOptions: List<String> = listOf("", ""),
        pollMultiple: Boolean = false,
        pollExpiry: Long = 86_400L,
        visibility: String = "public",
        scheduleOn: Boolean = false,
        scheduleAtMs: Long = 0L,
    ): String = composeFingerprint(
        text, cwOn, spoiler, markSensitive, media,
        pollOn, pollOptions, pollMultiple, pollExpiry,
        visibility, scheduleOn, scheduleAtMs,
    )

    @Test
    fun `identical state yields an identical fingerprint`() {
        assertEquals(fingerprint(), fingerprint())
    }

    @Test
    fun `a reply that only gained a poll changes the fingerprint`() {
        assertNotEquals(fingerprint(), fingerprint(pollOn = true, pollOptions = listOf("yes", "no")))
    }

    @Test
    fun `typed poll options change the fingerprint`() {
        assertNotEquals(
            fingerprint(pollOn = true, pollOptions = listOf("yes", "no")),
            fingerprint(pollOn = true, pollOptions = listOf("yes", "maybe")),
        )
    }

    @Test
    fun `poll settings change the fingerprint`() {
        val base = fingerprint(pollOn = true, pollOptions = listOf("a", "b"))
        assertNotEquals(base, fingerprint(pollOn = true, pollOptions = listOf("a", "b"), pollMultiple = true))
        assertNotEquals(base, fingerprint(pollOn = true, pollOptions = listOf("a", "b"), pollExpiry = 3_600L))
    }

    @Test
    fun `visibility and schedule changes change the fingerprint`() {
        assertNotEquals(fingerprint(), fingerprint(visibility = "private"))
        assertNotEquals(fingerprint(), fingerprint(scheduleOn = true, scheduleAtMs = 1_700_000_000_000L))
        assertNotEquals(
            fingerprint(scheduleOn = true, scheduleAtMs = 1_700_000_000_000L),
            fingerprint(scheduleOn = true, scheduleAtMs = 1_800_000_000_000L),
        )
    }

    @Test
    fun `media alt text changes the fingerprint`() {
        assertNotEquals(fingerprint(), fingerprint(media = listOf("a1:cat on a mat")))
        assertNotEquals(fingerprint(), fingerprint(media = listOf("a1:", "b2:")))
    }

    @Test
    fun `edit with untouched media keeps media_ids`() {
        val ids = editMediaIds(
            currentIds = listOf("m1", "m2"),
            originalIds = listOf("m1", "m2"),
            removedIds = emptySet(),
        )
        assertEquals(listOf("m1", "m2"), ids)
    }

    @Test
    fun `an attachment removed this session is dropped from media_ids`() {
        val ids = editMediaIds(
            currentIds = listOf("m2"),
            originalIds = listOf("m1", "m2"),
            removedIds = setOf("m1"),
        )
        assertEquals(listOf("m2"), ids)
    }

    @Test
    fun `an attachment missing from the live list is never silently dropped`() {
        // Upload died / restore glitch: the user never removed it, so the
        // edit must still keep it.
        val ids = editMediaIds(
            currentIds = listOf("m2"),
            originalIds = listOf("m1", "m2"),
            removedIds = emptySet(),
        )
        assertEquals(listOf("m2", "m1"), ids)
    }

    @Test
    fun `current ids without an original survive untouched`() {
        val ids = editMediaIds(
            currentIds = listOf("m1", "m9"),
            originalIds = listOf("m1"),
            removedIds = emptySet(),
        )
        assertEquals(listOf("m1", "m9"), ids)
    }

    @Test
    fun `blank and duplicate current ids are ignored`() {
        val ids = editMediaIds(
            currentIds = listOf("m1", "", "m1"),
            originalIds = listOf("m1"),
            removedIds = emptySet(),
        )
        assertEquals(listOf("m1"), ids)
    }
}
