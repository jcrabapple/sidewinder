package net.snakepit.sidewinder.ui.search

import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for K46 (load-more failure keeps the live state) and
 * G3-6 (results deduped by id on append, so a LazyColumn keyed by id
 * never crashes on duplicates).
 */
class SearchMergeTest {

    private val author = Account(id = "a1", username = "me", acct = "me")

    private fun status(id: String, favourited: Boolean = false) =
        Status(
            id = id,
            uri = "https://x.example/s/$id",
            created_at = "2026-10-16T12:00:00Z",
            account = author,
            content = "post $id",
            favourited = favourited,
        )

    @Test
    fun `failed load-more keeps the live state, not the captured snapshot`() {
        val captured = StatusResults(items = listOf(status("1", favourited = false)), nextOffset = 40, loadingMore = true)
        val live = StatusResults(items = listOf(status("1", favourited = true)), nextOffset = 40, loadingMore = true)

        val result = SearchMerge.failureState(captured, live)

        assertEquals(true, result?.items?.single()?.favourited) // live favourite survives
        assertEquals(false, result?.loadingMore) // in-flight flag cleared
    }

    @Test
    fun `failed load-more leaves a reset state cleared instead of resurrecting the snapshot`() {
        val captured = StatusResults(items = listOf(status("1")), nextOffset = 40, loadingMore = true)
        assertEquals(null, SearchMerge.failureState(captured, live = null))
    }

    @Test
    fun `append drops page items already in the base`() {
        val base = listOf(status("1"), status("2"))
        val page = listOf(status("2"), status("3"))
        assertEquals(listOf("1", "2", "3"), SearchMerge.append(base, page).map { it.id })
    }

    @Test
    fun `append drops duplicates within the page itself`() {
        val base = listOf(status("1"))
        val page = listOf(status("9"), status("9"), status("10"))
        val merged = SearchMerge.append(base, page)
        assertEquals(listOf("1", "9", "10"), merged.map { it.id })
        assertEquals(merged.size, merged.map { it.id }.distinct().size)
    }

    @Test
    fun `first-page results are deduped by id`() {
        val deduped = SearchMerge.deduped(listOf(status("1"), status("1"), status("2")))
        assertTrue(deduped.map { it.id } == listOf("1", "2"))
    }
}
