package net.snakepit.sidewinder.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- Poll eligibility -------------------------------------------------

    @Test
    fun `a poll needs two nonblank options`() {
        assertTrue(pollEligible(pollOn = true, options = listOf("cat", "dog")))
        assertTrue(pollEligible(pollOn = true, options = listOf("cat", "dog", "")))
        assertFalse(pollEligible(pollOn = true, options = listOf("cat", "")))
        assertFalse(pollEligible(pollOn = true, options = listOf("", "")))
    }

    @Test
    fun `a poll with an over-limit option is not eligible`() {
        assertFalse(pollEligible(pollOn = true, options = listOf("ok", "x".repeat(POLL_OPTION_LIMIT + 1))))
        assertTrue(pollEligible(pollOn = true, options = listOf("ok", "x".repeat(POLL_OPTION_LIMIT))))
    }

    @Test
    fun `poll eligibility is off when the poll is off`() {
        assertFalse(pollEligible(pollOn = false, options = listOf("cat", "dog")))
    }

    @Test
    fun `a disabled poll does not block an ordinary post`() {
        assertFalse(pollBlocksPosting(pollOn = false, options = listOf("", "")))
        assertFalse(pollBlocksPosting(pollOn = false, options = listOf("cat", "dog")))
    }

    @Test
    fun `an enabled incomplete poll blocks posting`() {
        assertTrue(pollBlocksPosting(pollOn = true, options = listOf("cat", "")))
        assertFalse(pollBlocksPosting(pollOn = true, options = listOf("cat", "dog")))
    }

    // ---- Sensitivity ------------------------------------------------------

    @Test
    fun `a text-only sensitive edit stays sensitive`() {
        assertTrue(
            submittedSensitive(
                cwOn = false,
                spoiler = "",
                markSensitive = true,
                hasMedia = false,
                isEdit = true,
            ),
        )
    }

    @Test
    fun `a new post without media does not submit the media toggle`() {
        assertFalse(
            submittedSensitive(
                cwOn = false,
                spoiler = "",
                markSensitive = true,
                hasMedia = false,
                isEdit = false,
            ),
        )
    }

    @Test
    fun `a content warning marks the post sensitive`() {
        assertTrue(submittedSensitive(cwOn = true, spoiler = "spoilers", markSensitive = false, hasMedia = false, isEdit = false))
        assertFalse(submittedSensitive(cwOn = true, spoiler = "  ", markSensitive = false, hasMedia = false, isEdit = false))
    }

    // ---- URL weighting ----------------------------------------------------

    @Test
    fun `a url weighs the reserved length regardless of its raw size`() {
        val short = weightedLength("see https://a.b")
        val long = weightedLength("see https://a.b/" + "x".repeat(200))
        assertEquals("see ".length + URL_WEIGHT, short)
        assertEquals(short, long)
    }

    @Test
    fun `every url in the text is weighted`() {
        val text = "https://a.b and https://c.d"
        assertEquals(URL_WEIGHT * 2 + " and ".length, weightedLength(text))
    }

    @Test
    fun `a content warning adds its own weighted text`() {
        // "body" (4) + "cw " (3) + URL_WEIGHT for the warning's link.
        assertEquals(4 + 3 + URL_WEIGHT, composerUsed(text = "body", spoiler = "cw https://a.b", cwOn = true, quotedUrl = null))
    }

    // ---- Insertion and autocomplete tokens --------------------------------

    @Test
    fun `insertion replaces the selection and moves the caret`() {
        val result = insertAtSelection("hello world", selectionStart = 6, selectionEnd = 11, snippet = "there")
        assertEquals("hello there", result.text)
        assertEquals(11, result.cursor)
    }

    @Test
    fun `insertion clamps out-of-range selection indices`() {
        val result = insertAtSelection("hi", selectionStart = 99, selectionEnd = 99, snippet = "!")
        assertEquals("hi!", result.text)
        assertEquals(3, result.cursor)
    }

    @Test
    fun `a mention token is found at the caret`() {
        assertEquals(
            AutocompleteToken(start = 6, end = 12, type = "accounts", query = "alice"),
            autocompleteToken("hello @alice", cursor = 12),
        )
    }

    @Test
    fun `a hashtag token is found after a newline`() {
        assertEquals(
            AutocompleteToken(start = 2, end = 7, type = "hashtags", query = "cats"),
            autocompleteToken("x\n#cats", cursor = 7),
        )
    }

    @Test
    fun `a bare sigil does not open autocomplete`() {
        assertNull(autocompleteToken("hello @", cursor = 7))
        assertNull(autocompleteToken("hello #", cursor = 7))
    }

    @Test
    fun `a plain word does not open autocomplete`() {
        assertNull(autocompleteToken("hello world", cursor = 11))
    }

    @Test
    fun `snippets carry the sigil and a trailing space`() {
        assertEquals("@alice ", autocompleteSnippet("accounts", autocompleteValue("@alice")))
        assertEquals("#cats ", autocompleteSnippet("hashtags", autocompleteValue("#cats")))
    }

    // ---- Thread chain -----------------------------------------------------

    @Test
    fun `an empty chain is valid`() {
        assertTrue(chainValid(emptyList(), cwOn = false, spoiler = ""))
    }

    @Test
    fun `a blank segment invalidates the chain`() {
        assertFalse(chainValid(listOf("second post", "   "), cwOn = false, spoiler = ""))
    }

    @Test
    fun `each segment is checked against the weighted limit`() {
        val over = "x".repeat(CHAR_LIMIT + 1)
        assertFalse(chainValid(listOf("fine", over), cwOn = false, spoiler = ""))
        // A URL weighs URL_WEIGHT, not its raw length.
        val longUrl = "see https://example.test/" + "a".repeat(200)
        assertTrue(chainValid(listOf(longUrl), cwOn = false, spoiler = ""))
    }

    @Test
    fun `the content warning counts once per segment`() {
        val nearlyFull = "x".repeat(CHAR_LIMIT - 1)
        assertFalse(chainValid(listOf(nearlyFull), cwOn = true, spoiler = "cw"))
        assertTrue(chainValid(listOf(nearlyFull), cwOn = false, spoiler = "cw"))
    }

    @Test
    fun `a segment message describes blank and over-limit posts`() {
        assertEquals("This post is empty. Write something or remove it.", chainSegmentMessage("  ", false, ""))
        assertNull(chainSegmentMessage("fine", false, ""))
        val over = chainSegmentMessage("x".repeat(CHAR_LIMIT + 2), false, "")
        assertEquals("This post is 2 characters over the $CHAR_LIMIT-character limit.", over)
    }

    @Test
    fun `posting resumes after the posts that already succeeded`() {
        // postedCount counts the root as 1.
        assertEquals(0, chainResumeIndex(0))
        assertEquals(0, chainResumeIndex(1))
        assertEquals(1, chainResumeIndex(2))
        assertEquals(3, chainResumeIndex(4))
    }

    @Test
    fun `segments before the resume point are marked posted`() {
        assertFalse(chainSegmentPosted(index = 0, postedCount = 1))
        assertFalse(chainSegmentPosted(index = 0, postedCount = 0))
        assertTrue(chainSegmentPosted(index = 0, postedCount = 2))
        assertFalse(chainSegmentPosted(index = 1, postedCount = 2))
        assertTrue(chainSegmentPosted(index = 1, postedCount = 3))
        assertTrue(chainSegmentPosted(index = 0, postedCount = 3))
    }

    @Test
    fun `segment labels are 1-based with the root as post 1`() {
        assertEquals("Post 2", chainPostLabel(0))
        assertEquals("Post 4", chainPostLabel(2))
    }

    @Test
    fun `chain text changes the dirty fingerprint`() {
        fun withChain(chain: List<String>): String = composeFingerprint(
            text = "hello",
            cwOn = false,
            spoiler = "",
            markSensitive = false,
            media = listOf("a1:cat"),
            pollOn = false,
            pollOptions = listOf("", ""),
            pollMultiple = false,
            pollExpiry = 86_400L,
            visibility = "public",
            scheduleOn = false,
            scheduleAtMs = 0L,
            language = "",
            chain = chain,
        )
        assertNotEquals(withChain(emptyList()), withChain(listOf("more")))
        assertNotEquals(withChain(listOf("more")), withChain(listOf("different")))
        assertEquals(withChain(listOf("more")), withChain(listOf("more")))
    }
}
