package net.snakepit.sidewinder.ui.catchup

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.catchup.CatchUpRepository
import net.snakepit.sidewinder.core.catchup.DigestEntry
import net.snakepit.sidewinder.core.catchup.DigestFilter
import net.snakepit.sidewinder.core.catchup.DigestSort
import net.snakepit.sidewinder.core.catchup.applyDigestFilter
import net.snakepit.sidewinder.core.catchup.collapseBoosts
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.ui.app.LocalAppActions
import net.snakepit.sidewinder.ui.timeline.StatusCard
import net.snakepit.sidewinder.ui.timeline.TimelineStore

/** Range choices for the digest, port of the web view's RANGES. */
private val RANGES_HOURS = listOf(1, 6, 12, 24)

private fun rangeLabel(hours: Int): String = "last ${hours}h"

/** Four mandatory states for the digest load. */
private sealed interface DigestState {
    /** Loading with a running collected count for the progress copy. */
    data class Loading(val loaded: Int) : DigestState

    data class Error(val message: String) : DigestState

    /** Nothing in range; carries the range label for the empty copy. */
    data class Empty(val range: String) : DigestState

    /** All statuses collected (chronological), plus the range for the summary. */
    data class Content(val statuses: List<Status>, val range: String) : DigestState
}

/**
 * Catch-up digest over the home timeline. Port of web
 * `src/views/CatchUp.tsx` with the web fallback for the missing
 * read-mark: the user picks a range (1h / 6h / 12h / 24h) and the home
 * timeline is paged backwards until posts fall outside it, capped at
 * 800 posts over 20 pages. Boosts of the same original collapse into
 * one row; rows are read-only [StatusCard]s that tap through to the
 * thread via [LocalAppActions.openThread].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatchUpScreen(api: Api, session: Session) {
    val actions = LocalAppActions.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var hours by rememberSaveable { mutableStateOf(24) }
    var filter by rememberSaveable { mutableStateOf(DigestFilter.All) }
    var sort by rememberSaveable { mutableStateOf(DigestSort.Newest) }
    var state by remember { mutableStateOf<DigestState>(DigestState.Loading(0)) }
    var loadJob by remember { mutableStateOf<Job?>(null) }

    fun load() {
        // A range change while a walk is in flight abandons the old walk.
        loadJob?.cancel()
        state = DigestState.Loading(0)
        loadJob = scope.launch {
            state = try {
                val cutoff = CatchUpRepository.cutoffFor(hours)
                val statuses = CatchUpRepository.collect(api, cutoff) { loaded ->
                    state = DigestState.Loading(loaded)
                }
                if (statuses.isEmpty()) {
                    DigestState.Empty(rangeLabel(hours))
                } else {
                    DigestState.Content(statuses, rangeLabel(hours))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                DigestState.Error(
                    "Could not load the digest: the server returned error ${e.status}. Check your connection, then retry.",
                )
            } catch (e: Exception) {
                DigestState.Error(
                    "Could not load the digest: ${e.message ?: "network error"}. Check your connection, then retry.",
                )
            }
        }
    }

    fun notice(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(api, session.id, hours) { load() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            ) {
                Text(
                    text = "Catch up",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { load() },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Refresh digest" },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
            }

            RangeChipRow(hours = hours, onSelect = { hours = it })

            when (val s = state) {
                is DigestState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics { contentDescription = "Loading the digest" },
                        )
                        Text(
                            text = if (s.loaded > 0) "Loading ${s.loaded} posts" else "Loading posts",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is DigestState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { load() },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "Retry loading the digest" },
                        ) {
                            Text("Retry")
                        }
                    }
                }

                is DigestState.Empty -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("You're all caught up", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "No posts in the ${s.range}. Pick a longer range above, or check back later.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is DigestState.Content -> DigestContent(
                    api = api,
                    session = session,
                    statuses = s.statuses,
                    range = s.range,
                    filter = filter,
                    sort = sort,
                    selfAccountId = actions?.selfAccountId ?: "",
                    onFilter = { filter = it },
                    onSort = { sort = it },
                    onOpenThread = { id -> actions?.openThread?.invoke(id) },
                    onCompose = { replyToId -> actions?.openCompose?.invoke(replyToId) },
                    onStatusUpdated = { updated ->
                        // A favourite or boost made from the digest keeps
                        // the cached home timeline in sync.
                        TimelineStore.applyEdit(updated.reblog ?: updated)
                    },
                    onNotice = { notice(it) },
                )
            }
        }
    }
}

/** Range picker; rendered in every state so the range stays reachable. */
@Composable
private fun RangeChipRow(hours: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RANGES_HOURS.forEach { h ->
            FilterChip(
                selected = hours == h,
                onClick = { onSelect(h) },
                label = { Text("Last ${h}h") },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Show posts from the last $h hours" },
            )
        }
    }
}

@Composable
private fun DigestContent(
    api: Api,
    session: Session,
    statuses: List<Status>,
    range: String,
    filter: DigestFilter,
    sort: DigestSort,
    selfAccountId: String,
    onFilter: (DigestFilter) -> Unit,
    onSort: (DigestSort) -> Unit,
    onOpenThread: (String) -> Unit,
    onCompose: (String) -> Unit,
    onStatusUpdated: (Status) -> Unit,
    onNotice: (String) -> Unit,
) {
    val entries = remember(statuses) { collapseBoosts(statuses) }
    val visible = remember(entries, filter, sort, selfAccountId) {
        applyDigestFilter(entries, filter, sort, selfAccountId)
    }
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "summary") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = "${statuses.size} posts from the $range",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Read-only digest. Tap a post to open its thread.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "controls") {
            FilterAndSortRow(filter = filter, sort = sort, onFilter = onFilter, onSort = onSort)
        }
        if (visible.isEmpty()) {
            item(key = "no-filter-match") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Nothing in this filter. Try a different filter above.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            items(count = visible.size, key = { visible[it].status.id }) { index ->
                DigestRow(
                    api = api,
                    session = session,
                    entry = visible[index],
                    onOpenThread = onOpenThread,
                    onCompose = onCompose,
                    onStatusUpdated = onStatusUpdated,
                    onNotice = onNotice,
                )
                if (index < visible.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun FilterAndSortRow(
    filter: DigestFilter,
    sort: DigestSort,
    onFilter: (DigestFilter) -> Unit,
    onSort: (DigestSort) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DigestFilter.entries.forEach { f ->
            FilterChip(
                selected = filter == f,
                onClick = { onFilter(f) },
                label = { Text(f.label) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Show ${f.label.lowercase()} in the digest" },
            )
        }
        DigestSort.entries.forEach { s ->
            FilterChip(
                selected = sort == s,
                onClick = { onSort(s) },
                label = { Text(s.label) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Sort the digest by ${s.label.lowercase()}" },
            )
        }
    }
}

/**
 * One digest row: the boosters line (when boosts collapsed onto this
 * original) above a read-only [StatusCard] of the original status.
 * Tapping the card opens the thread.
 */
@Composable
private fun DigestRow(
    api: Api,
    session: Session,
    entry: DigestEntry,
    onOpenThread: (String) -> Unit,
    onCompose: (String) -> Unit,
    onStatusUpdated: (Status) -> Unit,
    onNotice: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (entry.boosters.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp)
                    .semantics { contentDescription = "Boosted by ${boosterNames(entry)}" },
            ) {
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${boosterNames(entry)} boosted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StatusCard(
            status = entry.status,
            api = api,
            baseUrl = session.base,
            onStatusUpdated = onStatusUpdated,
            onNotice = onNotice,
            onOpenThread = onOpenThread,
            onCompose = onCompose,
        )
    }
}

/** "A", "A and B", or "A, B and 2 others", newest booster first. */
private fun boosterNames(entry: DigestEntry): String {
    val names = entry.boosters.map { it.display_name?.takeIf { name -> name.isNotBlank() } ?: it.username }
    return when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> "${names[0]}, ${names[1]} and ${names.size - 2} " +
            if (names.size == 3) "other" else "others"
    }
}
