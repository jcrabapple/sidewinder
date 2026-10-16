package net.snakepit.sidewinder.ui.lists

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
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.lists.ListMembersLogic
import net.snakepit.sidewinder.core.lists.ListsRepository
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.search.SearchRepository

private sealed interface MembersState {
    /** First page still loading. */
    data object Loading : MembersState

    /** First page failed; nothing to show. */
    data class Error(val message: String) : MembersState

    /** First page loaded with zero members. */
    data object Empty : MembersState

    /** At least one member page loaded. */
    data class Content(
        val members: List<Account>,
        val nextMaxId: String?,
        val loadingMore: Boolean,
    ) : MembersState
}

private sealed interface SearchState {
    /** No query typed yet, or the query is too short. */
    data object Idle : SearchState

    /** Query typed; debounce running or request in flight. */
    data object Loading : SearchState

    /** Request failed; retry by editing the query. */
    data class Error(val message: String) : SearchState

    /** Results in hand, possibly zero matches. */
    data class Results(val accounts: List<Account>) : SearchState
}

/**
 * Members of one list. Port of the web list-detail view: paged member
 * rows with optimistic Remove (revert + snackbar on failure), and a
 * debounced account search that adds members, skipping anyone already
 * on the list. The four mandatory states wrap the member list; the
 * search panel carries its own idle/loading/error/results state.
 */
@Composable
fun ListMembersScreen(
    api: Api,
    session: Session,
    listId: String,
    listTitle: String,
    onOpenTimeline: () -> Unit = {},
) {
    var state by remember { mutableStateOf<MembersState>(MembersState.Loading) }
    var search by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Failed load-more pages back off exponentially, so a persistent
    // failure at the tail cannot retry in a tight infinite loop.
    var pageFailures by remember { mutableStateOf(0) }
    var nextRetryAtMs by remember { mutableStateOf(0L) }

    // Every id on the list right now, including optimistic removals and
    // additions, so Add buttons and the search panel stay consistent.
    val memberIds = remember(state) {
        (state as? MembersState.Content)?.members?.mapTo(HashSet()) { it.id } ?: HashSet()
    }

    suspend fun loadFirstPage() {
        state = MembersState.Loading
        state = try {
            val page = ListsRepository.members(api, listId)
            val fresh = page.items.distinctBy { it.id }
            if (fresh.isEmpty()) MembersState.Empty
            else MembersState.Content(fresh, page.nextMaxId, loadingMore = false)
        } catch (e: ApiError) {
            MembersState.Error("Could not load members of \"$listTitle\": the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MembersState.Error("Could not load members of \"$listTitle\": ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    // One load-more at a time; re-checked here so a second trigger
    // cannot double-fetch the same page.
    fun loadNextPage() {
        val current = state as? MembersState.Content ?: return
        val maxId = current.nextMaxId ?: return
        if (current.loadingMore) return

        state = current.copy(loadingMore = true)
        scope.launch {
            try {
                val page = ListsRepository.members(api, listId, maxId)
                val base = (state as? MembersState.Content)?.members ?: current.members
                val baseIds = base.mapTo(HashSet()) { it.id }
                val fresh = page.items.filterNot { it.id in baseIds }
                state = MembersState.Content(base + fresh, page.nextMaxId, loadingMore = false)
                pageFailures = 0
                nextRetryAtMs = 0L
            } catch (e: ApiError) {
                pageFailures++
                nextRetryAtMs = System.currentTimeMillis() + ListMembersLogic.PageRetry.delayMs(pageFailures)
                (state as? MembersState.Content)?.let { latest -> state = latest.copy(loadingMore = false) }
                snackbarHostState.showSnackbar("Could not load older members: the server returned error ${e.status}. Scroll to try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pageFailures++
                nextRetryAtMs = System.currentTimeMillis() + ListMembersLogic.PageRetry.delayMs(pageFailures)
                (state as? MembersState.Content)?.let { latest -> state = latest.copy(loadingMore = false) }
                snackbarHostState.showSnackbar("Could not load older members: ${e.message ?: "network error"}. Scroll to try again.")
            }
        }
    }

    fun restore(index: Int, account: Account, nextMaxId: String?) {
        when (val cur = state) {
            is MembersState.Content -> {
                if (cur.members.none { it.id == account.id }) {
                    val restored = cur.members.toMutableList()
                    restored.add(index.coerceAtMost(restored.size), account)
                    state = cur.copy(members = restored)
                }
            }
            MembersState.Empty ->
                state = MembersState.Content(listOf(account), nextMaxId, loadingMore = false)
            else -> Unit
        }
    }

    fun remove(account: Account) {
        val current = state as? MembersState.Content ?: return
        val index = current.members.indexOfFirst { it.id == account.id }
        if (index < 0) return

        val remaining = current.members.toMutableList().apply { removeAt(index) }
        state = if (remaining.isEmpty()) MembersState.Empty else current.copy(members = remaining)

        scope.launch {
            try {
                ListsRepository.removeMembers(api, listId, listOf(account.id))
            } catch (e: ApiError) {
                restore(index, account, current.nextMaxId)
                snackbarHostState.showSnackbar(
                    "Could not remove @${account.acct} from \"$listTitle\": the server returned error ${e.status}. The member was restored; try again.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                restore(index, account, current.nextMaxId)
                snackbarHostState.showSnackbar(
                    "Could not remove @${account.acct} from \"$listTitle\": ${e.message ?: "network error"}. The member was restored; check your connection and try again.",
                )
            }
        }
    }

    fun add(account: Account) {
        // Guard against duplicates with the ids live at click time, not
        // the set captured when the row was composed.
        val idsNow = (state as? MembersState.Content)
            ?.members?.let { ListMembersLogic.memberIds(it) } ?: emptySet()
        if (account.id in idsNow) return
        scope.launch {
            try {
                ListsRepository.addMembers(api, listId, listOf(account.id))
            } catch (e: ApiError) {
                // GTS 404s list-add for accounts you do not follow.
                val reason = if (e.status == 404) {
                    "you can only add accounts you follow. Follow @${account.acct} first, then add them."
                } else {
                    "the server returned error ${e.status}. Try again."
                }
                snackbarHostState.showSnackbar(
                    "Could not add @${account.acct} to \"$listTitle\": $reason",
                )
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    "Could not add @${account.acct} to \"$listTitle\": ${e.message ?: "network error"}. Check your connection and try again.",
                )
                return@launch
            }
            snackbarHostState.showSnackbar("Added @${account.acct} to \"$listTitle\".")
            when (val cur = state) {
                is MembersState.Content ->
                    if (cur.members.none { it.id == account.id }) {
                        state = cur.copy(members = cur.members + account)
                    }
                MembersState.Empty -> state = MembersState.Content(listOf(account), null, loadingMore = false)
                // The first page is still loading (or failed): the add
                // succeeded server-side, so reload rather than losing
                // the member from the UI.
                else -> loadFirstPage()
            }
            // A freshly added account should not linger as a search hit.
            when (val cur = search) {
                is SearchState.Results ->
                    search = SearchState.Results(cur.accounts.filterNot { it.id == account.id })
                else -> Unit
            }
        }
    }

    LaunchedEffect(api, session.id, listId) { loadFirstPage() }

    // Infinite scroll, one page ahead of the last visible member.
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to state
        }.collect { (lastVisible, snapshot) ->
            val current = snapshot as? MembersState.Content ?: return@collect
            if (current.nextMaxId == null || current.loadingMore) return@collect
            if (System.currentTimeMillis() < nextRetryAtMs) return@collect
            if (lastVisible >= current.members.size - 3) loadNextPage()
        }
    }

    // Debounced account search for the add-member panel.
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            search = SearchState.Idle
            return@LaunchedEffect
        }
        search = SearchState.Loading
        delay(300)
        try {
            val results = SearchRepository.search(api, trimmed, "accounts").accounts
            // Filter with the member ids current at apply time: members
            // may have been added or removed while the search was in
            // flight, and a composition-time set would go stale.
            val idsNow = (state as? MembersState.Content)
                ?.members?.let { ListMembersLogic.memberIds(it) } ?: emptySet()
            search = SearchState.Results(ListMembersLogic.hideExistingMembers(results, idsNow))
        } catch (e: ApiError) {
            search = SearchState.Error("Search failed: the server returned error ${e.status}. Edit the query to retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            search = SearchState.Error("Search failed: ${e.message ?: "network error"}. Edit the query to retry.")
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        listTitle,
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onOpenTimeline,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.Notes,
                            contentDescription = "Open the $listTitle timeline",
                        )
                    }
                }
            }
            item(key = "add-member") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text("Add member") },
                        placeholder = { Text("Search accounts by name or @handle") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Search accounts to add to $listTitle" },
                    )
                    when (val s = search) {
                        SearchState.Idle -> Text(
                            text = "Type at least two characters to search the accounts you follow and know.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SearchState.Loading -> Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(20.dp)
                                    .semantics { contentDescription = "Searching accounts" },
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = "Searching accounts",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is SearchState.Error -> Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        is SearchState.Results -> if (s.accounts.isEmpty()) {
                            Text(
                                text = "No new accounts match. Anyone already on this list is hidden from these results.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                s.accounts.forEach { account ->
                                    SearchResultRow(
                                        account = account,
                                        onAdd = { add(account) },
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

            when (val s = state) {
                MembersState.Loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics { contentDescription = "Loading members of $listTitle" },
                        )
                    }
                }
                is MembersState.Error -> item(key = "error") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { scope.launch { loadFirstPage() } },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "Retry loading members" },
                        ) {
                            Text("Retry")
                        }
                    }
                }
                MembersState.Empty -> item(key = "empty") {
                    Text(
                        text = "No members yet. Use the search field above to add accounts to \"$listTitle\".",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
                is MembersState.Content -> {
                    items(count = s.members.size, key = { "member-${s.members[it].id}" }) { index ->
                        val account = s.members[index]
                        MemberRow(
                            account = account,
                            onRemove = { remove(account) },
                        )
                        if (index < s.members.lastIndex) HorizontalDivider()
                    }
                    if (s.loadingMore) {
                        item(key = "loading-more") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    modifier = Modifier.semantics { contentDescription = "Loading older members" },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One member row: avatar, display name, @acct, and a Remove button
 * (48dp minimum target).
 */
@Composable
private fun MemberRow(
    account: Account,
    onRemove: () -> Unit,
) {
    val name = account.display_name?.takeIf { it.isNotBlank() } ?: "@${account.acct}"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Member $name, @${account.acct}"
            },
    ) {
        AsyncImage(
            model = account.avatar_static ?: account.avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.small),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
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
        OutlinedButton(
            onClick = onRemove,
            modifier = Modifier.semantics { contentDescription = "Remove $name from the list" },
        ) {
            Text("Remove")
        }
    }
}

/**
 * One search hit: avatar, display name, @acct, and an Add button that
 * is only rendered for accounts not already on the list.
 */
@Composable
private fun SearchResultRow(
    account: Account,
    onAdd: () -> Unit,
) {
    val name = account.display_name?.takeIf { it.isNotBlank() } ?: "@${account.acct}"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Search result $name, @${account.acct}"
            },
    ) {
        AsyncImage(
            model = account.avatar_static ?: account.avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(36.dp)
                .clip(MaterialTheme.shapes.small),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
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
        Button(
            onClick = onAdd,
            modifier = Modifier.semantics { contentDescription = "Add $name to the list" },
        ) {
            Text("Add")
        }
    }
}
