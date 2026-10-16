package net.snakepit.sidewinder.ui.dm

import net.snakepit.sidewinder.core.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the DM list reductions: [applyConversationRead] must
 * work off safe casts (only Content changes), and
 * [applyConversationDeleted] must fall back to Empty when the LAST
 * conversation is removed (previously it produced an empty Content).
 */
class DmsStateTest {

    private fun conversation(id: String, unread: Boolean = false) = Conversation(id = id, unread = unread)

    private fun content(vararg items: Conversation) =
        DmsState.Content(items.toList(), nextMaxId = null, loadingMore = false)

    @Test
    fun deletingTheLastConversationLeavesEmpty() {
        val next = applyConversationDeleted(content(conversation("c1")), "c1")
        assertEquals(DmsState.Empty, next)
    }

    @Test
    fun deletingOneOfSeveralKeepsTheRest() {
        val next = applyConversationDeleted(content(conversation("c1"), conversation("c2")), "c1")
        assertTrue(next is DmsState.Content)
        assertEquals(listOf("c2"), (next as DmsState.Content).items.map { it.id })
    }

    @Test
    fun deletingFromANonContentStateIsANoOp() {
        assertEquals(DmsState.Loading, applyConversationDeleted(DmsState.Loading, "c1"))
        assertEquals(DmsState.Empty, applyConversationDeleted(DmsState.Empty, "c1"))
    }

    @Test
    fun markReadClearsOnlyTheTargetConversation() {
        val next = applyConversationRead(
            content(conversation("c1", unread = true), conversation("c2", unread = true)),
            "c1",
        )
        next as DmsState.Content
        assertEquals(false to true, next.items.map { it.unread }.let { it[0] to it[1] })
    }

    @Test
    fun markReadOnANonContentStateIsANoOp() {
        assertEquals(DmsState.Loading, applyConversationRead(DmsState.Loading, "c1"))
        assertEquals(DmsState.Empty, applyConversationRead(DmsState.Empty, "c1"))
    }
}
