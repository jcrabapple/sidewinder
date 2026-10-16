package net.snakepit.sidewinder.ui.notifications

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.model.Notification
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.notifications.NotificationsRepository
import net.snakepit.sidewinder.core.stream.StreamEvent
import net.snakepit.sidewinder.core.stream.StreamEventBus
import net.snakepit.sidewinder.core.text.StatusText
import net.snakepit.sidewinder.ui.timeline.relativeTimestamp

internal sealed interface NotificationsState {
    /** First page still loading. */
    data object Loading : NotificationsState

    /** First page failed; nothing to show. */
    data class Error(val message: String) : NotificationsState

    /** First page loaded with zero items. */
    data object Empty : NotificationsState

    /** At least one page loaded. */
    data class Content(
        val items: List<Notification>,
        val nextMaxId: String?,
        val loadingMore: Boolean,
    ) : NotificationsState
}

/** Which subset of notifications the list shows. */
enum class NotificationFilter {
    /** Every notification type. */
    All,

    /** Only mentions (`types[]=mention`). */
    Mentions,

    /** Only favourites (`types[]=favourite`). */
    Favourites,

    /** Only boosts (`types[]=reblog`). */
    Boosts,

    /** Follows and follow requests (`types[]=follow, follow_request`). */
    Follows,

    /** Only poll notifications (`types[]=poll`). */
    Polls,

    /** Only edited-post notifications (`types[]=update`). */
    Edits,
}

/** Query parameter value for each filter; null means unfiltered. */
private fun NotificationFilter.typesParam(): List<String>? = when (this) {
    NotificationFilter.All -> null
    NotificationFilter.Mentions -> listOf("mention")
    NotificationFilter.Favourites -> listOf("favourite")
    NotificationFilter.Boosts -> listOf("reblog")
    NotificationFilter.Follows -> listOf("follow", "follow_request")
    NotificationFilter.Polls -> listOf("poll")
    NotificationFilter.Edits -> listOf("update")
}

/**
 * Pure reduction for a streamed notification arriving while this screen is
 * open: prepend into Content (deduped by id), and — unlike the previous
 * behavior that dropped it — turn an Empty unfiltered list into Content
 * with the new notification. A filtered view never changes on stream
 * events (the notification may not belong to the filtered set), and the
 * read mark only advances from the unfiltered view (existing rule).
 */
internal fun applyStreamedNotification(
    state: NotificationsState,
    filter: NotificationFilter,
    incoming: Notification,
): NotificationsState = when {
    state is NotificationsState.Content ->
        if (state.items.any { it.id == incoming.id }) state
        else state.copy(items = listOf(incoming) + state.items)

    state is NotificationsState.Empty && filter == NotificationFilter.All ->
        NotificationsState.Content(listOf(incoming), nextMaxId = null, loadingMore = false)

    else -> state
}

/**
 * Merge a freshly fetched first page into a cached list (re-entry after
 * the holder already had content). When the fresh page overlaps the cache
 * the new items are prepended and the cached tail (and its cursor) kept;
 * with no overlap there is a gap, so the fresh page replaces the cache.
 * Non-Content states take the fresh page as-is.
 */
internal fun mergeFreshFirstPage(
    state: NotificationsState,
    fresh: List<Notification>,
    freshNextMaxId: String?,
): NotificationsState {
    val current = state as? NotificationsState.Content
    if (fresh.isEmpty()) {
        return current ?: NotificationsState.Empty
    }
    if (current == null) return NotificationsState.Content(fresh, freshNextMaxId, loadingMore = false)
    val cachedIds = current.items.mapTo(HashSet()) { it.id }
    val overlaps = fresh.any { it.id in cachedIds }
    if (!overlaps) return NotificationsState.Content(fresh, freshNextMaxId, loadingMore = false)
    val newer = fresh.filterNot { it.id in cachedIds }
    return if (newer.isEmpty()) current else current.copy(items = newer + current.items)
}

/**
 * One filter view's loaded notifications and scroll position, kept OUTSIDE
 * composition (same shape as TimelineStore) so rotation or a round trip
 * through another screen restores the same list at the same place instead
 * of refetching page 1 at the top. Keyed by session id + filter, LRU 12.
 */
internal class NotificationsHolder {
    var state by mutableStateOf<NotificationsState>(NotificationsState.Loading)
    var firstVisibleIndex: Int = 0
    var firstVisibleOffset: Int = 0
}

/** Process-lifetime store of [NotificationsHolder]s. Main-thread only. */
internal object NotificationsStore {
    private const val MAX_HOLDERS = 12

    // accessOrder = true: iteration order is least- to most-recently used.
    private val holders = LinkedHashMap<String, NotificationsHolder>(16, 0.75f, true)

    fun holder(sessionId: String, filter: NotificationFilter): NotificationsHolder {
        val key = "$sessionId|$filter"
        holders[key]?.let { return it }
        val created = NotificationsHolder()
        holders[key] = created
        while (holders.size > MAX_HOLDERS) {
            val eldest = holders.keys.first()
            holders.remove(eldest)
        }
        return created
    }

    /** Starting fresh in [filter] must not inherit an old scroll offset. */
    fun resetScroll(sessionId: String, filter: NotificationFilter) {
        holder(sessionId, filter).let {
            it.firstVisibleIndex = 0
            it.firstVisibleOffset = 0
        }
    }

    /** Drop every holder belonging to a session that no longer exists. */
    fun retainSessions(sessionIds: Set<String>) {
        holders.keys.removeAll { key -> key.substringBefore('|') !in sessionIds }
    }
}

/**
 * Notifications. Port of web `src/views/Notifications.tsx`: paged
 * through `NotificationsRepository` with Link-header max_id paging,
 * infinite scroll near the list end, and the four mandatory states.
 * A filter row above the list narrows the list to all notifications
 * or mentions only; switching filters reloads page 1 at the top.
 *
 * Rows carrying a status open that status's thread; account-only rows
 * (follow, follow_request) are plain, non-clickable rows.
 */
@Composable
fun NotificationsScreen(
    api: Api,
    session: Session,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenFollowRequests: () -> Unit,
    /** Fires after an unfiltered first page loads: the newest notification
     *  id (or null when empty), so the shell can advance the read mark. */
    onNewestLoaded: (String?) -> Unit = {},
) {
    var filter by rememberSaveable { mutableStateOf(NotificationFilter.All) }
    // State + scroll live in the store, not composition, so a rotation
    // (or navigation away and back) restores the same list and place.
    val holder = remember(session.id, filter) { NotificationsStore.holder(session.id, filter) }
    var state by holder::state
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = remember(holder) { LazyListState(holder.firstVisibleIndex, holder.firstVisibleOffset) }
    DisposableEffect(holder, listState) {
        onDispose {
            holder.firstVisibleIndex = listState.firstVisibleItemIndex
            holder.firstVisibleOffset = listState.firstVisibleItemScrollOffset
        }
    }

    suspend fun loadFirstPage() {
        state = NotificationsState.Loading
        state = try {
            val page = NotificationsRepository.notifications(api, types = filter.typesParam())
            val fresh = page.items.distinctBy { it.id }
            // Only the unfiltered view advances the shared read mark:
            // a filtered page's newest id is not the stream's newest.
            if (filter == NotificationFilter.All) onNewestLoaded(fresh.firstOrNull()?.id)
            if (fresh.isEmpty()) NotificationsState.Empty
            else NotificationsState.Content(fresh, page.nextMaxId, loadingMore = false)
        } catch (e: ApiError) {
            NotificationsState.Error("Could not load notifications: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NotificationsState.Error("Could not load notifications: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    // One load-more at a time; the guard is re-checked here so a second
    // trigger cannot double-fetch the same page.
    fun loadNextPage() {
        val current = state as? NotificationsState.Content ?: return
        val maxId = current.nextMaxId ?: return // end reached
        if (current.loadingMore) return

        state = current.copy(loadingMore = true)
        val requestFilter = filter
        // Write results to the holder that started the request: `state`
        // follows the active filter, and a filter switch mid-flight must not
        // strand loadingMore = true on this holder (it is cached for reuse).
        val requestHolder = holder
        scope.launch {
            try {
                val page = NotificationsRepository.notifications(api, maxId, requestFilter.typesParam())
                val base = (requestHolder.state as? NotificationsState.Content)?.items ?: current.items
                val baseIds = base.mapTo(HashSet()) { it.id }
                val fresh = page.items.filterNot { it.id in baseIds }
                requestHolder.state = NotificationsState.Content(base + fresh, page.nextMaxId, loadingMore = false)
            } catch (e: ApiError) {
                scope.launch {
                    snackbarHostState.showSnackbar("Could not load older notifications: the server returned error ${e.status}. Scroll to try again.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scope.launch {
                    snackbarHostState.showSnackbar("Could not load older notifications: ${e.message ?: "network error"}. Scroll to try again.")
                }
            } finally {
                // Success already cleared it; failure and cancellation (screen
                // left mid-request) must not leave the cached holder stuck.
                (requestHolder.state as? NotificationsState.Content)
                    ?.takeIf { it.loadingMore }
                    ?.let { requestHolder.state = it.copy(loadingMore = false) }
            }
        }
    }

    // Load page 1 only when this holder has nothing yet: a rotation or a
    // return visit reuses the cached list instead of resetting it.
    LaunchedEffect(api, session.id, filter) {
        if (holder.state !is NotificationsState.Content) {
            loadFirstPage()
            return@LaunchedEffect
        }
        // Cached content (rotation, or back from another tab): show it at
        // once, then refresh page 1 silently so the list is current and the
        // unread badge clears. Failures keep the cached list.
        try {
            val page = NotificationsRepository.notifications(api, types = filter.typesParam())
            val fresh = page.items.distinctBy { it.id }
            state = mergeFreshFirstPage(state, fresh, page.nextMaxId)
            if (filter == NotificationFilter.All) onNewestLoaded(fresh.firstOrNull()?.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep showing the cached list; pull-to-refresh retries.
        }
    }

    // Live notifications from the streaming socket, while this screen is
    // composed. Only the unfiltered view prepends (a filtered query would
    // not necessarily include the new type), and the read mark advances
    // because the user is looking at the screen right now. An Empty
    // unfiltered list becomes Content so a first-ever notification is
    // not dropped.
    LaunchedEffect(api, session.id) {
        StreamEventBus.events.collect { event ->
            if (event !is StreamEvent.NotificationArrived) return@collect
            val next = applyStreamedNotification(state, filter, event.notification)
            if (next != state) {
                state = next
                if (filter == NotificationFilter.All) onNewestLoaded(event.notification.id)
            }
        }
    }

    // Infinite scroll: the flow emits on SCROLL POSITION changes only and
    // the state is read inside collect. A failed page (loadingMore reset
    // without any scroll change) therefore cannot re-trigger itself in a
    // request/snackbar loop — the user has to scroll again to retry.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val current = state as? NotificationsState.Content ?: return@collect
                if (current.nextMaxId == null || current.loadingMore) return@collect
                if (lastVisible >= current.items.size - 3) loadNextPage()
            }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            FilterChipRow(
                selected = filter,
                onSelect = { selected ->
                    if (selected != filter) {
                        // A new filter is a new list: never inherit the
                        // old scroll offset.
                        NotificationsStore.resetScroll(session.id, selected)
                        filter = selected
                    }
                },
            )
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                when (val s = state) {
                    NotificationsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics { contentDescription = "Loading notifications" },
                        )
                    }

                    is NotificationsState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(s.message, style = MaterialTheme.typography.bodyMedium)
                            Button(
                                onClick = { scope.launch { loadFirstPage() } },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics { contentDescription = "Retry loading notifications" },
                            ) {
                                Text("Retry")
                            }
                        }
                    }

                    NotificationsState.Empty -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = when (filter) {
                                NotificationFilter.All -> "No notifications yet. Mentions, favourites, boosts, and new followers will appear here."
                                NotificationFilter.Mentions -> "No mentions yet. When someone mentions you, it will appear here."
                                NotificationFilter.Favourites -> "No favourites yet. When someone favourites one of your posts, it will appear here."
                                NotificationFilter.Boosts -> "No boosts yet. When someone boosts one of your posts, it will appear here."
                                NotificationFilter.Follows -> "No follows yet. New followers and follow requests will appear here."
                                NotificationFilter.Polls -> "No poll notifications yet. Polls you voted in will appear here when they close."
                                NotificationFilter.Edits -> "No edited posts yet. Edits to posts you interacted with will appear here."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    is NotificationsState.Content -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(count = s.items.size, key = { s.items[it].id }) { index ->
                            NotificationRow(
                                notification = s.items[index],
                                onOpenThread = onOpenThread,
                                onOpenProfile = onOpenProfile,
                                onOpenFollowRequests = onOpenFollowRequests,
                            )
                            if (index < s.items.lastIndex) HorizontalDivider()
                        }
                        if (s.loadingMore) {
                            item(key = "loading-more") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.semantics { contentDescription = "Loading older notifications" },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Filter row above the list: All shows every notification type,
 * Mentions shows only mentions. Rendered in every state so the filter
 * stays reachable while loading, error, or empty. The chip's own label
 * is its accessibility name — no custom contentDescription.
 */
@Composable
private fun FilterChipRow(
    selected: NotificationFilter,
    onSelect: (NotificationFilter) -> Unit,
) {
    val chips = listOf(
        NotificationFilter.All,
        NotificationFilter.Mentions,
        NotificationFilter.Favourites,
        NotificationFilter.Boosts,
        NotificationFilter.Follows,
        NotificationFilter.Polls,
        NotificationFilter.Edits,
    )
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips.size, key = { chips[it].name }) { index ->
            val value = chips[index]
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(value.label()) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/** Short chip label for a notification filter. */
private fun NotificationFilter.label(): String = when (this) {
    NotificationFilter.All -> "All"
    NotificationFilter.Mentions -> "Mentions"
    NotificationFilter.Favourites -> "Favourites"
    NotificationFilter.Boosts -> "Boosts"
    NotificationFilter.Follows -> "Follows"
    NotificationFilter.Polls -> "Polls"
    NotificationFilter.Edits -> "Edits"
}

/**
 * One notification row: type icon, actor avatar, concrete label, plain
 * text status excerpt, relative time. Status-bearing rows navigate to
 * the thread; follow / follow_request rows navigate to the actor's
 * profile, and follow_request rows carry an extra affordance to the
 * follow-requests screen.
 */
@Composable
private fun NotificationRow(
    notification: Notification,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenFollowRequests: () -> Unit,
) {
    val label = notificationLabel(notification)
    // Boost notifications wrap the original status (wrapper content is
    // empty); show and open the displayed inner status, matching the
    // unwrap behavior of StatusCard.
    val shownStatus = notification.status?.let { it.reblog ?: it }
    // Plain-text extraction is cached in StatusText and remembered per
    // content, so recompositions do not re-parse the HTML.
    val excerptHtml = shownStatus?.content
    val excerpt = remember(excerptHtml) { excerptHtml?.let { StatusText.plain(it) } }
    val statusId = shownStatus?.id
    val rowDescription = when {
        statusId != null -> "$label. Opens the post"
        notification.type == "follow_request" -> "$label. Opens the requesting account and the follow requests list"
        notification.type == "follow" -> "$label. Opens the account"
        else -> label
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                when {
                    statusId != null -> Modifier
                        .clickable(
                            onClickLabel = "Open the post this notification is about",
                        ) { onOpenThread(statusId) }
                        .semantics { contentDescription = rowDescription }

                    notification.type == "follow" || notification.type == "follow_request" -> Modifier
                        .clickable(
                            onClickLabel = "Open the account profile",
                        ) { onOpenProfile(notification.account.id) }
                        .semantics { contentDescription = rowDescription }

                    else -> Modifier.semantics { contentDescription = rowDescription }
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = typeIcon(notification.type),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AsyncImage(
            model = actorAvatar(notification),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.small),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (excerpt == null) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!excerpt.isNullOrEmpty()) {
                Text(
                    text = excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = relativeTimestamp(notification.created_at),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (notification.type == "follow_request") {
            TextButton(
                onClick = onOpenFollowRequests,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Open follow requests" },
            ) {
                Text("Review")
            }
        }
    }
}

/** "Name favourited" style label, falling back to @acct like the web client. */
private fun notificationLabel(notification: Notification): String {
    val account = notification.account
    val name = account.display_name?.takeIf { it.isNotBlank() } ?: "@${account.acct}"
    return when (notification.type) {
        "mention" -> "$name mentioned you"
        "status" -> "$name posted"
        "reblog" -> "$name boosted"
        "favourite" -> "$name favourited"
        "follow" -> "$name followed you"
        "follow_request" -> "$name requested to follow you"
        "poll" -> "A poll you took part in has ended"
        "update" -> "$name edited a post"
        else -> "$name (${notification.type})"
    }
}

private fun typeIcon(type: String) = when (type) {
    "mention" -> Icons.Outlined.AlternateEmail
    "status" -> Icons.Outlined.ChatBubbleOutline
    "reblog" -> Icons.Outlined.Repeat
    "favourite" -> Icons.Filled.Star
    "follow" -> Icons.Outlined.PersonAdd
    "follow_request" -> Icons.Outlined.PersonOutline
    "poll" -> Icons.Outlined.Poll
    "update" -> Icons.Outlined.Edit
    else -> Icons.Outlined.Notifications
}

private fun actorAvatar(notification: Notification): String =
    notification.account.avatar_static
        ?: notification.account.avatar
