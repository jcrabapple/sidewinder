package net.snakepit.sidewinder.core.lists

import net.snakepit.sidewinder.core.model.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for member-search filtering and load-more backoff. */
class ListMembersLogicTest {

    private fun account(id: String) = Account(id = id, username = id, acct = id)

    @Test
    fun memberIdsAreDistinct() {
        assertEquals(
            setOf("1", "2"),
            ListMembersLogic.memberIds(listOf(account("1"), account("2"), account("1"))),
        )
    }

    @Test
    fun searchResultsHideExistingMembers() {
        val results = listOf(account("1"), account("3"), account("2"))
        val filtered = ListMembersLogic.hideExistingMembers(results, setOf("2"))
        assertEquals(listOf("1", "3"), filtered.map { it.id })
    }

    @Test
    fun emptyMembershipHidesNothing() {
        val results = listOf(account("1"), account("2"))
        assertEquals(results, ListMembersLogic.hideExistingMembers(results, emptySet()))
    }

    @Test
    fun backoffStartsAtOneSecondAndDoubles() {
        assertEquals(0L, ListMembersLogic.PageRetry.delayMs(0))
        assertEquals(1_000L, ListMembersLogic.PageRetry.delayMs(1))
        assertEquals(2_000L, ListMembersLogic.PageRetry.delayMs(2))
        assertEquals(4_000L, ListMembersLogic.PageRetry.delayMs(3))
    }

    @Test
    fun backoffCapsAtThirtySeconds() {
        assertEquals(30_000L, ListMembersLogic.PageRetry.delayMs(6))
        assertEquals(30_000L, ListMembersLogic.PageRetry.delayMs(50))
        assertTrue(ListMembersLogic.PageRetry.delayMs(5) <= ListMembersLogic.PageRetry.MAX_MS)
        assertFalse(ListMembersLogic.PageRetry.delayMs(50) < ListMembersLogic.PageRetry.MAX_MS)
    }
}
