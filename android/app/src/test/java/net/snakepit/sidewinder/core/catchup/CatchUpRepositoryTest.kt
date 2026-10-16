package net.snakepit.sidewinder.core.catchup

import java.time.Instant
import kotlinx.coroutines.runBlocking
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the digest walk, especially the unparseable
 * `created_at` rule: a bad stamp must be skipped, not treated as
 * history's beginning (which ended the whole digest).
 */
class CatchUpRepositoryTest {

    private fun account(id: String) = Account(id = id, username = id, acct = id)

    private fun status(id: String, createdAt: String) = Status(
        id = id,
        uri = "https://example.test/users/x/statuses/$id",
        created_at = createdAt,
        account = account(id),
    )

    private val cutoff = Instant.parse("2026-01-01T12:00:00Z")

    @Test
    fun skipsUnparseableStampInsteadOfEndingTheDigest() = runBlocking {
        val page = Page(
            items = listOf(
                status("1", "2026-01-01T12:30:00Z"),
                status("bad", "not-a-timestamp"),
                status("2", "2026-01-01T12:10:00Z"),
                status("old", "2026-01-01T11:00:00Z"),
            ),
            nextMaxId = null,
        )
        val collected = CatchUpRepository.collectPages(cutoff) { page }
        // "bad" is skipped; the walk continues to "2" and stops at "old".
        // The pre-fix behavior returned EPOCH for "bad", hit the cutoff
        // immediately and dropped "2" from the digest.
        assertEquals(listOf("2", "1"), collected.map { it.id })
    }

    @Test
    fun unparseableStampOnALaterPageDoesNotEndTheWalk() = runBlocking {
        val pages = listOf(
            Page(items = listOf(status("1", "2026-01-01T12:30:00Z")), nextMaxId = "p2"),
            Page(
                items = listOf(
                    status("bad", "garbage"),
                    status("2", "2026-01-01T12:15:00Z"),
                ),
                nextMaxId = null,
            ),
        )
        var calls = 0
        val collected = CatchUpRepository.collectPages(cutoff) {
            calls++
            pages[calls - 1]
        }
        // Pre-fix, the bad stamp at the top of page 2 ended the digest
        // before "2" was read.
        assertEquals(listOf("2", "1"), collected.map { it.id })
    }

    @Test
    fun createdAtOrNullParsesAndRejects() {
        val good = CatchUpRepository.createdAtOrNull(status("1", "2026-01-01T12:30:00Z"))
        assertEquals(Instant.parse("2026-01-01T12:30:00Z"), good)
        assertNull(CatchUpRepository.createdAtOrNull(status("1", "junk")))
    }

    @Test
    fun cutoffForSubtractsHours() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), CatchUpRepository.cutoffFor(12, now))
    }
}
