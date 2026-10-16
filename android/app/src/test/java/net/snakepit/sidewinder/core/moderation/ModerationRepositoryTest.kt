package net.snakepit.sidewinder.core.moderation

import net.snakepit.sidewinder.core.moderation.ModerationRepository.AdminAccount
import net.snakepit.sidewinder.core.moderation.ModerationRepository.AdminDomainBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic behind the moderation screen: the admin account search param
 * mapping (the "Local only" chip), pending-signup filtering + page budget,
 * domain-block page merging, suspend feedback copy, and the list update
 * after a suspend lands.
 */
class ModerationRepositoryTest {

    // ---- E2-1 (K70): "Local only" chip must send local=true ----

    @Test
    fun `local mode sends local param`() {
        val q = ModerationRepository.searchAdminQuery(local = true)
        assertEquals(listOf("true"), q["local"])
        assertNull(q["remote"])
        assertNull(q["suspended"])
        assertEquals(listOf("40"), q["limit"])
    }

    @Test
    fun `remote and suspended modes keep their params and send no local param`() {
        assertEquals(listOf("true"), ModerationRepository.searchAdminQuery(remote = true)["remote"])
        assertEquals(listOf("true"), ModerationRepository.searchAdminQuery(suspended = true)["suspended"])
        assertNull(ModerationRepository.searchAdminQuery(remote = true)["local"])
        assertNull(ModerationRepository.searchAdminQuery(suspended = true)["local"])
        assertNull(ModerationRepository.searchAdminQuery()["local"])
    }

    @Test
    fun `username search trims and drops blank`() {
        assertEquals(listOf("alice"), ModerationRepository.searchAdminQuery(username = "  alice ")["username"])
        assertNull(ModerationRepository.searchAdminQuery(username = "   ")["username"])
        assertNull(ModerationRepository.searchAdminQuery(username = null)["username"])
    }

    // ---- E2-3 (K72): pending signup filter + page budget ----

    private fun account(
        username: String,
        domain: String? = null,
        approved: Boolean = false,
        confirmed: Boolean = false,
    ) = AdminAccount(
        id = username,
        username = username,
        domain = domain,
        approved = approved,
        confirmed = confirmed,
    )

    @Test
    fun `pending signup keeps local unapproved unconfirmed rows`() {
        val a = account("alice")
        assertTrue(ModerationRepository.isPendingSignup(a, "gts.example"))
    }

    @Test
    fun `pending signup filter drops remote approved confirmed and instance rows`() {
        val host = "gts.example"
        assertTrue(!ModerationRepository.isPendingSignup(account("bob", domain = "remote.example"), host))
        assertTrue(!ModerationRepository.isPendingSignup(account("carol", approved = true), host))
        assertTrue(!ModerationRepository.isPendingSignup(account("dave", confirmed = true), host))
        // GTS's internal/instance account carries the host as its username.
        assertTrue(!ModerationRepository.isPendingSignup(account("GTS.Example"), host))
    }

    @Test
    fun `page budget stops at the cap even with more pages`() {
        assertEquals("cursor-2", ModerationRepository.pageBudget(pagesFetched = 1, nextMaxId = "cursor-2"))
        assertEquals("cursor-10", ModerationRepository.pageBudget(pagesFetched = 9, nextMaxId = "cursor-10"))
        // Cap reached: no further page even though the cursor exists.
        assertNull(ModerationRepository.pageBudget(pagesFetched = 10, nextMaxId = "cursor-11"))
        assertNull(ModerationRepository.pageBudget(pagesFetched = 2, nextMaxId = null))
    }

    // ---- E2-2 (K71): domain block pages merge, deduped by id ----

    private fun block(id: String, domain: String) = AdminDomainBlock(id = id, domain = domain)

    @Test
    fun `mergeDomainBlocks appends the next page in order`() {
        val merged = ModerationRepository.mergeDomainBlocks(
            listOf(block("1", "a.example"), block("2", "b.example")),
            listOf(block("3", "c.example")),
        )
        assertEquals(listOf("1", "2", "3"), merged.map { it.id })
    }

    @Test
    fun `mergeDomainBlocks drops ids already present`() {
        val merged = ModerationRepository.mergeDomainBlocks(
            listOf(block("1", "a.example")),
            listOf(block("1", "a.example"), block("2", "b.example")),
        )
        assertEquals(listOf("1", "2"), merged.map { it.id })
    }

    // ---- E2-4 (K73): suspend succeeded, resolve failed ----

    @Test
    fun `suspend resolve notice names both outcomes and the resolve-only retry`() {
        val notice = ModerationRepository.suspendResolveNotice(
            targetAcct = "spammer",
            suspendSucceeded = true,
            resolveError = "the server returned error 500.",
        )
        assertTrue(notice!!.contains("@spammer"))
        assertTrue(notice.contains("suspended"))
        assertTrue(notice.contains("could not be resolved"))
        assertTrue(notice.contains("resolve"))
    }

    @Test
    fun `suspend resolve notice is null when the suspend did not happen`() {
        assertNull(
            ModerationRepository.suspendResolveNotice(
                targetAcct = "spammer",
                suspendSucceeded = false,
                resolveError = "the server returned error 500.",
            ),
        )
    }

    // ---- E2-5 (K74): list update after a suspend lands ----

    @Test
    fun `suspend in the suspended filter keeps the card marked suspended`() {
        val items = listOf(account("alice"), account("bob"))
        val updated = account("alice", approved = true).copy(suspended = true)
        val next = ModerationRepository.updateSuspendedList(items, updated, keepSuspended = true)
        assertEquals(2, next.size)
        assertTrue(next.first { it.id == "alice" }.suspended)
    }

    @Test
    fun `suspend outside the suspended filter removes the card`() {
        val items = listOf(account("alice"), account("bob"))
        val updated = account("alice").copy(suspended = true)
        val next = ModerationRepository.updateSuspendedList(items, updated, keepSuspended = false)
        assertEquals(listOf("bob"), next.map { it.id })
    }
}
