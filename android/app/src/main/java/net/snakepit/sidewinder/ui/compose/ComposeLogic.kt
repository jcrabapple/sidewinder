package net.snakepit.sidewinder.ui.compose

import android.net.Uri
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.snakepit.sidewinder.core.model.MediaAttachment

/**
 * Pure helpers + saved-state plumbing for the composer, split out of
 * ComposeScreen so the fingerprint and edit-payload logic can be unit
 * tested on the JVM.
 */

/** Per-item media state: uploading, ready (server attachment), or error. */
internal data class DraftMedia(
    val key: String,
    val uri: Uri? = null,
    val filename: String = "",
    val attachment: MediaAttachment? = null,
    val uploading: Boolean = false,
    val error: String? = null,
)

@Serializable
private data class SavedMedia(
    val key: String,
    val uri: String? = null,
    val filename: String = "",
    val attachment: MediaAttachment? = null,
    val uploading: Boolean = false,
    val error: String? = null,
)

private val mediaJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Keeps the attachment list across activity recreation for every compose
 * mode. In-flight uploads cannot survive recreation, so an item that was
 * still uploading comes back as a retryable error instead of silently
 * vanishing.
 */
internal val MediaStateSaver: Saver<SnapshotStateList<DraftMedia>, String> = Saver(
    save = { list ->
        mediaJson.encodeToString(
            ListSerializer(SavedMedia.serializer()),
            list.map { SavedMedia(it.key, it.uri?.toString(), it.filename, it.attachment, it.uploading, it.error) },
        )
    },
    restore = { text ->
        mediaJson.decodeFromString(ListSerializer(SavedMedia.serializer()), text)
            .map { saved ->
                DraftMedia(
                    key = saved.key,
                    uri = saved.uri?.let { Uri.parse(it) },
                    filename = saved.filename,
                    attachment = saved.attachment,
                    error = if (saved.uploading && saved.attachment == null && saved.uri != null) {
                        "Upload interrupted. Tap to retry."
                    } else {
                        saved.error
                    },
                )
            }
            .toMutableStateList()
    },
)

/**
 * Dirty-check signature for the composer: everything whose change must
 * trigger the discard prompt. Media entries are "id:alt" descriptors.
 */
internal fun composeFingerprint(
    text: String,
    cwOn: Boolean,
    spoiler: String,
    markSensitive: Boolean,
    media: List<String>,
    pollOn: Boolean,
    pollOptions: List<String>,
    pollMultiple: Boolean,
    pollExpiry: Long,
    visibility: String,
    scheduleOn: Boolean,
    scheduleAtMs: Long,
): String = listOf(
    text,
    cwOn.toString(),
    spoiler,
    markSensitive.toString(),
    media.joinToString("|"),
    pollOn.toString(),
    pollOptions.joinToString("|"),
    pollMultiple.toString(),
    pollExpiry.toString(),
    visibility,
    scheduleOn.toString(),
    scheduleAtMs.toString(),
).joinToString("\u0000")

/**
 * The attachment ids an edit keeps. Attachments the user removed in this
 * session ([removedIds]) are dropped; an attachment that is missing from
 * the live list for any OTHER reason (its upload died, a restore glitch)
 * is kept by falling back to the original list, so an edit can never
 * silently delete attachments.
 */
internal fun editMediaIds(
    currentIds: List<String>,
    originalIds: List<String>,
    removedIds: Set<String>,
): List<String> {
    val kept = currentIds.filter { it.isNotBlank() }.distinct()
    val keptSet = kept.toSet()
    val retained = originalIds.filter { it !in keptSet && it !in removedIds }
    return kept + retained
}
