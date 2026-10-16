package net.snakepit.sidewinder.ui.lists

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.lists.ListRecord
import net.snakepit.sidewinder.core.lists.ListsRepository
import net.snakepit.sidewinder.core.lists.RepliesPolicy
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError

private sealed interface ListsState {
    /** First load still running. */
    data object Loading : ListsState

    /** First load failed; nothing to show. */
    data class Error(val message: String) : ListsState

    /** Loaded with zero lists. */
    data object Empty : ListsState

    /** At least one list loaded. */
    data class Content(val lists: List<ListRecord>) : ListsState
}

/**
 * All of the user's lists. Port of the web client's list index: create
 * with a title and replies policy, rename, delete behind a confirm, and
 * tap through to the members screen. The four mandatory states wrap the
 * first load; later mutations surface errors as snackbars.
 */
@Composable
fun ListsScreen(
    api: Api,
    session: Session,
    onOpenList: (ListRecord) -> Unit,
) {
    var state by remember { mutableStateOf<ListsState>(ListsState.Loading) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Dialog state: which editor is open, and which list (if any) a
    // delete confirm is asking about.
    var editing by remember { mutableStateOf<ListRecord?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ListRecord?>(null) }

    suspend fun load() {
        state = ListsState.Loading
        state = try {
            val lists = ListsRepository.lists(api).sortedBy { it.title.lowercase() }
            if (lists.isEmpty()) ListsState.Empty else ListsState.Content(lists)
        } catch (e: ApiError) {
            ListsState.Error("Could not load lists: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ListsState.Error("Could not load lists: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    fun upsert(record: ListRecord) {
        val current = state as? ListsState.Content ?: ListsState.Content(emptyList())
        val updated = current.lists
            .map { if (it.id == record.id) record else it }
            .let { if (current.lists.none { l -> l.id == record.id }) it + record else it }
            .sortedBy { it.title.lowercase() }
        state = ListsState.Content(updated)
    }

    fun remove(record: ListRecord) {
        val current = state as? ListsState.Content ?: return
        val remaining = current.lists.filterNot { it.id == record.id }
        state = if (remaining.isEmpty()) ListsState.Empty else ListsState.Content(remaining)
    }

    fun save(title: String, policy: RepliesPolicy, existing: ListRecord?) {
        scope.launch {
            try {
                val saved = if (existing == null) {
                    ListsRepository.create(api, title, policy)
                } else {
                    ListsRepository.update(api, existing.id, title, policy)
                }
                upsert(saved)
            } catch (e: ApiError) {
                snackbarHostState.showSnackbar(
                    "Could not ${if (existing == null) "create" else "rename"} the list: the server returned error ${e.status}. Try again.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    "Could not ${if (existing == null) "create" else "rename"} the list: ${e.message ?: "network error"}. Check your connection and try again.",
                )
            }
        }
    }

    fun confirmDelete(record: ListRecord) {
        deleting = null
        remove(record)
        scope.launch {
            try {
                ListsRepository.delete(api, record.id)
            } catch (e: ApiError) {
                upsert(record)
                snackbarHostState.showSnackbar(
                    "Could not delete \"${record.title}\": the server returned error ${e.status}. The list was restored; try again.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                upsert(record)
                snackbarHostState.showSnackbar(
                    "Could not delete \"${record.title}\": ${e.message ?: "network error"}. The list was restored; check your connection and try again.",
                )
            }
        }
    }

    LaunchedEffect(api, session.id) { load() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state is ListsState.Content || state is ListsState.Empty) {
                FloatingActionButton(
                    onClick = { creating = true },
                    modifier = Modifier.semantics { contentDescription = "Create a new list" },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                }
            }
        },
    ) { padding ->
        when (val s = state) {
            ListsState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading lists" },
                )
            }

            is ListsState.Error -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { scope.launch { load() } },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Retry loading lists" },
                    ) {
                        Text("Retry")
                    }
                }
            }

            ListsState.Empty -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "No lists yet. Use the add button to group accounts into a feed you can open any time.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is ListsState.Content -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(count = s.lists.size, key = { s.lists[it].id }) { index ->
                    val record = s.lists[index]
                    ListRow(
                        record = record,
                        onOpen = { onOpenList(record) },
                        onRename = { editing = record },
                        onDelete = { deleting = record },
                    )
                    if (index < s.lists.lastIndex) HorizontalDivider()
                }
            }
        }
    }

    if (creating) {
        ListEditDialog(
            existing = null,
            onDismiss = { creating = false },
            onSave = { title, policy ->
                creating = false
                save(title, policy, existing = null)
            },
        )
    }
    editing?.let { record ->
        ListEditDialog(
            existing = record,
            onDismiss = { editing = null },
            onSave = { title, policy ->
                editing = null
                save(title, policy, existing = record)
            },
        )
    }
    deleting?.let { record ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this list?") },
            text = { Text("Members stay followed. The list and its timeline disappear from this app and the server.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete(record) },
                    modifier = Modifier.semantics { contentDescription = "Confirm deleting ${record.title}" },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleting = null },
                    modifier = Modifier.semantics { contentDescription = "Cancel deleting ${record.title}" },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * One list row: title, replies-policy subtitle, tap opens the members
 * screen, trailing rename and delete actions (48dp targets).
 */
@Composable
private fun ListRow(
    record: ListRecord,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics {
                contentDescription = "List ${record.title}, ${repliesPolicyLabel(record.replies_policy)}. Opens list members."
            },
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = record.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = repliesPolicyLabel(record.replies_policy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onRename,
            modifier = Modifier.semantics { contentDescription = "Rename ${record.title}" },
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null)
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.semantics { contentDescription = "Delete ${record.title}" },
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null)
        }
    }
}

/** Sentence-case policy line for row subtitles. */
private fun repliesPolicyLabel(apiValue: String): String =
    when (RepliesPolicy.fromApi(apiValue)) {
        RepliesPolicy.List -> "Replies from list members"
        RepliesPolicy.Followed -> "Replies from accounts you follow"
        RepliesPolicy.None -> "No replies shown"
    }

/**
 * Create-or-rename dialog: title field plus replies-policy dropdown
 * with "Members of this list" as the default. Saves on the confirm
 * button; validation keeps the title non-blank.
 */
@Composable
private fun ListEditDialog(
    existing: ListRecord?,
    onDismiss: () -> Unit,
    onSave: (title: String, policy: RepliesPolicy) -> Unit,
) {
    var title by remember(existing) { mutableStateOf(existing?.title.orEmpty()) }
    var policy by remember(existing) {
        mutableStateOf(existing?.let { RepliesPolicy.fromApi(it.replies_policy) } ?: RepliesPolicy.List)
    }
    var policyOpen by remember { mutableStateOf(false) }
    val valid = title.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New list" else "Rename list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text("Title") },
                    isError = title.isBlank(),
                    supportingText = if (title.isBlank()) {
                        { Text("Enter a title to save.") }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "List title" },
                )
                Box {
                    OutlinedTextField(
                        value = policy.label,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("Show replies from") },
                        trailingIcon = {
                            IconButton(
                                onClick = { policyOpen = true },
                                modifier = Modifier.semantics {
                                    contentDescription = "Choose replies policy, current ${policy.label}"
                                },
                            ) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Replies policy, ${policy.label}" },
                    )
                    DropdownMenu(
                        expanded = policyOpen,
                        onDismissRequest = { policyOpen = false },
                    ) {
                        RepliesPolicy.entries.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.label) },
                                onClick = {
                                    policy = candidate
                                    policyOpen = false
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = "Replies policy option: ${candidate.label}"
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), policy) },
                enabled = valid,
                modifier = Modifier.semantics {
                    contentDescription = if (existing == null) "Create list" else "Save list title"
                },
            ) {
                Text(if (existing == null) "Create" else "Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Cancel list editing" },
            ) {
                Text("Cancel")
            }
        },
    )
}
