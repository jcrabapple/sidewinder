package net.snakepit.sidewinder.core.notify

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-session background-poll state at
 * `filesDir/sidewinder/notify-state.json`: the last notification id the
 * worker has already delivered, keyed by session id. Notification ids
 * compare chronologically as plain strings (ULID-shaped), so the highest
 * id is simply the lexicographic maximum. Writes are atomic:
 * temp file + rename, same pattern as SessionStore.
 */
class NotifyState(context: Context) {

    @Serializable
    private data class FileShape(
        val lastIds: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.applicationContext.filesDir, "sidewinder/notify-state.json")

    /** The last delivered notification id for [sessionId], or null on first run. */
    suspend fun lastNotifiedId(sessionId: String): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(FileShape.serializer(), file.readText()).lastIds[sessionId]
        }.getOrNull()
    }

    /** Persists [notificationId] as the last delivered id for [sessionId]. */
    suspend fun setLastNotifiedId(sessionId: String, notificationId: String) =
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val current = runCatching {
                if (file.exists()) {
                    json.decodeFromString(FileShape.serializer(), file.readText())
                } else {
                    FileShape()
                }
            }.getOrDefault(FileShape())

            val next = FileShape(
                lastIds = current.lastIds + (sessionId to notificationId),
            )
            val tmp = File(file.parentFile, file.name + ".tmp")
            runCatching {
                tmp.writeText(json.encodeToString(FileShape.serializer(), next))
                if (!tmp.renameTo(file)) {
                    file.writeText(tmp.readText())
                    tmp.delete()
                }
            }.onFailure { tmp.delete() }
        }
}
