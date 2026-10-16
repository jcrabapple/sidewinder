package net.snakepit.sidewinder.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.thread.ThreadRepository
import net.snakepit.sidewinder.core.mute.MuteRepository
import net.snakepit.sidewinder.ui.timeline.StatusCard
import net.snakepit.sidewinder.ui.timeline.TimelineStore

private sealed interface ThreadState {
    /** Status or context still loading. */
    data object Loading : ThreadState

    /** Initial load failed; nothing to show. */
    data class Error(val message: String) : ThreadState

    /** The focused status was deleted in this screen. */
    data object Deleted : ThreadState

    /** Thread loaded. */
    data class Content(
        val ancestors: List<Status>,
        val focus: Status,
        val descendants: List<Status>,
        val deleting: Boolean = false,
    ) : ThreadState
}

/**
 * Thread view. Port of web `src/views/Thread.tsx`, narrowed to the
 * milestone scope: ancestor chain, the focused status emphasized with
 * full actions (including delete-if-own behind a confirmation), and
 * descendant replies indented one level. Single context fetch; deeper
 * descendant pagination is out of scope.
 */
@Composable
fun ThreadScreen(
    api: Api,
    session: Session,
    statusId: String,
    onOpenThread: (String) -> Unit,
    onCompose: (String) -> Unit,
    onDeleted: (String) -> Unit,
    /** Open the composer in edit mode for the focused (own) status. */
    onEdit: ((String) -> Unit)? = null,
) {
    var state by remember(statusId) { mutableStateOf<ThreadState>(ThreadState.Loading) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var unrollOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    suspend fun load() {
        state = ThreadState.Loading
        state = try {
            val focus = ThreadRepository.status(api, statusId)
            val context = ThreadRepository.context(api, statusId)
            ThreadState.Content(
                ancestors = context.ancestors.distinctBy { it.id },
                focus = focus,
                descendants = context.descendants.distinctBy { it.id },
            )
        } catch (e: ApiError) {
            ThreadState.Error("Could not load this thread: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ThreadState.Error("Could not load this thread: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    fun showNotice(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    /** Fav/boost results land here; StatusCard keeps the boost wrapper shape. */
    fun updateStatus(updated: Status) {
        val current = state as? ThreadState.Content ?: return
        state = current.copy(
            ancestors = current.ancestors.map { if (it.id == updated.id) updated else it },
            focus = if (current.focus.id == updated.id) updated else current.focus,
            descendants = current.descendants.map { if (it.id == updated.id) updated else it },
        )
        // Keep cached timelines in step, so going back shows the same state.
        TimelineStore.applyEdit(updated.reblog ?: updated)
    }

    fun deleteFocus(id: String) {
        val current = state as? ThreadState.Content ?: return
        if (current.deleting) return
        state = current.copy(deleting = true)
        scope.launch {
            try {
                ThreadRepository.delete(api, id)
                TimelineStore.applyDelete(id)
                state = ThreadState.Deleted
                onDeleted(id)
            } catch (e: ApiError) {
                (state as? ThreadState.Content)?.let { latest -> state = latest.copy(deleting = false) }
                showNotice("Could not delete this post: the server returned error ${e.status}. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (state as? ThreadState.Content)?.let { latest -> state = latest.copy(deleting = false) }
                showNotice("Could not delete this post: ${e.message ?: "network error"}. Check your connection and try again.")
            }
        }
    }

    /** Mute or unmute notifications for this conversation. */
    fun toggleThreadMute(focus: Status) {
        scope.launch {
            try {
                MuteRepository.setThreadMuted(api, session.token, focus.id, !focus.muted)
                updateStatus(focus.copy(muted = !focus.muted))
                showNotice(if (focus.muted) "Conversation unmuted." else "Conversation muted. You will not get notifications about it.")
            } catch (e: ApiError) {
                showNotice("Could not change the conversation mute: the server returned error ${e.status}. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNotice("Could not change the conversation mute: ${e.message ?: "network error"}. Check your connection and try again.")
            }
        }
    }

    LaunchedEffect(api, session.id, statusId) { load() }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this post?") },
            text = { Text("This permanently removes your post. Replies from other people are not removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        deleteFocus(statusId)
                    },
                    modifier = Modifier.semantics { contentDescription = "Confirm deleting this post" },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    modifier = Modifier.semantics { contentDescription = "Cancel deleting this post" },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (historyOpen) {
        HistoryDialog(statusId = statusId, api = api, baseUrl = session.base, onDismiss = { historyOpen = false })
    }
    val threadContent = state as? ThreadState.Content
    if (unrollOpen && threadContent != null) {
        UnrollDialog(
            ancestors = threadContent.ancestors,
            focus = threadContent.focus,
            descendants = threadContent.descendants,
            baseUrl = session.base,
            onDismiss = { unrollOpen = false },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val s = state) {
            ThreadState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading the thread" },
                )
            }

            is ThreadState.Error -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { scope.launch { load() } },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Retry loading the thread" },
                    ) {
                        Text("Retry")
                    }
                }
            }

            ThreadState.Deleted -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "This post was deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is ThreadState.Content -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                s.ancestors.forEach { ancestor ->
                    item(key = ancestor.id) {
                        StatusCard(
                            status = ancestor,
                            api = api,
                            baseUrl = session.base,
                            onStatusUpdated = { updateStatus(it) },
                            onNotice = { showNotice(it) },
                            onOpenThread = onOpenThread,
                            onCompose = onCompose,
                            filterContext = "thread",
                        )
                        HorizontalDivider()
                    }
                }

                item(key = "focus") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        StatusCard(
                            status = s.focus,
                            api = api,
                            baseUrl = session.base,
                            onStatusUpdated = { updateStatus(it) },
                            onNotice = { showNotice(it) },
                            // The focused status is already this thread; do not re-navigate.
                            onOpenThread = {},
                            onCompose = onCompose,
                            filterContext = "thread",
                        )
                        Row(Modifier.padding(start = 8.dp, end = 16.dp, bottom = 8.dp)) {
                            // Poll posts are not editable (see StatusCard's menu).
                            if (s.focus.account.id == session.account.id && onEdit != null && s.focus.poll == null) {
                                TextButton(
                                    onClick = { onEdit(s.focus.id) },
                                    enabled = !s.deleting,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics { contentDescription = "Edit this post" },
                                ) {
                                    Text("Edit post")
                                }
                            }
                            if (s.focus.account.id == session.account.id) {
                                TextButton(
                                    onClick = { showDeleteDialog = true },
                                    enabled = !s.deleting,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics { contentDescription = "Delete this post" },
                                ) {
                                    Text(
                                        text = "Delete post",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            TextButton(
                                onClick = { toggleThreadMute(s.focus) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics {
                                        contentDescription =
                                            if (s.focus.muted) "Unmute this conversation" else "Mute this conversation"
                                    },
                            ) {
                                Text(if (s.focus.muted) "Unmute conversation" else "Mute conversation")
                            }
                            if (s.focus.edited_at != null) {
                                TextButton(
                                    onClick = { historyOpen = true },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics { contentDescription = "Show the edit history of this post" },
                                ) {
                                    Text("History")
                                }
                            }
                            TextButton(
                                onClick = { unrollOpen = true },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics { contentDescription = "Unroll this thread as an article" },
                            ) {
                                Text("Unroll")
                            }
                        }
                    }
                    HorizontalDivider()
                }

                s.descendants.forEach { descendant ->
                    item(key = descendant.id) {
                        // One-level indentation: every reply is a direct child
                        // visually; deeper nesting is flattened, matching scope.
                        StatusCard(
                            status = descendant,
                            api = api,
                            baseUrl = session.base,
                            onStatusUpdated = { updateStatus(it) },
                            onNotice = { showNotice(it) },
                            onOpenThread = onOpenThread,
                            onCompose = onCompose,
                            filterContext = "thread",
                            modifier = Modifier.padding(start = 16.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
