package net.snakepit.sidewinder.ui.dm

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.dm.ConversationsRepository
import net.snakepit.sidewinder.core.model.Conversation
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.ui.timeline.relativeTimestamp
import org.jsoup.Jsoup

private sealed interface DmsState {
    data object Loading : DmsState
    data class Error(val message: String) : DmsState
    data object Empty : DmsState
    data class Content(val items: List<Conversation>, val nextMaxId: String?, val loadingMore: Boolean) : DmsState
}

/**
 * Direct messages: one row per conversation (participants, last post
 * excerpt, unread flag). Tapping opens the last status's thread — replies
 * there continue the conversation. Overflow deletes the conversation from
 * the list; mark-read happens on open.
 */
@Composable
fun DmsScreen(api: Api, session: Session, onOpenThread: (String) -> Unit) {
    var state by remember { mutableStateOf<DmsState>(DmsState.Loading) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    suspend fun loadFirstPage() {
        state = DmsState.Loading
        state = try {
            val page = ConversationsRepository.list(api, session.token)
            if (page.items.isEmpty()) DmsState.Empty else DmsState.Content(page.items, page.nextMaxId, false)
        } catch (e: ApiError) {
            DmsState.Error("Could not load direct messages: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DmsState.Error("Could not load direct messages: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    fun loadNextPage() {
        val current = state as? DmsState.Content ?: return
        val maxId = current.nextMaxId ?: return
        if (current.loadingMore) return
        state = current.copy(loadingMore = true)
        scope.launch {
            try {
                val page = ConversationsRepository.list(api, session.token, maxId)
                val base = (state as? DmsState.Content)?.items ?: current.items
                val ids = base.mapTo(HashSet()) { it.id }
                state = DmsState.Content(base + page.items.filterNot { it.id in ids }, page.nextMaxId, false)
            } catch (e: ApiError) {
                (state as? DmsState.Content)?.let { latest -> state = latest.copy(loadingMore = false) }
                scope.launch { snackbar.showSnackbar("Could not load older conversations: the server returned error ${e.status}. Scroll to try again.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (state as? DmsState.Content)?.let { latest -> state = latest.copy(loadingMore = false) }
                scope.launch { snackbar.showSnackbar("Could not load older conversations: ${e.message ?: "network error"}. Scroll to try again.") }
            }
        }
    }

    LaunchedEffect(api, session.id) { loadFirstPage() }
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to state
        }.collect { (lastVisible, snapshot) ->
            val current = snapshot as? DmsState.Content ?: return@collect
            if (current.nextMaxId == null || current.loadingMore) return@collect
            if (lastVisible >= current.items.size - 3) loadNextPage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val s = state) {
            DmsState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading direct messages" },
                )
            }

            is DmsState.Error -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { scope.launch { loadFirstPage() } },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Retry loading direct messages" },
                    ) {
                        Text("Retry")
                    }
                }
            }

            DmsState.Empty -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No direct messages yet. Mention someone in a direct post to start a conversation.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is DmsState.Content -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(count = s.items.size, key = { s.items[it].id }) { index ->
                    ConversationRow(
                        conversation = s.items[index],
                        onOpen = { conversation ->
                            conversation.last_status?.id?.let(onOpenThread)
                            if (conversation.unread) {
                                scope.launch {
                                    try {
                                        ConversationsRepository.markRead(api, session.token, conversation.id)
                                        val fresh = conversation.copy(unread = false)
                                        state = (state as DmsState.Content)
                                            .copy(items = state.let { cur ->
                                                (cur as DmsState.Content).items.map { if (it.id == fresh.id) fresh else it }
                                            })
                                    } catch (_: Exception) {
                                        // Mark-read is best-effort; the dot survives a failure.
                                    }
                                }
                            }
                        },
                        onDelete = { conversation ->
                            scope.launch {
                                try {
                                    ConversationsRepository.delete(api, session.token, conversation.id)
                                    state = (state as DmsState.Content).copy(items = state.let { cur ->
                                        (cur as DmsState.Content).items.filterNot { it.id == conversation.id }
                                    })
                                } catch (e: ApiError) {
                                    snackbar.showSnackbar("Could not delete the conversation: the server returned error ${e.status}. Try again.")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    snackbar.showSnackbar("Could not delete the conversation: ${e.message ?: "network error"}. Try again.")
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
                if (s.loadingMore) {
                    item(key = "loading-more") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.semantics { contentDescription = "Loading older conversations" },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    onOpen: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val last = conversation.last_status
    val excerpt = last?.let { Jsoup.parse(it.content).text() }.orEmpty()
    val names = conversation.accounts
        .map { it.display_name?.takeIf { n -> n.isNotBlank() } ?: it.username }
        .ifEmpty { listOf("Unknown account") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Open the conversation") { onOpen(conversation) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = conversation.accounts.firstOrNull()?.avatar_static
                ?: conversation.accounts.firstOrNull()?.avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Avatar of ${names.first()}" },
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (names.size == 1) names.first() else "${names.first()} +${names.size - 1}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.unread) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .semantics { contentDescription = "Unread" },
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                }
                last?.let {
                    Text(
                        text = relativeTimestamp(it.created_at),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = excerpt.ifBlank { "(no text)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.semantics { contentDescription = "Actions for this conversation" },
            ) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Delete conversation") },
                    onClick = {
                        menuOpen = false
                        confirmDelete = true
                    },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this conversation?") },
            text = { Text("It disappears from this list. The messages themselves are not deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(conversation)
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep it") }
            },
        )
    }
}
