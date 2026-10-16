package net.snakepit.sidewinder.ui.mutes

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import net.snakepit.sidewinder.core.mute.MuteRepository
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError

private sealed interface MutesState {
    /** First page still loading. */
    data object Loading : MutesState

    /** First page failed; nothing to show. */
    data class Error(val message: String) : MutesState

    /** First page loaded with zero items. */
    data object Empty : MutesState

    /** At least one page loaded. */
    data class Content(
        val items: List<Account>,
        val nextMaxId: String?,
        val loadingMore: Boolean,
    ) : MutesState
}

/**
 * Muted accounts. Port of web `src/views/Mutes.tsx`: paged through
 * [MuteRepository.mutes] with Link-header max_id paging, infinite scroll
 * near the list end, and the four mandatory states. No Scaffold top bar;
 * the shell provides it.
 *
 * Unmute is optimistic: the row disappears immediately and is restored
 * with an explanatory snackbar if the server call fails. Tapping a row
 * opens the account's profile.
 */
@Composable
fun MutesScreen(
    api: Api,
    session: Session,
    onOpenProfile: (String) -> Unit,
) {
    var state by remember { mutableStateOf<MutesState>(MutesState.Loading) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    suspend fun loadFirstPage() {
        state = MutesState.Loading
        state = try {
            val page = MuteRepository.mutes(api, session.token)
            val fresh = page.items.distinctBy { it.id }
            if (fresh.isEmpty()) MutesState.Empty
            else MutesState.Content(fresh, page.nextMaxId, loadingMore = false)
        } catch (e: ApiError) {
            MutesState.Error("Could not load muted accounts: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MutesState.Error("Could not load muted accounts: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    // One load-more at a time; the guard is re-checked here so a second
    // trigger cannot double-fetch the same page.
    fun loadNextPage() {
        val current = state as? MutesState.Content ?: return
        val maxId = current.nextMaxId ?: return // end reached
        if (current.loadingMore) return

        state = current.copy(loadingMore = true)
        scope.launch {
            try {
                val page = MuteRepository.mutes(api, session.token, maxId)
                val base = (state as? MutesState.Content)?.items ?: current.items
                val baseIds = base.mapTo(HashSet()) { it.id }
                val fresh = page.items.filterNot { it.id in baseIds }
                state = MutesState.Content(base + fresh, page.nextMaxId, loadingMore = false)
            } catch (e: ApiError) {
                (state as? MutesState.Content)?.let { latest -> state = latest.copy(loadingMore = false) }
                scope.launch {
                    snackbarHostState.showSnackbar("Could not load older muted accounts: the server returned error ${e.status}. Scroll to try again.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (state as? MutesState.Content)?.let { latest -> state = latest.copy(loadingMore = false) }
                scope.launch {
                    snackbarHostState.showSnackbar("Could not load older muted accounts: ${e.message ?: "network error"}. Scroll to try again.")
                }
            }
        }
    }

    /**
     * Put a reverted account back at its original index. Handles the case
     * where the optimistic removal emptied the list (state flipped to
     * Empty) so a failed unmute can never lose the row, and dedupes by id
     * in case a concurrent page load re-added the account.
     */
    fun restore(index: Int, account: Account, nextMaxId: String?) {
        when (val cur = state) {
            is MutesState.Content -> {
                if (cur.items.none { it.id == account.id }) {
                    val restored = cur.items.toMutableList()
                    restored.add(index.coerceAtMost(restored.size), account)
                    state = cur.copy(items = restored)
                }
            }
            MutesState.Empty ->
                state = MutesState.Content(listOf(account), nextMaxId, loadingMore = false)
            else -> Unit
        }
    }

    /**
     * Optimistic unmute: drop the row at once, call the server, and on
     * failure put the account back at its original index with a snackbar
     * naming the problem and the recovery.
     */
    fun unmute(account: Account) {
        val current = state as? MutesState.Content ?: return
        val index = current.items.indexOfFirst { it.id == account.id }
        if (index < 0) return

        val remaining = current.items.toMutableList().apply { removeAt(index) }
        state = if (remaining.isEmpty()) MutesState.Empty else current.copy(items = remaining)

        scope.launch {
            try {
                MuteRepository.unmute(api, session.token, account.id)
            } catch (e: ApiError) {
                restore(index, account, current.nextMaxId)
                snackbarHostState.showSnackbar(
                    "Could not unmute @${account.acct}: the server returned error ${e.status}. The account was restored to the list; try again.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                restore(index, account, current.nextMaxId)
                snackbarHostState.showSnackbar(
                    "Could not unmute @${account.acct}: ${e.message ?: "network error"}. The account was restored to the list; check your connection and try again.",
                )
            }
        }
    }

    LaunchedEffect(api, session.id) { loadFirstPage() }

    // Infinite scroll: trigger one page ahead of the last visible item.
    // Both the last visible index and the state are read inside the
    // snapshotFlow so a fresh page (or end of list) re-evaluates, and
    // loadNextPage re-checks the guards so this cannot double-fetch.
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to state
        }.collect { (lastVisible, snapshot) ->
            val current = snapshot as? MutesState.Content ?: return@collect
            if (current.nextMaxId == null || current.loadingMore) return@collect
            if (lastVisible >= current.items.size - 3) loadNextPage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            MutesState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading muted accounts" },
                )
            }

            is MutesState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { scope.launch { loadFirstPage() } },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Retry loading muted accounts" },
                    ) {
                        Text("Retry")
                    }
                }
            }

            MutesState.Empty -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "No muted accounts. Mute someone from their profile.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is MutesState.Content -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(count = s.items.size, key = { s.items[it].id }) { index ->
                    MuteRow(
                        account = s.items[index],
                        onOpenProfile = onOpenProfile,
                        onUnmute = { unmute(s.items[index]) },
                    )
                    if (index < s.items.lastIndex) HorizontalDivider()
                }
                if (s.loadingMore) {
                    item(key = "loading-more") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.semantics { contentDescription = "Loading older muted accounts" },
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * One muted account: avatar, display name, @acct. The whole row opens the
 * profile; the trailing Unmute button is a separate 48dp target.
 */
@Composable
private fun MuteRow(
    account: Account,
    onOpenProfile: (String) -> Unit,
    onUnmute: () -> Unit,
) {
    val name = account.display_name?.takeIf { it.isNotBlank() } ?: "@${account.acct}"
    val rowDescription = "Open profile for $name, @${account.acct}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = { onOpenProfile(account.id) })
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = rowDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = account.avatar_static ?: account.avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "@${account.acct}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = onUnmute,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Unmute $name, @${account.acct}" },
        ) {
            Text("Unmute")
        }
    }
}
