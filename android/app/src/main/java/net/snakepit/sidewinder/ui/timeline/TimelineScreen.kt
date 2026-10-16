package net.snakepit.sidewinder.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.timeline.TimelineRepository
import net.snakepit.sidewinder.core.timeline.TimelineSource
import net.snakepit.sidewinder.core.timeline.label
import net.snakepit.sidewinder.ui.app.LocalAppSettings
import net.snakepit.sidewinder.ui.theme.LocalReduceMotion

/** Show the jump pill once the user is this many items deep. */
private const val JUMP_THRESHOLD = 4

/**
 * Any paged status timeline: home, local, federated, a hashtag (the
 * instance's own tag timeline, so posts federated here, never the
 * origin server's tag page), bookmarks, or favourites. [source] wins;
 * otherwise [tag] selects a hashtag timeline (milestone-5 signature),
 * otherwise home.
 *
 * Link-header max_id paging with infinite scroll, pull-to-refresh that
 * replaces the items with a fresh first page, the four mandatory states,
 * and jump-to-top: a floating "Back to top" pill once scrolled down, plus
 * [scrollToTop] events (bottom-nav reselect) that scroll to the top, or
 * refresh when already there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    api: Api,
    session: Session,
    tag: String? = null,
    onOpenThread: (String) -> Unit,
    onCompose: (String?) -> Unit,
    source: TimelineSource? = null,
    scrollToTop: Flow<Unit>? = null,
    /** Show the boosts/replies/self-boosts chip row (Home feeds). */
    showDisplayFilters: Boolean = false,
) {
    val resolved: TimelineSource = source ?: tag?.let { TimelineSource.Hashtag(it) } ?: TimelineSource.Home
    val label = resolved.label
    val settings = LocalAppSettings.current
    // Mastodon filter contexts; bookmarks/favourites sit outside every
    // context, so no filtering applies there.
    val filterContext = when (resolved) {
        TimelineSource.Home, is TimelineSource.ListTimeline -> "home"
        TimelineSource.Local, TimelineSource.Federated, is TimelineSource.Hashtag -> "public"
        TimelineSource.Bookmarks, TimelineSource.Favourites -> null
    }
    // Posts + scroll position live in the store, not in composition, so
    // returning from a post (or another tab) resumes exactly where you were.
    val holder = remember(session.id, resolved) { TimelineStore.holder(session.id, resolved) }
    var state by holder::state
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState(holder.firstVisibleIndex, holder.firstVisibleOffset)
    DisposableEffect(holder, listState) {
        onDispose {
            holder.firstVisibleIndex = listState.firstVisibleItemIndex
            holder.firstVisibleOffset = listState.firstVisibleItemScrollOffset
        }
    }
    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > JUMP_THRESHOLD } }
    // Per-feed display filters (client-side over the loaded page set).
    var showBoosts by rememberSaveable { mutableStateOf(true) }
    var showReplies by rememberSaveable { mutableStateOf(true) }
    var hideSelfBoosts by rememberSaveable { mutableStateOf(false) }
    // Rendered (filtered) row count and raw item count, written from
    // composition and read by the scroll effect. Kept as plain snapshot
    // state rather than reading `state` inside the snapshotFlow: a failed
    // load-more flips `loadingMore` back without changing either count, so
    // the flow does not re-emit and a failure cannot retry forever.
    var renderedCount by remember { mutableStateOf(0) }
    var rawCount by remember { mutableStateOf(0) }

    suspend fun fetchFirstPage(): TimelineState {
        val page = TimelineRepository.fetch(api, resolved)
        val fresh = page.items.distinctBy { it.id }
        return if (fresh.isEmpty()) TimelineState.Empty else TimelineState.Content(fresh, page.nextMaxId, loadingMore = false)
    }

    suspend fun loadFirstPage() {
        state = TimelineState.Loading
        state = try {
            fetchFirstPage()
        } catch (e: ApiError) {
            TimelineState.Error("Could not load the $label: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimelineState.Error("Could not load the $label: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    fun updateStatus(updated: Status) {
        val current = state as? TimelineState.Content ?: return
        state = current.copy(items = current.items.map { if (it.id == updated.id) updated else it })
        // Same post in other cached feeds (Home vs Local, boosts of it) too.
        TimelineStore.applyEdit(updated.reblog ?: updated)
    }

    fun showNotice(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    // One load-more at a time; guarded again inside loadNextPage so a
    // second trigger cannot double-fetch the same page.
    fun loadNextPage() {
        val current = state as? TimelineState.Content ?: return
        val maxId = current.nextMaxId ?: return // end reached
        if (current.loadingMore) return

        state = current.copy(loadingMore = true)
        scope.launch {
            try {
                val page = TimelineRepository.fetch(api, resolved, maxId)
                // Re-read: a favourite/boost may have landed while loading.
                val base = (state as? TimelineState.Content)?.items ?: current.items
                val baseIds = base.mapTo(HashSet()) { it.id }
                val fresh = page.items.filterNot { it.id in baseIds }
                state = TimelineState.Content(base + fresh, page.nextMaxId, loadingMore = false)
            } catch (e: ApiError) {
                (state as? TimelineState.Content)?.let { latest -> state = latest.copy(loadingMore = false) }
                showNotice("Could not load older posts: the server returned error ${e.status}. Scroll to try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (state as? TimelineState.Content)?.let { latest -> state = latest.copy(loadingMore = false) }
                showNotice("Could not load older posts: ${e.message ?: "network error"}. Scroll to try again.")
            }
        }
    }

    // Pull-to-refresh: re-fetch page 1 and replace the current items.
    // The list state is kept, so keyed items that persist hold their
    // scroll position. On failure the old items stay and a snackbar
    // explains the error and recovery. Works from Empty too, so a new
    // account's empty feed can be pulled once posts arrive.
    fun refresh() {
        val current = state
        if (current is TimelineState.Error) {
            scope.launch { loadFirstPage() }
            return
        }
        if (current !is TimelineState.Content && current !is TimelineState.Empty) return
        if (isRefreshing || (current is TimelineState.Content && current.loadingMore)) return

        isRefreshing = true
        scope.launch {
            try {
                state = fetchFirstPage()
                // Home only: persist the read position server-side (markers).
                if (resolved == TimelineSource.Home) {
                    (state as? TimelineState.Content)?.items?.firstOrNull()?.id?.let { newest ->
                        scope.launch {
                            try {
                                TimelineRepository.saveMarker(api, newest)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // The marker is best-effort; losing one just
                                // means this read position is not remembered.
                            }
                        }
                    }
                }
            } catch (e: ApiError) {
                showNotice("Could not refresh the $label: the server returned error ${e.status}. Pull down to try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNotice("Could not refresh the $label: ${e.message ?: "network error"}. Pull down to try again.")
            } finally {
                isRefreshing = false
            }
        }
    }

    // Load only what the store does not already hold. A load abandoned when
    // the user navigated away mid-request left Loading (refetch) or a stuck
    // loadingMore flag (clear it so infinite scroll works again).
    LaunchedEffect(api, session.id, resolved) {
        when (val current = state) {
            TimelineState.Loading, is TimelineState.Error -> loadFirstPage()
            is TimelineState.Content -> if (current.loadingMore) state = current.copy(loadingMore = false)
            TimelineState.Empty -> Unit
        }
    }

    // Server-backed read position (markers, home only): when the server
    // knows about posts newer than this device's cache, pull a fresh page.
    // The marker fetch and the Content wait are both awaited; on cold start
    // the state is still Loading, so the Content comparison must not run
    // against Loading and then never re-run — hence the snapshotFlow wait.
    LaunchedEffect(api, session.id, resolved) {
        if (resolved != TimelineSource.Home) return@LaunchedEffect
        try {
            val marker = TimelineRepository.readMarker(api) ?: return@LaunchedEffect
            val current = snapshotFlow { state }.filterIsInstance<TimelineState.Content>().first()
            val newest = current.items.firstOrNull()?.id ?: return@LaunchedEffect
            // ULIDs compare lexicographically in chronological order.
            if (marker > newest) refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The marker read is best-effort; a failure just skips the
            // freshness check and the next refresh catches up.
        }
    }

    // Bottom-nav reselect: scroll up, or refresh when already at the top.
    LaunchedEffect(scrollToTop) {
        scrollToTop?.collect {
            val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            if (atTop) refresh() else listState.jumpToTopSmoothly()
        }
    }

    // Infinite scroll: the flow is driven by the scroll position and the
    // rendered/raw row counts only — never by the store state. Reading
    // `state` here would re-emit when a failed load-more resets
    // `loadingMore`, retrying the same request forever. The threshold is
    // compared against the RENDERED (filtered) count, and a feed whose
    // rows are nearly all hidden keeps paging so filters cannot dead-end
    // it. `state` is read inside collect, and loadNextPage re-checks its
    // guards so this cannot double-fetch.
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            Triple(lastVisible, renderedCount, rawCount)
        }.collect { (lastVisible, rendered, raw) ->
            val current = state as? TimelineState.Content ?: return@collect
            if (current.nextMaxId == null || current.loadingMore) return@collect
            // Fewer than 3 rendered rows while more pages exist: the loaded
            // set is filtered away, so scroll position alone can never reach
            // the threshold — keep paging forward instead.
            val nearEnd = lastVisible >= rendered - 3
            if (nearEnd || (rendered <= 3 && raw > rendered)) loadNextPage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val s = state) {
                TimelineState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics { contentDescription = "Loading the $label" },
                    )
                }

                is TimelineState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { scope.launch { loadFirstPage() } },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "Retry loading the $label" },
                        ) {
                            Text("Retry")
                        }
                    }
                }

                // A LazyColumn (not a Box) so pull-to-refresh works on an empty feed.
                TimelineState.Empty -> LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(text = emptyCopy(resolved), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                is TimelineState.Content -> {
                    // Client-side display filters over the loaded page set:
                    // boosts, replies, self-boosts, and (wellbeing) alt-less media.
                    // Remembered on its inputs so paging logic can read the
                    // rendered count without re-filtering on every recomposition.
                    val items = remember(s.items, showBoosts, showReplies, hideSelfBoosts, settings.hideNoAltPosts) {
                        s.items.filter { st ->
                            val shown = st.reblog ?: st
                            if (!showBoosts && st.reblog != null) return@filter false
                            if (!showReplies && shown.in_reply_to_id != null) return@filter false
                            if (hideSelfBoosts && st.reblog != null && st.account.id == shown.account.id) return@filter false
                            if (settings.hideNoAltPosts && shown.media_attachments.any { it.description.isNullOrBlank() }) return@filter false
                            true
                        }
                    }
                    // How many rows the list actually renders (the filtered
                    // count plus the display-filter header), for the
                    // infinite-scroll threshold; also the raw page-set size,
                    // so a page fully hidden by filters still advances paging.
                    SideEffect {
                        renderedCount = items.size + if (showDisplayFilters) 1 else 0
                        rawCount = s.items.size
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Room below the last post so the compose FAB never covers it.
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        if (showDisplayFilters) {
                            item(key = "display-filters") {
                                DisplayFilterRow(
                                    showBoosts = showBoosts,
                                    showReplies = showReplies,
                                    hideSelfBoosts = hideSelfBoosts,
                                    onShowBoosts = { showBoosts = it },
                                    onShowReplies = { showReplies = it },
                                    onHideSelfBoosts = { hideSelfBoosts = it },
                                )
                            }
                        }
                        items(
                            count = items.size,
                            key = { items[it].id },
                            contentType = { "status" },
                        ) { index ->
                            StatusCard(
                                status = items[index],
                                api = api,
                                baseUrl = session.base,
                                onStatusUpdated = { updateStatus(it) },
                                onNotice = { showNotice(it) },
                                onOpenThread = onOpenThread,
                                onCompose = { replyToId -> onCompose(replyToId) },
                                filterContext = filterContext,
                                inTimeline = true,
                            )
                            if (index < items.lastIndex) HorizontalDivider()
                        }
                        if (s.loadingMore) {
                            item(key = "loading-more") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.semantics { contentDescription = "Loading older posts" },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val reduceMotion = LocalReduceMotion.current
            AnimatedVisibility(
                visible = showJump && state is TimelineState.Content,
                enter = if (reduceMotion) fadeIn() else fadeIn() + slideInVertically { -it },
                exit = if (reduceMotion) fadeOut() else fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            ) {
                JumpToTopPill(onClick = { scope.launch { listState.jumpToTopSmoothly() } })
            }
        }
    }
}

/** Floating "Back to top" pill shown once the user has scrolled down. */
@Composable
internal fun JumpToTopPill(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 4.dp,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = "Back to top" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Back to top", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Client-side display filters for Home feeds: show/hide boosts, show/hide
 * replies, and hide boosts of your own posts. Filters apply to the loaded
 * page set; paging continues underneath.
 *
 * Design: icon-led filter chips — a leading icon shows what the chip
 * governs, and a small trailing check marks what's currently shown. Chips
 * sit in a horizontal pill tray; a leading tuning icon labels the tray so
 * the row reads as one control, not three stray buttons.
 */
@Composable
private fun DisplayFilterRow(
    showBoosts: Boolean,
    showReplies: Boolean,
    hideSelfBoosts: Boolean,
    onShowBoosts: (Boolean) -> Unit,
    onShowReplies: (Boolean) -> Unit,
    onHideSelfBoosts: (Boolean) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "tray-icon") {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = "Filter this feed",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        item(key = "boosts") {
            FilterChip(
                selected = showBoosts,
                onClick = { onShowBoosts(!showBoosts) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Repeat,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = { Text("Boosts") },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = if (showBoosts) "Hide boosts" else "Show boosts"
                    },
            )
        }
        item(key = "replies") {
            FilterChip(
                selected = showReplies,
                onClick = { onShowReplies(!showReplies) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = { Text("Replies") },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = if (showReplies) "Hide replies" else "Show replies"
                    },
            )
        }
        item(key = "self-boosts") {
            FilterChip(
                selected = hideSelfBoosts,
                onClick = { onHideSelfBoosts(!hideSelfBoosts) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Repeat,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                // Fixed label: the selected state already shows it is on, and a
                // longer "on" label pushed the chip past the right screen edge.
                label = { Text("No self-boosts") },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription =
                            if (hideSelfBoosts) "Show boosts of your own posts again" else "Hide boosts of your own posts"
                    },
            )
        }
    }
}

/**
 * Scroll to the first item. From deep in a long feed, snap most of the
 * way first so the animation is short instead of a multi-second scroll.
 */
internal suspend fun LazyListState.jumpToTopSmoothly() {
    if (firstVisibleItemIndex > 15) scrollToItem(8)
    animateScrollToItem(0)
}

private fun emptyCopy(source: TimelineSource): String = when (source) {
    TimelineSource.Home -> "Your home timeline is empty. Follow some accounts and their posts will appear here."
    TimelineSource.Local -> "No posts from this server yet. Public posts by people on your instance will appear here."
    TimelineSource.Federated -> "No public posts have reached this server yet. They will appear here as they federate in."
    is TimelineSource.Hashtag ->
        "No posts with #${source.name} have reached this server yet. Tagged posts appear here as they federate in."
    TimelineSource.Bookmarks -> "No bookmarks yet. Tap the bookmark icon on any post to save it here."
    TimelineSource.Favourites -> "No favourites yet. Posts you favourite will be listed here."
    is TimelineSource.ListTimeline ->
        "The list \"${source.title}\" has no posts yet. Add accounts to it from Settings, then Lists."
}
