package net.snakepit.sidewinder.core.notify

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

/**
 * Per-session background-poll state at
 * `filesDir/sidewinder/notify-state.json`: the last notification id the
 * worker has already delivered, keyed by session id. Notification ids
 * compare chronologically as plain strings (ULID-shaped), so the highest
 * id is simply the lexicographic maximum. Writes use a uniquely named temp
 * file and an atomic move; only a missing file represents empty state.
 */
class NotifyState internal constructor(stateFile: File) {

    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "sidewinder/notify-state.json"),
    )

    @Serializable
    private data class FileShape(
        val lastIds: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val file = stateFile.absoluteFile

    private fun read(): FileShape {
        val text = try {
            Files.newBufferedReader(file.toPath()).use { it.readText() }
        } catch (_: NoSuchFileException) {
            return FileShape()
        }
        return json.decodeFromString(FileShape.serializer(), text)
    }

    private fun write(data: FileShape) {
        val directory = file.parentFile ?: throw IllegalStateException("Notification state has no parent directory")
        directory.mkdirs()
        val tmp = File.createTempFile("${file.name}.", ".tmp", directory)
        try {
            tmp.writeText(json.encodeToString(FileShape.serializer(), data))
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            tmp.delete()
        }
    }

    /** The last delivered notification id for [sessionId], or null on first run. */
    suspend fun lastNotifiedId(sessionId: String): String? = mutex.withLock {
        withContext(Dispatchers.IO) { read().lastIds[sessionId] }
    }

    /**
     * Advances the last delivered id for [sessionId]. An in-app read can win a
     * race with a background poll, but neither writer can move the ULID back.
     */
    suspend fun setLastNotifiedId(sessionId: String, notificationId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = read()
            val previous = current.lastIds[sessionId]
            if (previous != null && previous >= notificationId) return@withContext

            write(
                FileShape(
                    lastIds = current.lastIds + (sessionId to notificationId),
                ),
            )
        }
    }

    /** Preserve an unreadable file for diagnosis and let the next worker run rebuild state. */
    suspend fun quarantineUnreadable() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext
            val quarantine = File(file.parentFile, file.name + ".corrupt")
            try {
                Files.move(
                    file.toPath(),
                    quarantine.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(file.toPath(), quarantine.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private companion object {
        /** All instances share one lock, including shell and WorkManager callers. */
        val mutex = Mutex()
    }
}
