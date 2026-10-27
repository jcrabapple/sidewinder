package net.snakepit.sidewinder.core.compose

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The new-post draft persists the sensitive toggle, language, and thread progress. */
class DraftSensitiveTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `a sensitive draft round-trips through JSON`() {
        val draft = ComposeDraft(
            text = "cat pic",
            markSensitive = true,
            language = "cy",
            visibility = "unlisted",
        )
        val restored = json.decodeFromString(ComposeDraft.serializer(), json.encodeToString(ComposeDraft.serializer(), draft))
        assertTrue(restored.markSensitive)
        assertEquals("cy", restored.language)
        assertEquals("cat pic", restored.text)
        assertEquals("unlisted", restored.visibility)
    }

    @Test
    fun `an old draft without the sensitive field decodes as not sensitive`() {
        val restored = json.decodeFromString(ComposeDraft.serializer(), """{"text":"old","savedAt":1}""")
        assertFalse(restored.markSensitive)
        assertEquals("", restored.language)
        assertTrue(restored.chain.isEmpty())
        assertEquals(0, restored.postedCount)
        assertEquals("", restored.chainParentId)
    }

    @Test
    fun `a partially posted thread round-trips its progress`() {
        val draft = ComposeDraft(
            text = "root",
            rootKey = "root-key",
            chain = listOf(DraftChainSegment("key-2", "second"), DraftChainSegment("key-3", "third")),
            postedCount = 2,
            chainParentId = "status-2",
        )
        val restored = json.decodeFromString(ComposeDraft.serializer(), json.encodeToString(ComposeDraft.serializer(), draft))
        assertEquals("root-key", restored.rootKey)
        assertEquals(listOf("key-2", "key-3"), restored.chain.map { it.key })
        assertEquals(listOf("second", "third"), restored.chain.map { it.text })
        assertEquals(2, restored.postedCount)
        assertEquals("status-2", restored.chainParentId)
    }
}
