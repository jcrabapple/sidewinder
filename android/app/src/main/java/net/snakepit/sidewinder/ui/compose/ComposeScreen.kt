package net.snakepit.sidewinder.ui.compose

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.snakepit.sidewinder.core.compose.ComposeDraft
import net.snakepit.sidewinder.core.compose.DraftMediaRef
import net.snakepit.sidewinder.core.compose.DraftStore
import net.snakepit.sidewinder.core.model.MediaAttachment
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.network.NetworkError
import net.snakepit.sidewinder.core.thread.ThreadRepository
import net.snakepit.sidewinder.ui.app.LocalAppSettings
import net.snakepit.sidewinder.ui.timeline.TimelineStore
import org.jsoup.Jsoup
import java.util.UUID

internal const val CHAR_LIMIT = 5000
internal const val MAX_MEDIA = 6
internal const val POLL_OPTION_LIMIT = 50
internal const val ALT_TEXT_LIMIT = 1500

internal val EXPIRY_OPTIONS = listOf(
    1_800L to "30 minutes",
    3_600L to "1 hour",
    21_600L to "6 hours",
    86_400L to "1 day",
    259_200L to "3 days",
    604_800L to "7 days",
)

/** Per-item media state: uploading, ready (server attachment), or error. */
internal data class DraftMedia(
    val key: String,
    val uri: Uri? = null,
    val filename: String = "",
    val attachment: MediaAttachment? = null,
    val uploading: Boolean = false,
    val error: String? = null,
    /** Completes with the attachment on success, null on failure. */
    val upload: CompletableDeferred<MediaAttachment?>? = null,
)

private enum class ComposeMode { New, Reply, Edit }

/**
 * Markdown composer for new posts, replies, and edits. Layout follows
 * Tusky/Moshidon: a top bar (close, title, Post), the post being replied
 * to, a "posting as" row with the visibility picker, a borderless text
 * box that opens focused with the keyboard up and sentence-capitalized,
 * an attachment strip, an optional poll card, and a bottom toolbar with
 * the remaining-characters ring.
 *
 * New-post drafts persist per session (debounced) and restore on open;
 * reply and edit drafts do not. Closing with unsaved work asks first.
 * Edits PUT the same body shape the web client sends, keep the original
 * visibility, and never touch polls (callers only offer edit on poll-less
 * posts).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    api: Api,
    session: Session,
    replyTo: Status?,
    onPosted: (Status) -> Unit,
    onDismiss: () -> Unit,
    /** Text shared into the app (ACTION_SEND); appended to any restored draft. */
    initialText: String? = null,
    /** Images shared into the app; uploaded on open, up to the attachment cap. */
    initialMedia: List<Uri> = emptyList(),
    /** Own status being edited; switches the composer into edit mode. */
    editing: Status? = null,
    /** Source text of [editing] from `/statuses/{id}/source`, when available. */
    editSourceText: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val draftStore = remember { DraftStore(context) }
    val appSettings = LocalAppSettings.current
    val mode = when {
        editing != null -> ComposeMode.Edit
        replyTo != null -> ComposeMode.Reply
        else -> ComposeMode.New
    }

    var body by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var cwOn by rememberSaveable { mutableStateOf(false) }
    var spoiler by rememberSaveable { mutableStateOf("") }
    var markSensitive by rememberSaveable { mutableStateOf(false) }
    var visibility by rememberSaveable {
        mutableStateOf(editing?.visibility ?: replyTo?.visibility ?: appSettings.defaultVisibility)
    }
    val media = remember { mutableStateListOf<DraftMedia>() }
    var pollOn by rememberSaveable { mutableStateOf(false) }
    val pollOptions = remember { mutableStateListOf("", "") }
    var pollMultiple by rememberSaveable { mutableStateOf(false) }
    var pollExpiry by rememberSaveable { mutableLongStateOf(86_400L) }
    var posting by rememberSaveable { mutableStateOf(false) }
    var pendingAltConfirm by rememberSaveable { mutableStateOf(false) }
    var altDialogKey by rememberSaveable { mutableStateOf<String?>(null) }
    var closePrompt by rememberSaveable { mutableStateOf(false) }
    var draftReady by rememberSaveable { mutableStateOf(false) }
    // Snapshot of the opening state, to tell whether a reply/edit has unsaved work.
    var baseline by rememberSaveable { mutableStateOf("") }
    val uploadJobs = remember { mutableMapOf<String, Job>() }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val text = body.text
    fun setTextAtEnd(value: String) {
        body = TextFieldValue(value, selection = TextRange(value.length))
    }
    fun fingerprint(): String =
        // body.text, not the composition-time `text` val: this also runs from
        // effects, where that val is stale (baseline would be "" and Save on).
        listOf(body.text, cwOn.toString(), spoiler, markSensitive.toString(), media.joinToString { it.attachment?.id + ":" + it.attachment?.description })
            .joinToString("\u0000")

    // ---- Initial content: edit source, reply mentions, or saved draft ----
    LaunchedEffect(Unit) {
        if (draftReady) {
            // Recreated (rotation): text and toggles came back via saved state,
            // but the attachment list did not. New posts recover it from the draft.
            if (mode == ComposeMode.New && media.isEmpty()) {
                draftStore.load(session.id)?.media?.let { saved ->
                    media.addAll(saved.map { DraftMedia(key = UUID.randomUUID().toString(), attachment = it.attachment) })
                }
            }
            return@LaunchedEffect
        }
        when (mode) {
            ComposeMode.Edit -> {
                val status = editing ?: return@LaunchedEffect
                setTextAtEnd(editSourceText ?: status.text ?: Jsoup.parse(status.content).wholeText().trim())
                spoiler = status.spoiler_text
                cwOn = status.spoiler_text.isNotBlank()
                markSensitive = status.sensitive && status.spoiler_text.isBlank()
                media.addAll(status.media_attachments.map { DraftMedia(key = it.id, attachment = it) })
            }
            ComposeMode.Reply -> {
                val parent = replyTo ?: return@LaunchedEffect
                // Mention the author plus everyone they mentioned, minus yourself
                // (Tusky/Moshidon behavior). Inherit the parent's content warning.
                val handles = (listOf(parent.account.acct) + parent.mentions.map { it.acct })
                    .filter { it != session.account.acct }
                    .distinct()
                setTextAtEnd(handles.joinToString(" ") { "@$it" } + if (handles.isNotEmpty()) " " else "")
                if (parent.spoiler_text.isNotBlank()) {
                    cwOn = true
                    spoiler = parent.spoiler_text
                }
            }
            ComposeMode.New -> draftStore.load(session.id)?.let { draft ->
                setTextAtEnd(draft.text)
                cwOn = draft.cw
                spoiler = draft.spoiler
                visibility = draft.visibility
                pollOn = draft.pollOn
                pollOptions.clear()
                pollOptions.addAll(draft.pollOptions.ifEmpty { listOf("", "") })
                pollMultiple = draft.pollMultiple
                pollExpiry = draft.pollExpiry
                media.addAll(draft.media.map { DraftMedia(key = UUID.randomUUID().toString(), attachment = it.attachment) })
            }
        }
        baseline = fingerprint()
        draftReady = true
    }

    // Focus the text box and raise the keyboard as soon as content is in place.
    LaunchedEffect(draftReady) {
        if (!draftReady) return@LaunchedEffect
        delay(60) // let the restored text lay out before placing the caret
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    // ---- Debounced draft save (new posts only) ------------------------
    val keyPollOptions = pollOptions.joinToString("\u0000")
    suspend fun saveDraftNow() {
        draftStore.save(
            session.id,
            ComposeDraft(
                text = text,
                cw = cwOn,
                spoiler = spoiler,
                visibility = visibility,
                media = media.mapNotNull { item -> item.attachment?.let { DraftMediaRef(it) } },
                pollOn = pollOn,
                pollOptions = pollOptions.toList(),
                pollMultiple = pollMultiple,
                pollExpiry = pollExpiry,
                savedAt = System.currentTimeMillis(),
            ),
        )
    }
    val composerEmpty = text.isBlank() && spoiler.isBlank() && media.isEmpty() && !pollOn
    LaunchedEffect(
        draftReady, text, cwOn, spoiler, visibility,
        pollOn, pollMultiple, pollExpiry, keyPollOptions,
        media.size, media.count { it.attachment != null },
    ) {
        if (!draftReady || mode != ComposeMode.New || posting) return@LaunchedEffect
        if (composerEmpty) {
            draftStore.clear(session.id) // user emptied the composer
            return@LaunchedEffect
        }
        delay(600)
        saveDraftNow()
    }

    fun showError(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun uploadItem(item: DraftMedia) {
        val uri = item.uri ?: return
        val deferred = CompletableDeferred<MediaAttachment?>()
        val index = media.indexOfFirst { it.key == item.key }
        if (index < 0) return
        media[index] = item.copy(uploading = true, error = null, upload = deferred)
        uploadJobs[item.key] = scope.launch {
            val attachment = try {
                val (bytes, name, type) = readPickedImage(context, uri)
                api.upload(
                    path = "/api/v2/media",
                    serializer = MediaAttachment.serializer(),
                    filename = name,
                    mediaType = type,
                    bytes = bytes,
                    fields = emptyMap(),
                )
            } catch (e: CancellationException) {
                deferred.complete(null) // awaiters must never hang on a cancelled upload
                throw e
            } catch (e: ApiError) {
                showError("An image did not upload (server error ${e.status}). Tap it to retry.")
                null
            } catch (e: NetworkError) {
                showError("An image did not upload: no connection. Tap it to retry.")
                null
            } catch (e: Exception) {
                showError("An image did not upload. Tap it to retry.")
                null
            }
            val idx = media.indexOfFirst { it.key == item.key }
            if (idx >= 0) {
                media[idx] = media[idx].copy(
                    uploading = false,
                    attachment = attachment,
                    error = if (attachment == null) "Upload failed" else null,
                )
            }
            deferred.complete(attachment)
            uploadJobs.remove(item.key)
        }
    }

    /** Rows first, then uploads: uploadItem updates rows in place by key. */
    fun addPickedMedia(uris: List<Uri>) {
        for (uri in uris) {
            if (media.size >= MAX_MEDIA) {
                showError("A post holds up to $MAX_MEDIA attachments. The extra images were not added.")
                break
            }
            val name = runCatching { queryPickedImage(context, uri).first }.getOrDefault("Attachment")
            val item = DraftMedia(key = UUID.randomUUID().toString(), uri = uri, filename = name)
            media.add(item)
            uploadItem(item)
        }
    }

    fun removeMedia(item: DraftMedia) {
        media.firstOrNull { it.key == item.key }?.upload?.complete(null)
        uploadJobs.remove(item.key)?.cancel()
        media.removeAll { it.key == item.key }
    }

    // ---- Shared-into-app content (ACTION_SEND), applied once after restore ----
    var sharedApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(draftReady) {
        if (!draftReady || sharedApplied || mode != ComposeMode.New) return@LaunchedEffect
        sharedApplied = true
        initialText?.takeIf { it.isNotBlank() }?.let { shared ->
            setTextAtEnd(if (text.isBlank()) shared else text.trimEnd() + "\n\n" + shared)
        }
        if (initialMedia.isNotEmpty()) {
            pollOn = false // media and polls are mutually exclusive
            addPickedMedia(initialMedia)
        }
    }

    // ---- Validation ----
    val used = text.codePointCount(0, text.length) + if (cwOn) spoiler.codePointCount(0, spoiler.length) else 0
    val overLimit = used > CHAR_LIMIT
    val readyItems = media.filter { it.attachment != null }
    val missingAlt = readyItems.count { it.attachment?.description.isNullOrBlank() }
    val pollInvalid = pollOn && (pollOptions.count { it.isNotBlank() } < 2 || pollOptions.any { it.length > POLL_OPTION_LIMIT })
    val hasContent = text.isNotBlank() || media.isNotEmpty()
    val dirty = draftReady && fingerprint() != baseline
    // GoToSocial answers an edit that changes nothing with HTTP 422, so
    // Save stays disabled until the edit actually differs.
    val canPost = draftReady && !posting && hasContent && !overLimit && !pollInvalid &&
        media.none { it.error != null } && (mode != ComposeMode.Edit || dirty)

    fun submit() {
        posting = true
        pendingAltConfirm = false
        scope.launch {
            try {
                // Wait for in-flight uploads before building the request.
                media.mapNotNull { it.upload }.forEach { it.await() }
                val current = media.toList()
                if (current.any { it.attachment == null }) {
                    showError("Some images did not upload. Retry or remove them, then post again.")
                    return@launch
                }
                val sensitive = (cwOn && spoiler.isNotBlank()) || (markSensitive && current.isNotEmpty())
                val payload: JsonObject = buildJsonObject {
                    put("status", text)
                    put("content_type", editing?.content_type ?: "text/markdown")
                    put("visibility", visibility)
                    put("spoiler_text", if (cwOn) spoiler else "")
                    put("sensitive", sensitive)
                    if (replyTo != null && mode == ComposeMode.Reply) put("in_reply_to_id", replyTo.id)
                    // Edits always send the list: it is the set of attachments to KEEP.
                    if (current.isNotEmpty() || mode == ComposeMode.Edit) {
                        putJsonArray("media_ids") { current.forEach { add(JsonPrimitive(it.attachment?.id)) } }
                    }
                    if (mode == ComposeMode.Edit && current.isNotEmpty()) {
                        // Alt text of already-attached media rides along with the edit.
                        putJsonArray("media_attributes") {
                            current.forEach { item ->
                                val attachment = item.attachment ?: return@forEach
                                add(buildJsonObject {
                                    put("id", attachment.id)
                                    put("description", attachment.description.orEmpty())
                                })
                            }
                        }
                    }
                    if (pollOn && current.isEmpty() && mode != ComposeMode.Edit) {
                        putJsonObject("poll") {
                            putJsonArray("options") { pollOptions.filter { it.isNotBlank() }.forEach { add(JsonPrimitive(it)) } }
                            put("expires_in", pollExpiry)
                            put("multiple", pollMultiple)
                        }
                    }
                }
                val result = if (mode == ComposeMode.Edit && editing != null) {
                    ThreadRepository.edit(api, editing.id, payload).also { TimelineStore.applyEdit(it) }
                } else {
                    api.request(
                        path = "/api/v1/statuses",
                        serializer = Status.serializer(),
                        method = "POST",
                        body = payload,
                        headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
                    ).also { TimelineStore.applyPosted(session.id, it) }
                }
                if (mode == ComposeMode.New) draftStore.clear(session.id)
                keyboard?.hide()
                onPosted(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                val what = if (mode == ComposeMode.Edit) "Saving your edit" else "Posting"
                val reason = serverReason(e)?.let { ": $it" } ?: " (server error ${e.status})"
                showError("$what failed$reason. Nothing was lost; try again.")
            } catch (e: Exception) {
                val what = if (mode == ComposeMode.Edit) "Saving your edit" else "Posting"
                showError("$what failed: ${e.message ?: "no connection"}. Nothing was lost; try again.")
            } finally {
                posting = false
            }
        }
    }

    fun requestSubmit() {
        // The Settings "Require alt text" toggle gates the confirm.
        if (missingAlt > 0 && appSettings.requireAltText) pendingAltConfirm = true else submit()
    }

    fun requestClose() {
        when {
            posting -> Unit
            mode == ComposeMode.New && !composerEmpty -> closePrompt = true
            mode != ComposeMode.New && dirty -> closePrompt = true
            else -> {
                keyboard?.hide()
                onDismiss()
            }
        }
    }
    BackHandler(enabled = !closePrompt) { requestClose() }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_MEDIA)) { uris ->
        addPickedMedia(uris)
    }

    val title = when (mode) {
        ComposeMode.Edit -> "Edit post"
        ComposeMode.Reply -> "Reply"
        ComposeMode.New -> "New post"
    }
    val actionLabel = when (mode) {
        ComposeMode.Edit -> "Save"
        ComposeMode.Reply -> "Reply"
        ComposeMode.New -> "Post"
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                // The app shell already pads the status bar.
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(
                        onClick = { requestClose() },
                        modifier = Modifier.semantics { contentDescription = "Close composer" },
                    ) { Icon(Icons.Filled.Close, contentDescription = null) }
                },
                title = { Text(title) },
                actions = {
                    Button(
                        onClick = { requestSubmit() },
                        enabled = canPost,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .semantics { contentDescription = actionLabel },
                    ) {
                        if (posting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(actionLabel)
                        }
                    }
                },
            )
        },
        bottomBar = {
            ComposeToolbar(
                canAddMedia = !pollOn && media.size < MAX_MEDIA && !posting,
                onAddMedia = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                pollOn = pollOn,
                pollEnabled = media.isEmpty() && mode != ComposeMode.Edit && !posting,
                onTogglePoll = { pollOn = !pollOn },
                cwOn = cwOn,
                onToggleCw = { cwOn = !cwOn },
                hasMedia = media.isNotEmpty(),
                sensitive = markSensitive || (cwOn && spoiler.isNotBlank()),
                onToggleSensitive = { markSensitive = !markSensitive },
                used = used,
                limit = CHAR_LIMIT,
                // Sit on top of the keyboard. The shell already pads the
                // navigation bar, so only the IME height beyond it is added.
                modifier = Modifier.windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            replyTo?.takeIf { mode == ComposeMode.Reply }?.let { ReplyContext(it) }

            AuthorRow(
                session = session,
                visibility = visibility,
                locked = mode == ComposeMode.Edit,
                onVisibilityChange = { visibility = it },
            )

            if (cwOn) {
                TextField(
                    value = spoiler,
                    onValueChange = { spoiler = it },
                    placeholder = { Text("Content warning") },
                    leadingIcon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = ProseKeyboard,
                    colors = borderlessColors(container = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .semantics { contentDescription = "Content warning" },
                )
            }

            TextField(
                value = body,
                onValueChange = { body = it },
                placeholder = {
                    Text(
                        when (mode) {
                            ComposeMode.Reply -> "Write your reply"
                            ComposeMode.Edit -> "Edit your post"
                            ComposeMode.New -> "What's on your mind?"
                        },
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = ProseKeyboard,
                colors = borderlessColors(container = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .padding(horizontal = 4.dp)
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = "Post text. Markdown is supported." },
            )

            if (overLimit) {
                Text(
                    "This post is ${used - CHAR_LIMIT} characters over the $CHAR_LIMIT-character limit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (media.isNotEmpty()) {
                MediaStrip(
                    media = media,
                    onRemove = { removeMedia(it) },
                    onRetry = { uploadItem(it) },
                    onEditAlt = { altDialogKey = it.key },
                )
            }

            if (pollOn) {
                PollEditor(
                    options = pollOptions,
                    onOptionChange = { index, value -> pollOptions[index] = value },
                    onAddOption = { pollOptions.add("") },
                    onRemoveOption = { pollOptions.removeAt(it) },
                    multiple = pollMultiple,
                    onMultipleChange = { pollMultiple = it },
                    expiry = pollExpiry,
                    onExpiryChange = { pollExpiry = it },
                )
                if (pollInvalid) {
                    Text(
                        "A poll needs at least 2 options.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            HorizontalDivider(color = Color.Transparent, modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    // ---- Alt-text dialog ----
    altDialogKey?.let { key ->
        val item = media.firstOrNull { it.key == key }
        val attachment = item?.attachment
        if (attachment != null) {
            AltTextDialog(
                previewModel = attachment.preview_url ?: item.uri,
                initial = attachment.description.orEmpty(),
                onCancel = { altDialogKey = null },
                onSave = { description ->
                    altDialogKey = null
                    // Optimistic local update; the server copy follows.
                    fun setLocal(value: String?) {
                        val idx = media.indexOfFirst { it.key == key }
                        if (idx >= 0) media[idx] = media[idx].copy(attachment = media[idx].attachment?.copy(description = value))
                    }
                    val previous = attachment.description
                    setLocal(description)
                    val alreadyAttached = mode == ComposeMode.Edit && editing?.media_attachments?.any { it.id == attachment.id } == true
                    scope.launch {
                        try {
                            api.request(
                                path = "/api/v1/media/${attachment.id}",
                                serializer = MediaAttachment.serializer(),
                                method = "PUT",
                                body = buildJsonObject { put("description", description) },
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (alreadyAttached) {
                                // Attached media may refuse direct updates; the edit
                                // request carries the description instead.
                                snackbar.showSnackbar("The description will be saved when you save the edit.")
                            } else {
                                setLocal(previous)
                                showError("The description was not saved: ${e.message ?: "no connection"}. Try again.")
                            }
                        }
                    }
                },
            )
        }
    }

    // ---- Alt-text confirm guard ----
    if (pendingAltConfirm && missingAlt > 0) {
        AlertDialog(
            onDismissRequest = { pendingAltConfirm = false },
            title = { Text("Post without descriptions?") },
            text = {
                Text(
                    if (missingAlt == 1) "1 image has no description, so people using screen readers will miss it."
                    else "$missingAlt images have no description, so people using screen readers will miss them.",
                )
            },
            confirmButton = { TextButton(onClick = { submit() }) { Text("$actionLabel anyway") } },
            dismissButton = { TextButton(onClick = { pendingAltConfirm = false }) { Text("Add descriptions") } },
        )
    }

    // ---- Close with unsaved work ----
    if (closePrompt) {
        val newPost = mode == ComposeMode.New
        AlertDialog(
            onDismissRequest = { closePrompt = false },
            title = {
                Text(
                    when (mode) {
                        ComposeMode.New -> "Keep this draft?"
                        ComposeMode.Reply -> "Discard this reply?"
                        ComposeMode.Edit -> "Discard your changes?"
                    },
                )
            },
            text = {
                Text(
                    if (newPost) "Kept drafts reopen the next time you start a new post."
                    else "What you wrote here will be lost.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        closePrompt = false
                        keyboard?.hide()
                        if (newPost) {
                            scope.launch {
                                saveDraftNow() // flush the debounce before leaving
                                onDismiss()
                            }
                        } else {
                            media.toList().forEach { removeMedia(it) }
                            onDismiss()
                        }
                    },
                ) {
                    Text(
                        if (newPost) "Keep draft" else "Discard",
                        color = if (newPost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                if (newPost) {
                    TextButton(
                        onClick = {
                            closePrompt = false
                            keyboard?.hide()
                            scope.launch {
                                draftStore.clear(session.id)
                                onDismiss()
                            }
                        },
                    ) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { closePrompt = false }) { Text("Keep editing") }
                }
            },
        )
    }
}

/** The `error` string from a Mastodon-style JSON error body, if the server sent one. */
private fun serverReason(e: ApiError): String? =
    runCatching { (e.body as? JsonObject)?.get("error")?.jsonPrimitive?.content }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.take(160)

@Composable
private fun borderlessColors(container: Color) = TextFieldDefaults.colors(
    focusedContainerColor = container,
    unfocusedContainerColor = container,
    disabledContainerColor = container,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

/** Reads display name and mime type for a picked image without loading bytes. */
private fun queryPickedImage(context: Context, uri: Uri): Pair<String, String> {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    } ?: "upload.jpg"
    val type = context.contentResolver.getType(uri) ?: "image/jpeg"
    return name to type
}

/** Loads the picked image's bytes plus its name/type on IO. */
private suspend fun readPickedImage(context: Context, uri: Uri): Triple<ByteArray, String, String> =
    withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw NetworkError("Could not read the selected image.")
        val (name, type) = queryPickedImage(context, uri)
        Triple(bytes, name, type)
    }
