package net.snakepit.sidewinder.core.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure filter engine: context and expiry selection,
 * literal and whole-word keyword matching across every text field, and
 * severity ordering among multiple matches.
 */
class FilterEngineTest {

    private fun keyword(
        keyword: String,
        wholeWord: Boolean = false,
    ) = FilterKeyword(id = "k-$keyword", keyword = keyword, whole_word = wholeWord)

    private fun filter(
        id: String = "f1",
        title: String = "Test filter",
        context: List<String> = listOf("home"),
        action: String = "warn",
        expiresAt: String? = null,
        keywords: List<FilterKeyword> = emptyList(),
    ) = FilterV2(
        id = id,
        title = title,
        context = context,
        expires_at = expiresAt,
        filter_action = action,
        keywords = keywords,
    )

    private fun text(
        body: String = "",
        spoiler: String = "",
        polls: List<String> = emptyList(),
        media: List<String> = emptyList(),
    ) = FilterableText(body, spoiler, polls, media)

    // ---- context filtering ----

    @Test
    fun verdictIgnoresFiltersOutsideContext() {
        val hit = FilterEngine.verdict(
            listOf(FilteredStatus(filter = filter(context = listOf("public")))),
            context = "home",
            nowMs = 0L,
        )
        assertNull(hit)
    }

    @Test
    fun verdictKeepsFiltersInContext() {
        val hit = FilterEngine.verdict(
            listOf(FilteredStatus(filter = filter(context = listOf("home", "public")))),
            context = "home",
            nowMs = 0L,
        )
        assertEquals("f1", hit?.filterId)
    }

    @Test
    fun matchSkipsFiltersOutsideContext() {
        val hit = FilterEngine.match(
            listOf(filter(context = listOf("public"), keywords = listOf(keyword("spam")))),
            text("buy spam now"),
            context = "home",
            nowMs = 0L,
        )
        assertNull(hit)
    }

    @Test
    fun matchHitsFiltersInContext() {
        val hit = FilterEngine.match(
            listOf(filter(context = listOf("home"), keywords = listOf(keyword("spam")))),
            text("buy spam now"),
            context = "home",
            nowMs = 0L,
        )
        assertEquals("f1", hit?.filterId)
        assertEquals(listOf("spam"), hit?.matchedKeywords)
    }

    // ---- expiry boundary ----

    @Test
    fun expiredBeforeCutoffIsDropped() {
        val hit = FilterEngine.match(
            listOf(filter(expiresAt = "2026-01-01T00:00:00Z", keywords = listOf(keyword("spam")))),
            text("spam"),
            context = "home",
            nowMs = 1767225601000L, // 1ms after the expiry instant
        )
        assertNull(hit)
    }

    @Test
    fun notYetExpiredStillMatches() {
        val hit = FilterEngine.match(
            listOf(filter(expiresAt = "2026-01-01T00:00:00Z", keywords = listOf(keyword("spam")))),
            text("spam"),
            context = "home",
            nowMs = 1767225599000L, // 1s before the expiry instant
        )
        assertEquals("f1", hit?.filterId)
    }

    @Test
    fun unparseableExpiryCountsAsActive() {
        val hit = FilterEngine.match(
            listOf(filter(expiresAt = "not-a-date", keywords = listOf(keyword("spam")))),
            text("spam"),
            context = "home",
            nowMs = Long.MAX_VALUE,
        )
        assertEquals("f1", hit?.filterId)
    }

    // ---- literal matching across fields ----

    @Test
    fun matchesBodyText() {
        val hit = FilterEngine.match(
            listOf(filter(keywords = listOf(keyword("moss")))),
            text("the moss crept slowly"),
            context = "home",
            nowMs = 0L,
        )
        assertEquals(listOf("moss"), hit?.matchedKeywords)
    }

    @Test
    fun matchesSpoilerText() {
        val hit = FilterEngine.match(
            listOf(filter(keywords = listOf(keyword("spiders")))),
            text("nice day", spoiler = "warning: spiders ahead"),
            context = "home",
            nowMs = 0L,
        )
        assertEquals(listOf("spiders"), hit?.matchedKeywords)
    }

    @Test
    fun matchesPollOption() {
        val hit = FilterEngine.match(
            listOf(filter(keywords = listOf(keyword("parsnips")))),
            text("vote now", polls = listOf("carrots", "parsnips")),
            context = "home",
            nowMs = 0L,
        )
        assertEquals(listOf("parsnips"), hit?.matchedKeywords)
    }

    @Test
    fun matchesMediaDescription() {
        val hit = FilterEngine.match(
            listOf(filter(keywords = listOf(keyword("eyestrain")))),
            text("look at this", media = listOf("bright eyestrain colours")),
            context = "home",
            nowMs = 0L,
        )
        assertEquals(listOf("eyestrain"), hit?.matchedKeywords)
    }

    @Test
    fun noMatchWhenKeywordAbsent() {
        val hit = FilterEngine.match(
            listOf(filter(keywords = listOf(keyword("moss")))),
            text("clean lino"),
            context = "home",
            nowMs = 0L,
        )
        assertNull(hit)
    }

    // ---- case insensitivity ----

    @Test
    fun matchingIsCaseInsensitive() {
        val hit = FilterEngine.match(
            listOf(filter(keywords = listOf(keyword("MOSS")))),
            text("The Moss Crept"),
            context = "home",
            nowMs = 0L,
        )
        assertEquals(listOf("MOSS"), hit?.matchedKeywords)
    }

    // ---- whole word boundary ----

    @Test
    fun wholeWordAcceptsStandaloneWord() {
        val hit = FilterEngine.match(
            listOf(filter(keywords = listOf(keyword("cat", wholeWord = true)))),
            text("my cat sleeps"),
            context = "home",
            nowMs = 0L,
        )
        assertEquals("f1", hit?.filterId)
    }

    @Test
    fun wholeWordAcceptsPunctuationBoundaries() {
        val hit = FilterEngine.match(
            listOf(filter(keywords = listOf(keyword("cat", wholeWord = true)))),
            text("(cat), dog;"),
            context = "home",
            nowMs = 0L,
        )
        assertEquals("f1", hit?.filterId)
    }

    @Test
    fun wholeWordAcceptsStringEdges() {
        val hit = FilterEngine.match(
            listOf(filter(keywords = listOf(keyword("cat", wholeWord = true)))),
            text("cat"),
            context = "home",
            nowMs = 0L,
        )
        assertEquals("f1", hit?.filterId)
    }

    @Test
    fun wholeWordRejectsInsideLongerWord() {
        val hit = FilterEngine.match(
            listOf(filter(keywords = listOf(keyword("cat", wholeWord = true)))),
            text("concatenate these strings"),
            context = "home",
            nowMs = 0L,
        )
        assertNull(hit)
    }

    @Test
    fun wholeWordRejectsDigitNeighbours() {
        val hit = FilterEngine.match(
            listOf(filter(keywords = listOf(keyword("v2", wholeWord = true)))),
            text("release v2024 notes"),
            context = "home",
            nowMs = 0L,
        )
        assertNull(hit)
    }

    @Test
    fun literalMatchIgnoresWordBoundaries() {
        val hit = FilterEngine.match(
            listOf(filter(keywords = listOf(keyword("cat")))),
            text("concatenate"),
            context = "home",
            nowMs = 0L,
        )
        assertEquals("f1", hit?.filterId)
    }

    // ---- severity ordering ----

    @Test
    fun mostSevereActionWinsAmongMatches() {
        val filters = listOf(
            filter(id = "warn1", action = "warn", keywords = listOf(keyword("spam"))),
            filter(id = "blur1", action = "blur", keywords = listOf(keyword("spam"))),
            filter(id = "hide1", action = "hide", keywords = listOf(keyword("spam"))),
        )
        val hit = FilterEngine.match(filters, text("spam"), context = "home", nowMs = 0L)
        assertEquals("hide1", hit?.filterId)
        assertEquals("hide", hit?.action)
    }

    @Test
    fun tiesGoToTheFirstFilter() {
        val filters = listOf(
            filter(id = "first", action = "hide", keywords = listOf(keyword("spam"))),
            filter(id = "second", action = "hide", keywords = listOf(keyword("spam"))),
        )
        val hit = FilterEngine.match(filters, text("spam"), context = "home", nowMs = 0L)
        assertEquals("first", hit?.filterId)
    }

    @Test
    fun severityOrdersTheThreeActions() {
        assertTrue(FilterEngine.severity("warn") < FilterEngine.severity("blur"))
        assertTrue(FilterEngine.severity("blur") < FilterEngine.severity("hide"))
    }

    // ---- verdict over server-provided results ----

    @Test
    fun verdictPicksContextAppropriateAction() {
        val filtered = listOf(
            FilteredStatus(
                filter = filter(id = "homeHide", context = listOf("home"), action = "hide"),
                keyword_matches = listOf("spam"),
            ),
            FilteredStatus(
                filter = filter(id = "publicWarn", context = listOf("public"), action = "warn"),
                keyword_matches = listOf("spam"),
            ),
        )
        val homeHit = FilterEngine.verdict(filtered, context = "home", nowMs = 0L)
        assertEquals("homeHide", homeHit?.filterId)
        val publicHit = FilterEngine.verdict(filtered, context = "public", nowMs = 0L)
        assertEquals("publicWarn", publicHit?.filterId)
        assertNull(FilterEngine.verdict(filtered, context = "notifications", nowMs = 0L))
    }

    @Test
    fun verdictSkipsExpiredAndEntriesWithoutFilters() {
        val filtered = listOf(
            FilteredStatus(filter = null),
            FilteredStatus(
                filter = filter(id = "old", expiresAt = "2020-01-01T00:00:00Z", action = "hide"),
            ),
            FilteredStatus(
                filter = filter(id = "live", expiresAt = "2999-01-01T00:00:00Z", action = "blur"),
            ),
        )
        val hit = FilterEngine.verdict(filtered, context = "home", nowMs = 1_600_000_000_000L)
        assertEquals("live", hit?.filterId)
        assertEquals("blur", hit?.action)
    }

    @Test
    fun verdictPrefersHideOverWarnAcrossEntries() {
        val filtered = listOf(
            FilteredStatus(filter = filter(id = "w", action = "warn")),
            FilteredStatus(filter = filter(id = "h", action = "hide")),
        )
        assertEquals("h", FilterEngine.verdict(filtered, context = "home", nowMs = 0L)?.filterId)
    }

    // ---- filter without keywords never matches client-side ----

    @Test
    fun matchWithNoKeywordsNeverHits() {
        val hit = FilterEngine.match(
            listOf(filter(keywords = emptyList())),
            text("anything at all"),
            context = "home",
            nowMs = 0L,
        )
        assertNull(hit)
    }

    @Test
    fun matchReportsEveryMatchedKeyword() {
        val hit = FilterEngine.match(
            listOf(
                filter(
                    keywords = listOf(
                        keyword("moss"),
                        keyword("lichen", wholeWord = true),
                        keyword("unrelated"),
                    ),
                ),
            ),
            text("moss and lichen on the wall"),
            context = "home",
            nowMs = 0L,
        )
        assertEquals(2, hit?.matchedKeywords?.size)
        assertTrue(hit?.matchedKeywords?.contains("moss") == true)
        assertTrue(hit?.matchedKeywords?.contains("lichen") == true)
        assertFalse(hit?.matchedKeywords?.contains("unrelated") == true)
    }
}
