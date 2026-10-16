package net.snakepit.sidewinder.ui.filters

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.snakepit.sidewinder.core.filters.FilterV2
import net.snakepit.sidewinder.core.filters.FiltersRepository
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError

internal sealed interface FiltersState {
    data object Loading : FiltersState
    data class Error(val message: String) : FiltersState
    data object Empty : FiltersState
    data class Content(val items: List<FilterV2>) : FiltersState
}

/** The list state a failed mutation reverts to: Empty when the optimistic change emptied the list. */
internal fun stateAfterRevert(previous: List<FilterV2>): FiltersState =
    if (previous.isEmpty()) FiltersState.Empty else FiltersState.Content(previous)

/** (keyword text, whole word) pairs backing the keyword editor rows. */
@Serializable
private data class KeywordRow(val keyword: String, val wholeWord: Boolean, val existingId: String? = null)

@Serializable
private data class EditorState(
    val id: String? = null, // null = creating a new filter
    val title: String = "",
    val home: Boolean = false,
    val notifications: Boolean = false,
    val public: Boolean = false,
    val thread: Boolean = false,
    val action: String = "warn",
    val expirySeconds: Long? = null,
    val keywords: List<KeywordRow> = emptyList(),
)

/** Filter actions, in increasing severity order. */
private val ACTIONS = listOf("warn" to "Warn", "blur" to "Blur", "hide" to "Hide")

/** Expiry choices offered by the editor: label to seconds (null = never). */
private val EXPIRIES = listOf(
    "Never" to null,
    "30 minutes" to 1_800L,
    "1 hour" to 3_600L,
    "6 hours" to 21_600L,
    "1 day" to 86_400L,
    "7 days" to 604_800L,
    "30 days" to 2_592_000L,
)

private val editorJson = Json { ignoreUnknownKeys = true }

private val editorSaver = Saver<EditorState?, String>(
    save = { value -> value?.let { editorJson.encodeToString(EditorState.serializer(), it) } },
    restore = { text ->
        if (text == null) null
        else runCatching { editorJson.decodeFromString(EditorState.serializer(), text) }.getOrNull()
    },
)

/**
 * Formats a filter's `expires_at` timestamp (ISO 8601 offset) as a
 * localized date and time for display; null when it cannot be parsed.
 */
internal fun formatExpiresAt(iso: String): String? = try {
    val instant = java.time.OffsetDateTime.parse(iso).toInstant()
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM,
        java.text.DateFormat.SHORT,
        java.util.Locale.getDefault(),
    ).format(java.util.Date.from(instant))
} catch (e: Exception) {
    null
}

/**
 * Filter management, port of the web client's filters view on top of
 * `/api/v2/filters`. Lists the user's v2 filters, opens the editor on
 * tap, adds new filters, and deletes with confirmation. Deletions are
 * optimistic with revert and a snackbar on failure.
 */
@Composable
fun FiltersScreen(
    api: Api,
    session: Session,
) {
    var state by remember { mutableStateOf<FiltersState>(FiltersState.Loading) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var editor by rememberSaveable(stateSaver = editorSaver) { mutableStateOf<EditorState?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<FilterV2?>(null) }

    suspend fun reload() {
        state = try {
            val items = FiltersRepository.list(api, session.token)
            if (items.isEmpty()) FiltersState.Empty else FiltersState.Content(items)
        } catch (e: ApiError) {
            FiltersState.Error("Could not load filters: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FiltersState.Error("Could not load filters: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    LaunchedEffect(api, session.id) { reload() }

    /** Optimistic list update: apply [change], revert and explain on failure. */
    fun mutate(change: (List<FilterV2>) -> List<FilterV2>, revertTo: List<FilterV2>, action: suspend () -> Unit) {
        val previous = (state as? FiltersState.Content)?.items ?: revertTo
        val updated = change(previous)
        state = if (updated.isEmpty()) FiltersState.Empty else FiltersState.Content(updated)
        scope.launch {
            try {
                action()
            } catch (e: ApiError) {
                state = stateAfterRevert(previous)
                snackbarHostState.showSnackbar(
                    "Could not save the filter: the server returned error ${e.status}. The list was restored; try again.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = stateAfterRevert(previous)
                snackbarHostState.showSnackbar(
                    "Could not save the filter: ${e.message ?: "network error"}. The list was restored; check your connection and try again.",
                )
            }
        }
    }

    fun save(editorState: EditorState) {
        val contexts = buildList {
            if (editorState.home) add("home")
            if (editorState.notifications) add("notifications")
            if (editorState.public) add("public")
            if (editorState.thread) add("thread")
        }
        // The editor stays open with a busy state until the request
        // settles: closing early lost a typed filter on failure.
        saving = true
        saveError = null
        scope.launch {
            try {
                val existingId = editorState.id
                val saved = if (existingId == null) {
                    FiltersRepository.create(
                        api, session.token, editorState.title, contexts,
                        editorState.expirySeconds, editorState.action,
                        editorState.keywords.map { it.keyword to it.wholeWord },
                    )
                } else {
                    FiltersRepository.update(
                        api, session.token, existingId, editorState.title, contexts,
                        editorState.expirySeconds, editorState.action,
                        editorState.keywords.map { Triple(it.keyword, it.wholeWord, it.existingId) },
                    )
                }
                val current = (state as? FiltersState.Content)?.items ?: emptyList()
                val next = current.filterNot { it.id == saved.id } + saved
                state = if (next.isEmpty()) FiltersState.Empty else FiltersState.Content(next.sortedBy { it.title })
                saving = false
                editor = null
            } catch (e: ApiError) {
                saving = false
                saveError = "Could not save the filter: the server returned error ${e.status}. Try again."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saving = false
                saveError = "Could not save the filter: ${e.message ?: "network error"}. Check your connection and try again."
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val s = state) {
            FiltersState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading filters" },
                )
            }

            is FiltersState.Error -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { scope.launch { reload() } },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Retry loading filters" },
                    ) {
                        Text("Retry")
                    }
                }
            }

            FiltersState.Empty -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "No filters yet. Filters hide or blur posts that match keywords you choose.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { editor = EditorState() },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Add a filter" },
                    ) {
                        Text("Add a filter")
                    }
                }
            }

            is FiltersState.Content -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item(key = "add") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = { editor = EditorState() },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "Add a filter" },
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Add filter", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
                items(count = s.items.size, key = { s.items[it].id }) { index ->
                    val filter = s.items[index]
                    FilterRow(
                        filter = filter,
                        onTap = {
                            editor = EditorState(
                                id = filter.id,
                                title = filter.title,
                                home = "home" in filter.context,
                                notifications = "notifications" in filter.context,
                                public = "public" in filter.context,
                                thread = "thread" in filter.context,
                                action = filter.filter_action,
                                expirySeconds = null, // expiry is one-shot at save; existing expiry shown in the row
                                keywords = filter.keywords.map { KeywordRow(it.keyword, it.whole_word, it.id) },
                            )
                        },
                        onDelete = { pendingDelete = filter },
                    )
                    if (index < s.items.lastIndex) HorizontalDivider()
                }
            }
        }
    }

    editor?.let { current ->
        FilterEditorDialog(
            state = current,
            onStateChange = {
                saveError = null
                editor = it
            },
            saving = saving,
            errorText = saveError,
            onSave = { save(current) },
            onDismiss = {
                saveError = null
                editor = null
            },
        )
    }

    pendingDelete?.let { filter ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete filter") },
            text = { Text("Delete the filter \"${filter.title}\"? Posts it matched will show normally again.") },
            confirmButton = {
                val previous = (state as? FiltersState.Content)?.items ?: emptyList()
                TextButton(
                    onClick = {
                        pendingDelete = null
                        mutate(
                            change = { current -> current.filterNot { it.id == filter.id } },
                            revertTo = previous,
                            action = { FiltersRepository.delete(api, session.token, filter.id) },
                        )
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Confirm deleting filter ${filter.title}" },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Cancel deleting filter" },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

/** Human label for a filter action value. */
private fun actionLabel(action: String): String =
    ACTIONS.firstOrNull { it.first == action }?.second ?: action

/**
 * One filter row: title, action label, contexts, keyword count, and an
 * overflow menu with delete. Tapping anywhere else opens the editor.
 */
@Composable
private fun FilterRow(
    filter: FilterV2,
    onTap: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val contextNames = filter.context.joinToString(", ") { name ->
        when (name) {
            "home" -> "Home timeline"
            "notifications" -> "Notifications"
            "public" -> "Public timelines"
            "thread" -> "Threads"
            else -> name
        }
    }
    val expiry = filter.expires_at?.takeIf { it.isNotBlank() }?.let { iso ->
        formatExpiresAt(iso)?.let { "Expires $it" } ?: "Expires $iso"
    }
    val subtitle = buildList {
        add("${actionLabel(filter.filter_action)} in $contextNames")
        add("${filter.keywords.size} keyword${if (filter.keywords.size == 1) "" else "s"}")
        expiry?.let { add(it) }
    }.joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = filter.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "More options for filter ${filter.title}" },
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = {
                        menuOpen = false
                        onTap()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * Create/edit dialog: title, context checkboxes, action and expiry
 * dropdowns, and keyword rows. Save is disabled until the title is
 * non-blank and at least one keyword is non-blank.
 */
@Composable
private fun FilterEditorDialog(
    state: EditorState,
    onStateChange: (EditorState) -> Unit,
    saving: Boolean,
    errorText: String?,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val valid = state.title.isNotBlank() && state.keywords.any { it.keyword.isNotBlank() }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(if (state.id == null) "Add filter" else "Edit filter") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = { onStateChange(state.copy(title = it)) },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Filter title" },
                )

                Text("Where it applies", style = MaterialTheme.typography.labelLarge)
                ContextCheckbox("Home timeline", state.home) { onStateChange(state.copy(home = it)) }
                ContextCheckbox("Notifications", state.notifications) { onStateChange(state.copy(notifications = it)) }
                ContextCheckbox("Public timelines", state.public) { onStateChange(state.copy(public = it)) }
                ContextCheckbox("Threads", state.thread) { onStateChange(state.copy(thread = it)) }

                DropdownField(
                    label = "Filter action",
                    value = actionLabel(state.action),
                    options = ACTIONS.map { it.second },
                    onSelect = { label ->
                        onStateChange(state.copy(action = ACTIONS.first { it.second == label }.first))
                    },
                )
                DropdownField(
                    label = "Expires",
                    value = EXPIRIES.firstOrNull { it.second == state.expirySeconds }?.first ?: "Never",
                    options = EXPIRIES.map { it.first },
                    onSelect = { label ->
                        onStateChange(state.copy(expirySeconds = EXPIRIES.first { it.first == label }.second))
                    },
                )

                Text("Keywords", style = MaterialTheme.typography.labelLarge)
                state.keywords.forEachIndexed { index, row ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = row.keyword,
                            onValueChange = { text ->
                                onStateChange(state.copy(keywords = state.keywords.mapIndexed { i, r ->
                                    if (i == index) r.copy(keyword = text) else r
                                }))
                            },
                            label = { Text("Keyword") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Keyword ${index + 1}" },
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(
                                onClickLabel = "Toggle whole word matching for keyword ${index + 1}",
                            ) {
                                onStateChange(state.copy(keywords = state.keywords.mapIndexed { i, r ->
                                    if (i == index) r.copy(wholeWord = !r.wholeWord) else r
                                }))
                            },
                        ) {
                            Checkbox(
                                checked = row.wholeWord,
                                onCheckedChange = { checked ->
                                    onStateChange(state.copy(keywords = state.keywords.mapIndexed { i, r ->
                                        if (i == index) r.copy(wholeWord = checked) else r
                                    }))
                                },
                                modifier = Modifier.semantics { contentDescription = "Whole word matching for keyword ${index + 1}" },
                            )
                            Text("Whole word", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(
                            onClick = { onStateChange(state.copy(keywords = state.keywords.filterIndexed { i, _ -> i != index })) },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "Remove keyword ${index + 1}" },
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                }
                OutlinedButton(
                    onClick = { onStateChange(state.copy(keywords = state.keywords + KeywordRow("", wholeWord = false))) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Add a keyword" },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add keyword", modifier = Modifier.padding(start = 4.dp))
                }
                errorText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp)
                        .size(24.dp)
                        .semantics { contentDescription = "Saving filter" },
                )
            } else {
                Button(
                    onClick = onSave,
                    enabled = valid,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Save filter" },
                ) {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !saving,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Cancel editing filter" },
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ContextCheckbox(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    // The whole row is the checkbox (Role.Checkbox); the Checkbox graphic
    // is semantics-free so screen readers announce the row exactly once.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onChange,
            )
            .padding(vertical = 4.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Non-experimental dropdown: read-only field opening a DropdownMenu. */
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "$label: $value" },
        )
        // Transparent overlay: tapping anywhere on the field opens the
        // menu instead of the read-only text field stealing the tap.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClickLabel = "Choose $label") { open = true }
                .semantics { contentDescription = "Choose $label" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
