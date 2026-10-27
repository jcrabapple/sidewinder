package net.snakepit.sidewinder.core.notify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class NotifyStateTest {

    private lateinit var directory: File
    private lateinit var file: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("notify-state-test").toFile()
        file = File(directory, "notify-state.json")
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun missingFileMeansNoWatermark() = runBlocking {
        assertNull(NotifyState(file).lastNotifiedId("session"))
    }

    @Test
    fun watermarkIsMonotonicAcrossStateInstances() = runBlocking {
        val worker = NotifyState(file)
        val shell = NotifyState(file)

        worker.setLastNotifiedId("session", "100")
        shell.setLastNotifiedId("session", "150")
        worker.setLastNotifiedId("session", "120")

        assertEquals("150", shell.lastNotifiedId("session"))
    }

    @Test
    fun concurrentWritersCannotRegressOrCorruptState() = runBlocking {
        val states = listOf(NotifyState(file), NotifyState(file))

        coroutineScope {
            (0 until 100).map { value ->
                async(Dispatchers.Default) {
                    states[value % states.size].setLastNotifiedId(
                        "session",
                        value.toString().padStart(3, '0'),
                    )
                }
            }.awaitAll()
        }

        assertEquals("099", states[0].lastNotifiedId("session"))
        assertEquals(listOf("notify-state.json"), directory.listFiles()?.map(File::getName))
    }

    @Test
    fun unreadableStateFailsInsteadOfLookingMissing() = runBlocking<Unit> {
        file.mkdir()
        val state = NotifyState(file)

        assertThrows(IOException::class.java) {
            runBlocking { state.lastNotifiedId("session") }
        }
    }

    @Test
    fun corruptStateCanBeQuarantinedForTheNextWorkerRun() = runBlocking {
        file.writeText("{")
        val state = NotifyState(file)

        state.quarantineUnreadable()

        assertNull(state.lastNotifiedId("session"))
        assertEquals("{", File(directory, "notify-state.json.corrupt").readText())
    }

    @Test
    fun corruptStateFailsInsteadOfRebaselining() = runBlocking {
        file.writeText("{")
        val state = NotifyState(file)

        assertThrows(SerializationException::class.java) {
            runBlocking { state.lastNotifiedId("session") }
        }
        assertThrows(SerializationException::class.java) {
            runBlocking { state.setLastNotifiedId("session", "100") }
        }
        assertEquals("{", file.readText())
    }
}
