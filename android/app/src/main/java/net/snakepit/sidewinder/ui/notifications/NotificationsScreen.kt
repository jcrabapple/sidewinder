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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.model.Notification
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.notifications.NotificationsRepository
import net.snakepit.sidewinder.ui.timeline.relativeTimestamp
import org.jsoup.Jsoup

private sealed interface NotificationsState {
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
}

/** Query parameter value for each filter; null means unfiltered. */
private fun NotificationFilter.typesParam(): List<String>? = when (this) {
    NotificationFilter.All -> null
    NotificationFilter.Mentions -> listOf("mention")
}

/**
 * Notifications. Port of web `src/views/Notifications.tsx`: paged
 * through `NotificationsRepository` with Link-header max_id paging,
 * infinite scroll near the list end, and the four mandatory states.
 * A filter row above the list narrows the list to all notifications
 * or mentions only; switching filters reloads page 1.
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
) {
    var filter by rememberSaveable { mutableStateOf(NotificationFilter.All) }
    var state by remember { mutableStateOf<NotificationsState>(NotificationsState.Loading) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    suspend fun loadFirstPage() {
        state = NotificationsState.Loading
        state = try {
            val page = NotificationsRepository.notifications(api, types = filter.typesParam())
            val fresh = page.items.distinctBy { it.id }
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
        scope.launch {
            try {
                val page = NotificationsRepository.notifications(api, maxId, requestFilter.typesParam())
                // Filter switched mid-flight: this page belongs to the old list.
                if (filter != requestFilter) return@launch
                val base = (state as? NotificationsState.Content)?.items ?: current.items
                val baseIds = base.mapTo(HashSet()) { it.id }
                val fresh = page.items.filterNot { it.id in baseIds }
                state = NotificationsState.Content(base + fresh, page.nextMaxId, loadingMore = false)
            } catch (e: ApiError) {
                (state as? NotificationsState.Content)?.let { latest -> state = latest.copy(loadingMore = false) }
                scope.launch {
                    snackbarHostState.showSnackbar("Could not load older notifications: the server returned error ${e.status}. Scroll to try again.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (state as? NotificationsState.Content)?.let { latest -> state = latest.copy(loadingMore = false) }
                scope.launch {
                    snackbarHostState.showSnackbar("Could not load older notifications: ${e.message ?: "network error"}. Scroll to try again.")
                }
            }
        }
    }

    LaunchedEffect(api, session.id, filter) { loadFirstPage() }

    // Infinite scroll: trigger one page ahead of the last visible item.
    // Both the last visible index and the state are read inside the
    // snapshotFlow so a fresh page (or end of list) re-evaluates, and
    // loadNextPage re-checks the guards so this cannot double-fetch.
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to state
        }.collect { (lastVisible, snapshot) ->
            val current = snapshot as? NotificationsState.Content ?: return@collect
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
                    if (selected != filter) filter = selected
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
 * stays reachable while loading, error, or empty.
 */
@Composable
private fun FilterChipRow(
    selected: NotificationFilter,
    onSelect: (NotificationFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == NotificationFilter.All,
            onClick = { onSelect(NotificationFilter.All) },
            label = { Text("All") },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Show all notifications" },
        )
        FilterChip(
            selected = selected == NotificationFilter.Mentions,
            onClick = { onSelect(NotificationFilter.Mentions) },
            label = { Text("Mentions") },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Show mentions only" },
        )
    }
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
    val excerpt = shownStatus?.let { plainTextExcerpt(it.content) }
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

/**
 * Plain-text excerpt of server-rendered status HTML: all tags and
 * attributes removed, entities decoded, whitespace collapsed. No HTML
 * is ever interpreted as markup.
 */
internal fun plainTextExcerpt(html: String): String =
    Jsoup.parse(html).text().replace(Regex("\\s+"), " ").trim()
