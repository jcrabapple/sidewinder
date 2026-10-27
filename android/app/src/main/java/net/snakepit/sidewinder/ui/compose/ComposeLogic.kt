package net.snakepit.sidewinder.ui.compose

import android.net.Uri
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
 * One text-only follow-up in a thread. The [key] doubles as this
 * segment's Idempotency-Key so a retry after a failure reuses the same
 * request identity instead of publishing a duplicate.
 */
internal data class ChainSegment(
    val key: String,
    val value: TextFieldValue,
)

@Serializable
private data class SavedChainSegment(
    val key: String,
    val text: String = "",
    val start: Int = 0,
    val end: Int = 0,
)

private val chainJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Keeps the thread's segments (text, caret, idempotency key) across recreation. */
internal val ChainStateSaver: Saver<SnapshotStateList<ChainSegment>, String> = Saver(
    save = { list ->
        chainJson.encodeToString(
            ListSerializer(SavedChainSegment.serializer()),
            list.map {
                SavedChainSegment(
                    key = it.key,
                    text = it.value.text,
                    start = it.value.selection.start,
                    end = it.value.selection.end,
                )
            },
        )
    },
    restore = { text ->
        chainJson.decodeFromString(ListSerializer(SavedChainSegment.serializer()), text)
            .map { saved ->
                val value = saved.text
                val start = saved.start.coerceIn(0, value.length)
                val end = saved.end.coerceIn(start, value.length)
                ChainSegment(saved.key, TextFieldValue(value, selection = TextRange(start, end)))
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
    language: String = "",
    chain: List<String> = emptyList(),
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
    language,
    chain.joinToString("|"),
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

// ---- Character counting ---------------------------------------------------

/**
 * Characters a URL costs against the instance limit. Sidewinder currently
 * reserves 25 characters per URL, matching the GoToSocial configuration this
 * value was derived from, rather than Mastodon's usual 23. Instance probing
 * is deliberately out of scope.
 */
internal const val URL_WEIGHT = 25

/** Matches http(s) URLs; everything else counts one code point per character. */
private val URL_PATTERN = Regex("""https?://\S+""")

/**
 * Counts a status the way the server does: every URL weighs
 * [URL_WEIGHT] regardless of its raw length, and all other characters
 * count as Unicode code points.
 */
internal fun weightedLength(text: String): Int {
    var index = 0
    var total = 0
    for (match in URL_PATTERN.findAll(text)) {
        total += text.codePointCount(index, match.range.first)
        total += URL_WEIGHT
        index = match.range.last + 1
    }
    return total + text.codePointCount(index, text.length)
}

/**
 * The counter value for the composer: the field text plus, in quote mode,
 * the posted `RE:` line (its URL also weighs [URL_WEIGHT]) and, with a
 * content warning on, the warning text itself.
 */
internal fun composerUsed(
    text: String,
    spoiler: String,
    cwOn: Boolean,
    quotedUrl: String?,
): Int {
    val body = if (quotedUrl != null) quotePostText(text, quotedUrl) else text
    return weightedLength(body) + if (cwOn) weightedLength(spoiler) else 0
}

/**
 * Poll eligibility: on, at least two nonblank options, and no option over
 * [POLL_OPTION_LIMIT]. Validation stays in one place so the Post button
 * and the inline error agree.
 */
internal fun pollEligible(pollOn: Boolean, options: List<String>): Boolean =
    pollOn &&
        options.count { it.isNotBlank() } >= 2 &&
        options.none { it.length > POLL_OPTION_LIMIT }

/** A disabled poll is irrelevant; an enabled poll must be complete before posting. */
internal fun pollBlocksPosting(pollOn: Boolean, options: List<String>): Boolean =
    pollOn && !pollEligible(pollOn = true, options = options)

// ---- Thread chain ---------------------------------------------------------

/** Extra posts allowed after the root (the web composer caps a thread at 9). */
internal const val MAX_CHAIN_SEGMENTS = 8

/** Weighted character count of one segment (its own CW copy included). */
internal fun chainSegmentUsed(text: String, cwOn: Boolean, spoiler: String): Int =
    weightedLength(text) + if (cwOn) weightedLength(spoiler) else 0

/**
 * One chain segment's own limit check: nonblank and within the weighted
 * character limit, with the content warning counting once per post (every
 * thread post carries the same warning).
 */
internal fun chainSegmentValid(text: String, cwOn: Boolean, spoiler: String): Boolean =
    text.isNotBlank() && chainSegmentUsed(text, cwOn, spoiler) <= CHAR_LIMIT

/** Every segment must be publishable before the thread can be posted. */
internal fun chainValid(chain: List<String>, cwOn: Boolean, spoiler: String): Boolean =
    chain.all { chainSegmentValid(it, cwOn, spoiler) }

/** Per-segment validation message, or null when the segment is publishable. */
internal fun chainSegmentMessage(text: String, cwOn: Boolean, spoiler: String): String? {
    if (text.isBlank()) return "This post is empty. Write something or remove it."
    val used = chainSegmentUsed(text, cwOn, spoiler)
    return if (used > CHAR_LIMIT) {
        "This post is ${used - CHAR_LIMIT} characters over the $CHAR_LIMIT-character limit."
    } else {
        null
    }
}

/**
 * The chain index posting resumes at when [postedCount] posts have already
 * succeeded (the root counts as 1). 0 means "root posted, start at the
 * first segment"; a value at or past `chain.size` means the thread is done.
 */
internal fun chainResumeIndex(postedCount: Int): Int = (postedCount - 1).coerceAtLeast(0)

/** True when the segment at [index] was already published and must not be reposted. */
internal fun chainSegmentPosted(index: Int, postedCount: Int): Boolean = index < postedCount - 1

/** User-facing number of a chain segment ("Post 2" is the first follow-up). */
internal fun chainPostLabel(index: Int): String = "Post ${index + 2}"

/**
 * The `sensitive` flag sent with a status. A content warning always marks
 * the media; the eye toggle covers text-only sensitive posts too, so
 * editing one does not silently clear the original flag.
 */
internal fun submittedSensitive(
    cwOn: Boolean,
    spoiler: String,
    markSensitive: Boolean,
    hasMedia: Boolean,
    isEdit: Boolean,
): Boolean = (cwOn && spoiler.isNotBlank()) || (markSensitive && (hasMedia || isEdit))

// ---- Text insertion -------------------------------------------------------

/** Text plus the caret position after inserting a snippet. */
internal data class TextInsertion(val text: String, val cursor: Int)

/** Replaces [selectionStart]..[selectionEnd] with [snippet] and places the caret after it. */
internal fun insertAtSelection(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    snippet: String,
): TextInsertion {
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(start, text.length)
    return TextInsertion(
        text = text.substring(0, start) + snippet + text.substring(end),
        cursor = start + snippet.length,
    )
}

/** The @mention or #hashtag token being typed at [cursor], if any. */
internal data class AutocompleteToken(
    /** Index of `@` / `#`. */
    val start: Int,
    /** Caret index; the token ends here. */
    val end: Int,
    /** "accounts" for @, "hashtags" for #. */
    val type: String,
    /** Query sent to the search API (@ and # removed). */
    val query: String,
)

/**
 * Detects the word being typed at the caret. Only @ and # tokens count,
 * and a bare sigil is ignored so the picker does not open on every
 * mention of an @.
 */
internal fun autocompleteToken(text: String, cursor: Int): AutocompleteToken? {
    val end = cursor.coerceIn(0, text.length)
    if (end == 0) return null
    val start = (end - 1 downTo 0).firstOrNull { text[it].isWhitespace() }?.plus(1) ?: 0
    val token = text.substring(start, end)
    val type = when {
        token.startsWith("@") -> "accounts"
        token.startsWith("#") -> "hashtags"
        else -> return null
    }
    val query = token.drop(1)
    if (query.isBlank()) return null
    return AutocompleteToken(start = start, end = end, type = type, query = query)
}

/** The replacement snippet for an autocomplete pick (trailing space ends the token). */
internal fun autocompleteSnippet(type: String, value: String): String =
    if (type == "hashtags") "#$value " else "@$value "

/** Drops a leading @ / # from a result before building the snippet. */
internal fun autocompleteValue(value: String): String = value.trimStart('@', '#')

// ---- Quote mode -----------------------------------------------------------

/** The full status text for a quote post: commentary, blank line, RE line. */
internal fun quotePostText(commentary: String, quotedUrl: String): String =
    commentary.trimEnd() + "\n\nRE: " + quotedUrl

/**
 * The quote post's default visibility: the user's default, but never more
 * public than the quoted post (same demotion rule as replies).
 */
internal fun demoteVisibility(default: String, parent: String?): String =
    if (parent != null && visibilityRank(parent) > visibilityRank(default)) parent else default
