package net.snakepit.sidewinder.core.notifications

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.snakepit.sidewinder.core.model.Notification
import net.snakepit.sidewinder.core.network.Api
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Per-session notifications read-mark and cached badge count. Port of the
 * web client's notifications-side state (`state/notifications.ts`):
 *
 * - The read mark is the newest notification id the account has SEEN
 *   (ULID ids compare chronologically as strings).
 * - A session with no mark gets baselined to the newest notification on
 *   first recount, so a fresh device starts at zero unread.
 * - The count covers the newest [WINDOW] notifications only; a count of
 *   [WINDOW] means "that many or more" (the UI shows "40+").
 *
 * Server-marker sync (web `state/markers.ts`) is not ported yet: the mark
 * is local to this device, stored in `filesDir/sidewinder/unread-state.json`.
 */
class UnreadStore(context: Context) {

    @Serializable
    private data class Stored(
        val seenTop: Map<String, String> = emptyMap(),
        val counts: Map<String, Int> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val file: File = File(context.filesDir, "sidewinder/unread-state.json")

    private fun read(): Stored =
        if (!file.exists()) Stored()
        else runCatching { json.decodeFromString(Stored.serializer(), file.readText()) }.getOrDefault(Stored())

    private fun write(stored: Stored) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(Stored.serializer(), stored))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun seenTop(sessionId: String): String? = read().seenTop[sessionId]

    fun setSeenTop(sessionId: String, id: String) {
        val stored = read()
        write(stored.copy(seenTop = stored.seenTop + (sessionId to id)))
    }

    fun count(sessionId: String): Int = read().counts[sessionId] ?: 0

    fun setCount(sessionId: String, count: Int) {
        val stored = read()
        write(stored.copy(counts = stored.counts + (sessionId to count)))
    }

    companion object {
        /** Badge window; at or above this the UI shows "40+". */
        const val WINDOW = 40

        private val PAGE_SERIALIZER = ListSerializer(Notification.serializer())

        /**
         * Recount unread notifications for [sessionId] over the newest
         * [WINDOW] rows and persist the result. Mirrors the web client:
         * no mark yet means baseline-to-newest and zero unread.
         *
         * @return the unread count (also persisted for cold-start badges).
         */
        suspend fun recount(api: Api, sessionId: String, store: UnreadStore): Int {
            val page = api.page(
                path = "/api/v1/notifications",
                serializer = PAGE_SERIALIZER,
                query = mapOf("limit" to listOf(WINDOW.toString())),
            )
            val mark = store.seenTop(sessionId)
            if (mark == null) {
                page.items.firstOrNull()?.let { store.setSeenTop(sessionId, it.id) }
                store.setCount(sessionId, 0)
                return 0
            }
            val unread = page.items.count { it.id > mark }
            store.setCount(sessionId, unread)
            return unread
        }
    }
}
