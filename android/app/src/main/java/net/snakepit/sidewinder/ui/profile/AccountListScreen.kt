package net.snakepit.sidewinder.ui.profile

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.profile.ProfileRepository

private sealed interface AccountListState {
    /** First page still loading. */
    data object Loading : AccountListState

    /** First page failed; nothing to show. */
    data class Error(val message: String) : AccountListState

    /** First page loaded with zero items. */
    data object Empty : AccountListState

    /** At least one page loaded. */
    data class Content(
        val items: List<Account>,
        val nextMaxId: String?,
        val loadingMore: Boolean,
    ) : AccountListState
}

/**
 * An account's followers or the accounts it follows. Port of the web
 * account-list views: paged avatar/name/acct rows, tap opens that
 * account's profile, four mandatory states, timeline-style pagination.
 * [kind] is "followers" or "following".
 */
@Composable
fun AccountListScreen(
    api: Api,
    session: Session,
    accountId: String,
    kind: String,
    onOpenProfile: (String) -> Unit,
) {
    var state by remember { mutableStateOf<AccountListState>(AccountListState.Loading) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // A caller passing an unexpected kind would 404; clamp to the two
    // routes MainActivity wires so the fetch never hits a bogus path.
    val safeKind = if (kind == "following") "following" else "followers"

    suspend fun loadFirstPage() {
        state = AccountListState.Loading
        state = try {
            val page = ProfileRepository.accountList(api, accountId, safeKind)
            val fresh = page.items.distinctBy { it.id }
            if (fresh.isEmpty()) AccountListState.Empty else AccountListState.Content(fresh, page.nextMaxId, loadingMore = false)
        } catch (e: ApiError) {
            AccountListState.Error("Could not load this list: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: Exception) {
            AccountListState.Error("Could not load this list: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    fun showNotice(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun loadNextPage() {
        val current = state as? AccountListState.Content ?: return
        val maxId = current.nextMaxId ?: return // end reached
        if (current.loadingMore) return

        state = current.copy(loadingMore = true)
        scope.launch {
            try {
                val page = ProfileRepository.accountList(api, accountId, safeKind, maxId)
                val base = (state as? AccountListState.Content)?.items ?: current.items
                val baseIds = base.mapTo(HashSet()) { it.id }
                val fresh = page.items.filterNot { it.id in baseIds }
                state = AccountListState.Content(base + fresh, page.nextMaxId, loadingMore = false)
            } catch (e: ApiError) {
                (state as? AccountListState.Content)?.let { latest -> state = latest.copy(loadingMore = false) }
                showNotice("Could not load more accounts: the server returned error ${e.status}. Scroll to try again.")
            } catch (e: Exception) {
                (state as? AccountListState.Content)?.let { latest -> state = latest.copy(loadingMore = false) }
                showNotice("Could not load more accounts: ${e.message ?: "network error"}. Scroll to try again.")
            }
        }
    }

    LaunchedEffect(api, accountId, safeKind) { loadFirstPage() }

    // Infinite scroll: same guarded snapshotFlow pattern as the timeline.
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to state
        }.collect { (lastVisible, snapshot) ->
            val current = snapshot as? AccountListState.Content ?: return@collect
            if (current.nextMaxId == null || current.loadingMore) return@collect
            if (lastVisible >= current.items.size - 3) loadNextPage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val s = state) {
            AccountListState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading the account list" },
                )
            }

            is AccountListState.Error -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { scope.launch { loadFirstPage() } },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Retry loading the account list" },
                    ) {
                        Text("Retry")
                    }
                }
            }

            AccountListState.Empty -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (safeKind == "following") {
                        "This account does not follow anyone yet."
                    } else {
                        "Nobody follows this account yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is AccountListState.Content -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(count = s.items.size, key = { s.items[it].id }) { index ->
                    val account = s.items[index]
                    val name = account.display_name?.takeIf { it.isNotBlank() } ?: account.username
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(
                                onClickLabel = "Open the profile of $name",
                            ) { onOpenProfile(account.id) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = account.avatar_static ?: account.avatar,
                            contentDescription = "Avatar of $name",
                            modifier = Modifier.size(40.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                        Column {
                            Text(text = name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "@${account.acct}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (index < s.items.lastIndex) HorizontalDivider()
                }
                if (s.loadingMore) {
                    item(key = "loading-more") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.semantics { contentDescription = "Loading more accounts" },
                            )
                        }
                    }
                }
            }
        }
    }
}
