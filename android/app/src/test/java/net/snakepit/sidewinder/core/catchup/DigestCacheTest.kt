package net.snakepit.sidewinder.core.catchup

import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Status
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for the digest cache that survives rotation. */
class DigestCacheTest {

    @After
    fun clear() {
        DigestCache.invalidate("a")
        DigestCache.invalidate("b")
    }

    private fun status(id: String) = Status(
        id = id,
        uri = "u$id",
        created_at = "2026-01-01T12:00:00Z",
        account = Account(id = id, username = id, acct = id),
    )

    @Test
    fun roundTripBySessionAndHours() {
        DigestCache.put("a", 24, listOf(status("1")))
        assertEquals(listOf("1"), DigestCache.get("a", 24)?.map { it.id })
    }

    @Test
    fun rangesAndSessionsAreSeparateKeys() {
        DigestCache.put("a", 24, listOf(status("1")))
        assertNull(DigestCache.get("a", 6))
        assertNull(DigestCache.get("b", 24))
    }

    @Test
    fun invalidateDropsEveryRangeOfOneSession() {
        DigestCache.put("a", 1, listOf(status("1")))
        DigestCache.put("a", 24, listOf(status("2")))
        DigestCache.put("b", 24, listOf(status("3")))
        DigestCache.invalidate("a")
        assertNull(DigestCache.get("a", 1))
        assertNull(DigestCache.get("a", 24))
        assertEquals(listOf("3"), DigestCache.get("b", 24)?.map { it.id })
    }
}
