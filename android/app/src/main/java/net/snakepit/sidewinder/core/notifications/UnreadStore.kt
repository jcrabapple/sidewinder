package net.snakepit.sidewinder.core.notifications

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
class UnreadStore internal constructor(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "sidewinder/unread-state.json"))

    @Serializable
    private data class Stored(
        val seenTop: Map<String, String> = emptyMap(),
        val counts: Map<String, Int> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }
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

    suspend fun seenTop(sessionId: String): String? = withStore { read().seenTop[sessionId] }

    suspend fun setSeenTop(sessionId: String, id: String) {
        withStore {
            val stored = read()
            val previous = stored.seenTop[sessionId]
            if (previous == null || id >= previous) {
                // Seeing the newest row clears both the visible badge and
                // its persisted value. An older load cannot move the mark
                // backwards or resurrect those rows later.
                write(
                    stored.copy(
                        seenTop = stored.seenTop + (sessionId to id),
                        counts = stored.counts + (sessionId to 0),
                    ),
                )
            }
        }
    }

    suspend fun count(sessionId: String): Int = withStore { read().counts[sessionId] ?: 0 }

    suspend fun setCount(sessionId: String, count: Int) {
        withStore {
            val stored = read()
            write(stored.copy(counts = stored.counts + (sessionId to count)))
        }
    }

    /** Atomically increments a streamed-notification badge and returns its new value. */
    suspend fun increment(sessionId: String): Int = withStore {
        val stored = read()
        val next = ((stored.counts[sessionId] ?: 0) + 1).coerceAtMost(WINDOW)
        write(stored.copy(counts = stored.counts + (sessionId to next)))
        next
    }

    companion object {
        /** Badge window; at or above this the UI shows "40+". */
        const val WINDOW = 40

        private val PAGE_SERIALIZER = ListSerializer(Notification.serializer())

        /**
         * Process-wide serialization for every read-modify-write: three
         * UnreadStore instances exist (MainActivity, notifications screen,
         * NotifyWorker) and the backing file is shared.
         */
        private val mutex = Mutex()

        /** Run [block] on IO, serialized against the other instances. */
        private suspend fun <T> withStore(block: () -> T): T = mutex.withLock {
            withContext(Dispatchers.IO) { block() }
        }

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
