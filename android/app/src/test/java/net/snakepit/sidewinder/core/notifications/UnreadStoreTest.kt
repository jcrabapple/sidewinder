package net.snakepit.sidewinder.core.notifications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UnreadStoreTest {
    private lateinit var directory: File
    private lateinit var file: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("unread-store-test").toFile()
        file = File(directory, "unread-state.json")
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun streamedBurstsIncrementAtomicallyAndCapAtWindow() = runBlocking {
        val stores = listOf(UnreadStore(file), UnreadStore(file))

        coroutineScope {
            (0 until 100).map { index ->
                async(Dispatchers.Default) {
                    stores[index % stores.size].increment("session")
                }
            }.awaitAll()
        }

        assertEquals(UnreadStore.WINDOW, stores[0].count("session"))
    }

    @Test
    fun readMarkClearsCountAndNeverMovesBackwards() = runBlocking {
        val store = UnreadStore(file)
        repeat(3) { store.increment("session") }

        store.setSeenTop("session", "100")
        assertEquals(0, store.count("session"))
        assertEquals("100", store.seenTop("session"))

        store.setSeenTop("session", "099")
        assertEquals("100", store.seenTop("session"))

        store.increment("session")
        assertEquals(1, store.count("session"))
        assertEquals("100", store.seenTop("session"))
    }
}
