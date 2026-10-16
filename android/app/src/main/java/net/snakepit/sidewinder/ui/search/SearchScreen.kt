@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.snakepit.sidewinder.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.search.SearchHashtag
import net.snakepit.sidewinder.core.search.SearchRepository
import net.snakepit.sidewinder.core.search.recentUses
import net.snakepit.sidewinder.ui.app.LocalAppActions
import net.snakepit.sidewinder.ui.timeline.StatusCard
import net.snakepit.sidewinder.ui.trends.TrendsColumn

/**
 * Search over posts, people, and hashtags via `GET /api/v2/search`.
 * One debounced query drives all three tabs; each tab keeps its own
 * four-state machine (idle / loading / error / empty-or-content).
 * Statuses paginate by `offset`; accounts and hashtags are first page
 * only per the milestone contract.
 */
private const val DEBOUNCE_MS = 350L
private const val MIN_QUERY_LENGTH = 2

private val TAB_TYPES = listOf("statuses", "accounts", "hashtags")
private val TAB_LABELS = listOf("Posts", "People", "Hashtags", "Trends")

/** Four mandatory states, held per tab. */
private sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Error(val message: String) : SearchState
    data object Empty : SearchState
    data object Content : SearchState
}

// StatusResults now lives in SearchMerge.kt (internal) so the pure
// helpers and their tests share one definition.

private data class AccountResults(val items: List<Account>)
private data class HashtagResults(val items: List<SearchHashtag>)

@Composable
fun SearchScreen(
    api: Api,
    session: Session,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenTag: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    var statusState by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    var statusResults by remember { mutableStateOf<StatusResults?>(null) }
    var accountState by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    var accountResults by remember { mutableStateOf<AccountResults?>(null) }
    var hashtagState by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    var hashtagResults by remember { mutableStateOf<HashtagResults?>(null) }

    // Last query each tab actually searched for; a tab switch back to an
    // already-searched query reuses the held results instead of refetching.
    var statusQuery by remember { mutableStateOf<String?>(null) }
    var accountQuery by remember { mutableStateOf<String?>(null) }
    var hashtagQuery by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    fun showNotice(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun updateStatus(updated: Status) {
        val current = statusResults ?: return
        statusResults = current.copy(items = current.items.map { if (it.id == updated.id) updated else it })
    }

    /**
     * Runs a first-page search for [q] on [type]. Results are dropped if
     * the query or tab changed while the request was in flight, so a
     * fast typist never sees stale rows.
     */
    fun performSearch(type: String, q: String) {
        val isCurrent: () -> Boolean = {
            query.trim() == q && TAB_TYPES.getOrNull(tab) == type
        }
        when (type) {
            "statuses" -> scope.launch {
                statusState = SearchState.Loading
                statusResults = null
                try {
                    val results = SearchRepository.search(api, q, type = "statuses")
                    if (!isCurrent()) return@launch
                    statusResults = StatusResults(
                        items = SearchMerge.deduped(results.statuses),
                        nextOffset = if (results.statuses.size < SearchRepository.PAGE_LIMIT) null else SearchRepository.PAGE_LIMIT,
                    )
                    statusQuery = q
                    statusState = if (results.statuses.isEmpty()) SearchState.Empty else SearchState.Content
                } catch (e: ApiError) {
                    if (!isCurrent()) return@launch
                    statusState = SearchState.Error("Search failed: the server returned error ${e.status}. Check your connection, then retry.")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isCurrent()) return@launch
                    statusState = SearchState.Error("Search failed: ${e.message ?: "network error"}. Check your connection, then retry.")
                }
            }
            "accounts" -> scope.launch {
                accountState = SearchState.Loading
                accountResults = null
                try {
                    val results = SearchRepository.search(api, q, type = "accounts")
                    if (!isCurrent()) return@launch
                    accountResults = AccountResults(results.accounts.distinctBy { it.id })
                    accountQuery = q
                    accountState = if (results.accounts.isEmpty()) SearchState.Empty else SearchState.Content
                } catch (e: ApiError) {
                    if (!isCurrent()) return@launch
                    accountState = SearchState.Error("Search failed: the server returned error ${e.status}. Check your connection, then retry.")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isCurrent()) return@launch
                    accountState = SearchState.Error("Search failed: ${e.message ?: "network error"}. Check your connection, then retry.")
                }
            }
            else -> scope.launch {
                hashtagState = SearchState.Loading
                hashtagResults = null
                try {
                    val results = SearchRepository.search(api, q, type = "hashtags")
                    if (!isCurrent()) return@launch
                    hashtagResults = HashtagResults(results.hashtags.distinctBy { it.name })
                    hashtagQuery = q
                    hashtagState = if (results.hashtags.isEmpty()) SearchState.Empty else SearchState.Content
                } catch (e: ApiError) {
                    if (!isCurrent()) return@launch
                    hashtagState = SearchState.Error("Search failed: the server returned error ${e.status}. Check your connection, then retry.")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isCurrent()) return@launch
                    hashtagState = SearchState.Error("Search failed: ${e.message ?: "network error"}. Check your connection, then retry.")
                }
            }
        }
    }

    // Debounce: wait for the query to settle, then search the active tab.
    // Switching tabs searches too (unless that tab already has results for
    // this exact query). Short or empty queries reset every tab to Idle
    // and never hit the network.
    LaunchedEffect(query, tab) {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) {
            statusState = SearchState.Idle; statusResults = null; statusQuery = null
            accountState = SearchState.Idle; accountResults = null; accountQuery = null
            hashtagState = SearchState.Idle; hashtagResults = null; hashtagQuery = null
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        if (query.trim() != trimmed) return@LaunchedEffect // still typing
        val type = TAB_TYPES.getOrNull(tab) ?: return@LaunchedEffect // Trends tab: no search
        // Only settled results count as "already searched": a Loading
        // state means a previous attempt was dropped (tab switched away
        // mid-flight), so the tab must refetch when revisited.
        val alreadySearched = when (type) {
            "statuses" -> statusQuery == trimmed &&
                (statusState == SearchState.Content || statusState == SearchState.Empty)
            "accounts" -> accountQuery == trimmed &&
                (accountState == SearchState.Content || accountState == SearchState.Empty)
            else -> hashtagQuery == trimmed &&
                (hashtagState == SearchState.Content || hashtagState == SearchState.Empty)
        }
        if (!alreadySearched) performSearch(type, trimmed)
    }

    // Offset-based load-more for the statuses tab, same near-end trigger
    // as the timeline. Guarded so one page loads at a time and a query
    // change mid-flight discards the result.
    LaunchedEffect(Unit) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                val current = statusResults ?: return@collect
                val nextOffset = current.nextOffset ?: return@collect
                if (current.loadingMore) return@collect
                if (lastVisible < current.items.size - 3) return@collect
                val q = query.trim()
                if (q.length < MIN_QUERY_LENGTH) return@collect

                statusResults = current.copy(loadingMore = true)
                scope.launch {
                    try {
                        val results = SearchRepository.search(api, q, type = "statuses", offset = nextOffset)
                        if (query.trim() != q) return@launch
                        val live = statusResults ?: return@launch
                        statusResults = StatusResults(
                            items = SearchMerge.append(live.items, results.statuses),
                            nextOffset = if (results.statuses.size < SearchRepository.PAGE_LIMIT) null else nextOffset + SearchRepository.PAGE_LIMIT,
                            loadingMore = false,
                        )
                    } catch (e: ApiError) {
                        if (query.trim() == q) {
                            // K46: keep the LIVE state (concurrent favourites
                            // survive), only clear the in-flight flag.
                            statusResults = SearchMerge.failureState(current, statusResults)
                            showNotice("Could not load more posts: the server returned error ${e.status}. Scroll to try again.")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (query.trim() == q) {
                            statusResults = SearchMerge.failureState(current, statusResults)
                            showNotice("Could not load more posts: ${e.message ?: "network error"}. Scroll to try again.")
                        }
                    }
                }
            }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Search posts, people, and hashtags" },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null) // decorative; the field is labelled
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { query = "" },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "Clear search" },
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                },
                label = { Text("Search") },
                supportingText = { Text("Type at least 2 characters") },
            )

            PrimaryTabRow(selectedTabIndex = tab) {
                TAB_LABELS.forEachIndexed { index, label ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            val retry: () -> Unit = {
                val trimmed = query.trim()
                if (trimmed.length >= MIN_QUERY_LENGTH) TAB_TYPES.getOrNull(tab)?.let { performSearch(it, trimmed) }
            }

            when (tab) {
                0 -> StatusesTab(
                    state = statusState,
                    results = statusResults,
                    api = api,
                    session = session,
                    listState = listState,
                    onStatusUpdated = ::updateStatus,
                    onNotice = ::showNotice,
                    onOpenThread = onOpenThread,
                    onRetry = retry,
                )
                1 -> AccountsTab(
                    state = accountState,
                    results = accountResults,
                    onOpenProfile = onOpenProfile,
                    onRetry = retry,
                )
                2 -> HashtagsTab(
                    state = hashtagState,
                    results = hashtagResults,
                    onOpenTag = onOpenTag,
                    onRetry = retry,
                )
                else -> {
                    val actions = LocalAppActions.current
                    val uriHandler = LocalUriHandler.current
                    TrendsColumn(
                        api = api,
                        session = session,
                        onOpenTag = onOpenTag,
                        onOpenUrl = { url -> if (actions != null) actions.openUrl(url) else uriHandler.openUri(url) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusesTab(
    state: SearchState,
    results: StatusResults?,
    api: Api,
    session: Session,
    listState: LazyListState,
    onStatusUpdated: (Status) -> Unit,
    onNotice: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        SearchState.Idle -> CenteredText("Search results appear here. Type at least 2 characters above.")
        SearchState.Loading -> CenteredSpinner("Searching posts")
        is SearchState.Error -> ErrorPanel(state.message, onRetry)
        SearchState.Empty -> CenteredText("No posts matched this search. Try different words or check the spelling.")
        SearchState.Content -> {
            val items = results?.items ?: return
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, status ->
                    StatusCard(
                        status = status,
                        api = api,
                        baseUrl = session.base,
                        onStatusUpdated = onStatusUpdated,
                        onNotice = onNotice,
                        onOpenThread = onOpenThread,
                        // The composer is not wired from search; the card's
                        // reply icon surfaces a notice instead.
                        onCompose = { onNotice("Replying is not available from search. Open the post and reply there.") },
                    )
                    if (index < items.lastIndex) HorizontalDivider()
                }
                if (results.loadingMore) {
                    item(key = "loading-more") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.semantics { contentDescription = "Loading more posts" },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountsTab(
    state: SearchState,
    results: AccountResults?,
    onOpenProfile: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        SearchState.Idle -> CenteredText("Matching accounts appear here. Type at least 2 characters above.")
        SearchState.Loading -> CenteredSpinner("Searching accounts")
        is SearchState.Error -> ErrorPanel(state.message, onRetry)
        SearchState.Empty -> CenteredText("No accounts matched this search. Try the full handle, for example someone@example.social.")
        SearchState.Content -> {
            val items = results?.items ?: return
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, account ->
                    AccountRow(account = account, onOpenProfile = onOpenProfile)
                    if (index < items.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HashtagsTab(
    state: SearchState,
    results: HashtagResults?,
    onOpenTag: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        SearchState.Idle -> CenteredText("Matching hashtags appear here. Type at least 2 characters above.")
        SearchState.Loading -> CenteredSpinner("Searching hashtags")
        is SearchState.Error -> ErrorPanel(state.message, onRetry)
        SearchState.Empty -> CenteredText("No hashtags matched this search. Try a shorter or broader term.")
        SearchState.Content -> {
            val items = results?.items ?: return
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { _, item -> item.name }) { index, hashtag ->
                    HashtagRow(hashtag = hashtag, onOpenTag = onOpenTag)
                    if (index < items.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

/** Avatar + display name + @acct row; whole row opens the profile. */
@Composable
private fun AccountRow(account: Account, onOpenProfile: (String) -> Unit) {
    val name = account.display_name?.takeIf { it.isNotBlank() } ?: account.username
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // K47: label the click itself; no extra semantics node.
            .clickable(
                onClickLabel = "Open profile for $name, @${account.acct}",
            ) { onOpenProfile(account.id) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            model = account.avatar_static ?: account.avatar,
            contentDescription = null, // decorative; the row text carries the identity
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "@${account.acct}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "#name" + recent-use count row; whole row opens the tag timeline. */
@Composable
private fun HashtagRow(hashtag: SearchHashtag, onOpenTag: (String) -> Unit) {
    val uses = hashtag.recentUses()
    val detail = if (uses > 0) {
        "$uses ${if (uses == 1L) "post" else "posts"} recently"
    } else {
        "No recent posts"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // K47: label the click itself; no extra semantics node.
            .clickable(
                onClickLabel = "Open hashtag ${hashtag.name}, $detail",
            ) { onOpenTag(hashtag.name) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Tag,
            contentDescription = null, // decorative; the row text carries the meaning
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("#${hashtag.name}", style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CenteredText(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CenteredSpinner(description: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = description })
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Retry the search" },
            ) {
                Text("Retry")
            }
        }
    }
}
