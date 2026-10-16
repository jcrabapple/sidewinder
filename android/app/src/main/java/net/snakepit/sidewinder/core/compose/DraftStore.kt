package net.snakepit.sidewinder.core.compose

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.snakepit.sidewinder.core.model.MediaAttachment
import java.io.File

/**
 * Compose draft recovery. Port of web `src/state/draft.ts`: the in-progress
 * new-post draft persists per session id on disk (debounced by the caller on
 * every change), so a crash or accidental dismissal never loses a half-written
 * post.
 *
 * Scope: new-post drafts only — reply drafts are bound to their parent status
 * and follow the reply flow instead. Cleared when the post goes out or the
 * user empties the composer. Only already-uploaded media attachments (their
 * server records) are persisted; raw picked files never are.
 */
@Serializable
data class DraftMediaRef(
    val attachment: MediaAttachment,
)

@Serializable
data class ComposeDraft(
    val text: String = "",
    val cw: Boolean = false,
    val spoiler: String = "",
    val visibility: String = "public",
    val media: List<DraftMediaRef> = emptyList(),
    val pollOn: Boolean = false,
    val pollOptions: List<String> = emptyList(),
    val pollMultiple: Boolean = false,
    val pollExpiry: Long = 86400L,
    /** Schedule toggle for the draft post (new posts only). */
    val scheduleOn: Boolean = false,
    /** Epoch millis of the chosen publish time; 0 = not picked yet. */
    val scheduledAtMs: Long = 0,
    val savedAt: Long = 0,
)

class DraftStore(context: Context) {

    private val dir: File = File(context.filesDir, "sidewinder/drafts")

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun fileFor(sessionId: String): File {
        require(sessionId.isNotBlank()) { "blank session id" }
        return File(dir.apply { mkdirs() }, "$sessionId.json")
    }

    suspend fun save(sessionId: String, draft: ComposeDraft): Unit = withContext(Dispatchers.IO) {
        val target = fileFor(sessionId)
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.writeText(json.encodeToString(ComposeDraft.serializer(), draft))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (_: Exception) {
            // Blocked storage / quota: draft recovery silently unavailable.
            tmp.delete()
        }
    }

    suspend fun load(sessionId: String): ComposeDraft? = withContext(Dispatchers.IO) {
        val target = fileFor(sessionId)
        if (!target.exists()) return@withContext null
        try {
            val draft = json.decodeFromString(ComposeDraft.serializer(), target.readText())
            // Drop stale drafts (older than 48h) whose upload URLs may have
            // been cleaned up server-side.
            if (draft.savedAt > 0 && System.currentTimeMillis() - draft.savedAt > 48 * 3_600_000L) {
                target.delete()
                null
            } else {
                draft
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun clear(sessionId: String): Unit = withContext(Dispatchers.IO) {
        try {
            fileFor(sessionId).delete()
        } catch (_: Exception) {
            // Nothing to do.
        }
    }
}
