package net.snakepit.sidewinder.core.datastore

import kotlinx.serialization.SerializationException
import net.snakepit.sidewinder.core.model.AccountCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for the session dedupe rule: a re-login (same base AND same
 * account id) must REPLACE the stored session instead of duplicating it,
 * while the same account on a different server is kept separately.
 */
class SessionStoreDedupeTest {

    private fun stored(
        id: String,
        base: String,
        accountId: String = "a1",
    ) = SessionStore.StoredSession(
        id = id,
        base = base,
        token = "tok-$id",
        account = AccountCredentials(id = accountId, username = "alice", acct = "alice"),
        scopes = null,
    )

    @Test
    fun newSessionIsAppended() {
        val merged = mergeSessions(listOf(stored("s1", "https://a.example")), stored("s2", "https://b.example"))
        assertEquals(listOf("s1", "s2"), merged.map { it.id })
    }

    @Test
    fun sameIdReplaces() {
        val merged = mergeSessions(listOf(stored("s1", "https://a.example")), stored("s1", "https://b.example"))
        assertEquals(1, merged.size)
        assertEquals("https://b.example", merged.single().base)
    }

    @Test
    fun reLoginReplacesSameBaseSameAccount() {
        val merged = mergeSessions(
            listOf(stored("s1", "https://a.example", accountId = "acct-9")),
            stored("s2", "https://a.example", accountId = "acct-9"),
        )
        assertEquals(listOf("s2"), merged.map { it.id })
    }

    @Test
    fun sameAccountDifferentBaseIsKept() {
        val merged = mergeSessions(
            listOf(stored("s1", "https://a.example", accountId = "acct-9")),
            stored("s2", "https://b.example", accountId = "acct-9"),
        )
        assertEquals(listOf("s1", "s2"), merged.map { it.id })
    }

    @Test
    fun sameBaseDifferentAccountIsKept() {
        val merged = mergeSessions(
            listOf(stored("s1", "https://a.example", accountId = "acct-1")),
            stored("s2", "https://a.example", accountId = "acct-2"),
        )
        assertEquals(listOf("s1", "s2"), merged.map { it.id })
    }

    @Test
    fun missingSessionFileDecodesAsEmpty() {
        val decoded = decodeSessionFile(null)
        assertEquals(emptyList<SessionStore.StoredSession>(), decoded.sessions)
        assertNull(decoded.activeId)
    }

    @Test(expected = SerializationException::class)
    fun corruptSessionFileThrows() {
        decodeSessionFile("{not valid json")
    }

    @Test
    fun unknownActiveIdRetainsCurrentSession() {
        val data = SessionStore.SessionFile(
            sessions = listOf(stored("s1", "https://a.example"), stored("s2", "https://b.example")),
            activeId = "s2",
        )
        assertSame(data, activateSession(data, "missing"))
    }

    @Test
    fun knownActiveIdReplacesOnlyActiveSelection() {
        val data = SessionStore.SessionFile(
            sessions = listOf(stored("s1", "https://a.example"), stored("s2", "https://b.example")),
            activeId = "s2",
        )
        val updated = activateSession(data, "s1")
        assertEquals("s1", updated.activeId)
        assertEquals(data.sessions, updated.sessions)
    }
}
