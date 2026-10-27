package net.snakepit.sidewinder.ui.requests

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.requests.FollowRequestsRepository
import net.snakepit.sidewinder.core.text.StatusText

private sealed interface RequestsState {
    /** First page still loading. */
    data object Loading : RequestsState

    /** First page failed; nothing to show. */
    data class Error(val message: String) : RequestsState

    /** First page loaded with zero items. */
    data object Empty : RequestsState

    /** At least one page loaded. */
    data class Content(
        val items: List<Account>,
        val nextMaxId: String?,
        val loadingMore: Boolean,
        val loadMoreFailed: Boolean = false,
    ) : RequestsState
}

/**
 * Follow requests. Port of web `src/views/FollowRequests.tsx`: paged
 * through `FollowRequestsRepository` with Link-header max_id paging,
 * infinite scroll near the list end, and the four mandatory states.
 *
 * Accept and remove are optimistic: the row disappears immediately and
 * is restored with an explanatory snackbar if the server call fails.
 * [onChanged] fires after every decision so the shell can refresh badges.
 */
@Composable
fun FollowRequestsScreen(
    api: Api,
    session: Session,
    onChanged: () -> Unit,
) {
    var state by remember { mutableStateOf<RequestsState>(RequestsState.Loading) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var failedPagePosition by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    suspend fun loadFirstPage() {
        state = RequestsState.Loading
        state = try {
            val page = FollowRequestsRepository.requests(api)
            val fresh = page.items.distinctBy { it.id }
            if (fresh.isEmpty()) RequestsState.Empty
            else RequestsState.Content(fresh, page.nextMaxId, loadingMore = false)
        } catch (e: ApiError) {
            RequestsState.Error("Could not load follow requests: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RequestsState.Error("Could not load follow requests: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    // One load-more at a time; the guard is re-checked here so a second
    // trigger cannot double-fetch the same page.
    fun loadNextPage() {
        val current = state as? RequestsState.Content ?: return
        val maxId = current.nextMaxId ?: return // end reached
        if (current.loadingMore) return

        state = current.copy(loadingMore = true, loadMoreFailed = false)
        scope.launch {
            try {
                val page = FollowRequestsRepository.requests(api, maxId)
                val base = (state as? RequestsState.Content)?.items ?: current.items
                val baseIds = base.mapTo(HashSet()) { it.id }
                val fresh = page.items.filterNot { it.id in baseIds }
                state = RequestsState.Content(base + fresh, page.nextMaxId, loadingMore = false)
                failedPagePosition = null
            } catch (e: ApiError) {
                failedPagePosition = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                (state as? RequestsState.Content)?.let { latest ->
                    state = latest.copy(loadingMore = false, loadMoreFailed = true)
                }
                scope.launch {
                    snackbarHostState.showSnackbar("Could not load older follow requests: the server returned error ${e.status}. Scroll or tap Retry to try again.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedPagePosition = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                (state as? RequestsState.Content)?.let { latest ->
                    state = latest.copy(loadingMore = false, loadMoreFailed = true)
                }
                scope.launch {
                    snackbarHostState.showSnackbar("Could not load older follow requests: ${e.message ?: "network error"}. Scroll or tap Retry to try again.")
                }
            }
        }
    }

    /**
     * Put a reverted account back at its original index. Handles the
     * case where the optimistic removal emptied the list (state flipped
     * to Empty) so a failed decision can never lose the row, and dedupes
     * by id in case a concurrent page load re-added the account.
     */
    fun restore(index: Int, account: Account, nextMaxId: String?) {
        when (val cur = state) {
            is RequestsState.Content -> {
                if (cur.items.none { it.id == account.id }) {
                    val restored = cur.items.toMutableList()
                    restored.add(index.coerceAtMost(restored.size), account)
                    state = cur.copy(items = restored)
                }
            }
            RequestsState.Empty ->
                state = RequestsState.Content(listOf(account), nextMaxId, loadingMore = false)
            else -> Unit
        }
    }

    /**
     * Optimistic decision: drop the row at once, call the server, and on
     * failure put the account back at its original index with a snackbar
     * naming the problem and the recovery. [onChanged] fires once the
     * decision has settled (success or revert) so the shell can refresh
     * badges against the server's truth, never against the optimistic row.
     */
    fun decide(account: Account, accept: Boolean) {
        val current = state as? RequestsState.Content ?: return
        val index = current.items.indexOfFirst { it.id == account.id }
        if (index < 0) return

        val remaining = current.items.toMutableList().apply { removeAt(index) }
        state = if (remaining.isEmpty()) RequestsState.Empty else current.copy(items = remaining)

        scope.launch {
            try {
                if (accept) FollowRequestsRepository.authorize(api, account.id)
                else FollowRequestsRepository.reject(api, account.id)
            } catch (e: ApiError) {
                restore(index, account, current.nextMaxId)
                snackbarHostState.showSnackbar(
                    "Could not ${if (accept) "accept" else "decline"} @${account.acct}: the server returned error ${e.status}. The request was restored; try again.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                restore(index, account, current.nextMaxId)
                snackbarHostState.showSnackbar(
                    "Could not ${if (accept) "accept" else "decline"} @${account.acct}: ${e.message ?: "network error"}. The request was restored; check your connection and try again.",
                )
            }
            onChanged()
        }
    }

    LaunchedEffect(api, session.id) { loadFirstPage() }

    // Infinite scroll: trigger one page ahead of the last visible item.
    // Only the scroll position and the paging flags drive re-evaluation, so
    // unrelated list edits cannot relaunch a failed page. After a failure a
    // real scroll (position change) or the inline Retry is required.
    LaunchedEffect(listState) {
        snapshotFlow {
            val position = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val paging = (state as? RequestsState.Content)?.let { it.loadingMore to it.loadMoreFailed }
            Triple(position, lastVisible, paging)
        }.collect { (position, lastVisible, _) ->
            val current = state as? RequestsState.Content ?: return@collect
            if (current.nextMaxId == null || current.loadingMore) return@collect
            if (current.loadMoreFailed && position == failedPagePosition) return@collect
            if (lastVisible >= current.items.size - 3) loadNextPage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val s = state) {
            RequestsState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading follow requests" },
                )
            }

            is RequestsState.Error -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { scope.launch { loadFirstPage() } },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Retry loading follow requests" },
                    ) {
                        Text("Retry")
                    }
                }
            }

            RequestsState.Empty -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "No pending follow requests. When someone asks to follow you, the request will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is RequestsState.Content -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(count = s.items.size, key = { s.items[it].id }) { index ->
                    RequestRow(
                        account = s.items[index],
                        onAccept = { decide(s.items[index], accept = true) },
                        onReject = { decide(s.items[index], accept = false) },
                    )
                    if (index < s.items.lastIndex) HorizontalDivider()
                }
                if (s.loadingMore) {
                    item(key = "loading-more") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.semantics { contentDescription = "Loading older follow requests" },
                            )
                        }
                    }
                } else if (s.loadMoreFailed) {
                    item(key = "load-more-error") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "Could not load older follow requests.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = { loadNextPage() },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics { contentDescription = "Retry loading older follow requests" },
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One requesting account: avatar, display name, @acct, plain-text note
 * excerpt, and Accept / Reject buttons (48dp minimum targets).
 */
@Composable
private fun RequestRow(
    account: Account,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val name = account.display_name?.takeIf { it.isNotBlank() } ?: "@${account.acct}"
    // K42: parse once per note through the shared cached extractor,
    // not per recomposition.
    val note = remember(account.note) { noteExcerpt(account.note) }
    val rowDescription = buildString {
        append("Follow request from $name, @${account.acct}")
        if (note.isNotEmpty()) append(". $note")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            // K43: one merged node describing the whole request row, so
            // screen readers get a single announcement instead of one per child.
            .semantics(mergeDescendants = true) { contentDescription = rowDescription },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = account.avatar_static ?: account.avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "@${account.acct}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (note.isNotEmpty()) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAccept,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Accept follow request from $name" },
            ) {
                Text("Accept")
            }
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Decline follow request from $name" },
            ) {
                Text("Decline")
            }
        }
    }
}

/** Plain-text note excerpt: tags stripped, entities decoded, collapsed. */
private fun noteExcerpt(html: String): String = StatusText.plain(html)
