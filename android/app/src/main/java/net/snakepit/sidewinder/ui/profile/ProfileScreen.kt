package net.snakepit.sidewinder.ui.profile

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Relationship
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.mute.MuteRepository
import net.snakepit.sidewinder.core.profile.ProfileRepository
import net.snakepit.sidewinder.core.text.HtmlText
import net.snakepit.sidewinder.ui.app.LocalAppActions
import net.snakepit.sidewinder.ui.app.LocalAppSettings
import net.snakepit.sidewinder.ui.mutes.MuteDialog
import net.snakepit.sidewinder.ui.timeline.StatusCard

/** Account-header fetch state; the header survives tab switches. */
private sealed interface HeaderState {
    /** Account still loading. */
    data object Loading : HeaderState

    /** The account itself failed; nothing to show. */
    data class Error(val message: String) : HeaderState

    /** Account loaded; tab content renders under it. */
    data class Ready(val account: Account) : HeaderState
}

/** Statuses for one profile tab: page 1 loading, error, list, pagination. */
private data class TabListState(
    val loading: Boolean = false,
    val error: String? = null,
    val items: List<Status> = emptyList(),
    val nextMaxId: String? = null,
    val loadingMore: Boolean = false,
)

/**
 * A profile. Port of web `src/views/Profile.tsx`: account header with
 * avatar, names, sanitized note, fields, counts and joined date; the
 * relationship follow/unfollow/requested button (optimistic, reverted on
 * error with a snackbar); and a tabbed status timeline (posts, posts and
 * replies, media) with the same pagination pattern as the home timeline.
 */
@Composable
fun ProfileScreen(
    api: Api,
    session: Session,
    accountId: String,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    /** "followers" | "following" list navigation (web-parity count links). */
    onOpenAccountList: ((String) -> Unit)? = null,
) {
    var headerState by remember { mutableStateOf<HeaderState>(HeaderState.Loading) }
    var relationship by remember { mutableStateOf<Relationship?>(null) }
    /** True when the relationship fetch failed; the follow button stays disabled until retried. */
    var relError by remember { mutableStateOf(false) }
    var relBusy by remember { mutableStateOf(false) }
    var tabs by remember { mutableStateOf(emptyMap<ProfileRepository.StatusesTab, TabListState>()) }
    // Saved as its enum name so it survives process death via AutoSaver.
    var selectedTabName by rememberSaveable { mutableStateOf(ProfileRepository.StatusesTab.Posts.name) }
    val selectedTab = ProfileRepository.StatusesTab.valueOf(selectedTabName)
    // Monotonic sequence: any response that lands after a tab switch is dropped.
    var statusSeq by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val isSelf = accountId == session.account.id
    val settings = LocalAppSettings.current
    var confirmFollow by remember { mutableStateOf(false) }
    var muteDialogOpen by remember { mutableStateOf(false) }
    var noteDialogOpen by remember { mutableStateOf(false) }

    /** Fetch the relationship; on failure keep the profile up with a null relationship (K36). */
    suspend fun loadRelationshipSuspend() {
        relationship = try {
            ProfileRepository.relationship(api, accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            relError = true
            null
        }
    }

    suspend fun loadAccount() {
        headerState = HeaderState.Loading
        relError = false
        headerState = try {
            // The account loads on its own: a relationship-fetch failure
            // must not take the whole profile down (K36).
            val account = ProfileRepository.account(api, accountId)
            HeaderState.Ready(account)
        } catch (e: ApiError) {
            HeaderState.Error("Could not load this profile: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HeaderState.Error("Could not load this profile: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
        if (headerState is HeaderState.Ready && !isSelf) {
            loadRelationshipSuspend()
        }
    }

    fun retryRelationship() {
        if (relBusy) return
        relError = false
        relBusy = true
        scope.launch {
            try {
                loadRelationshipSuspend()
                if (relError) {
                    snackbarHostState.showSnackbar("Could not load the follow state for this account. Use Retry to try again.")
                }
            } finally {
                relBusy = false
            }
        }
    }

    fun loadFirstPage(tab: ProfileRepository.StatusesTab) {
        val seq = ++statusSeq
        tabs = tabs + (tab to TabListState(loading = true))
        scope.launch {
            try {
                val page = ProfileRepository.statuses(api, accountId, tab = tab)
                if (seq != statusSeq) return@launch
                val fresh = page.items.distinctBy { it.id }
                tabs = tabs + (tab to TabListState(items = fresh, nextMaxId = page.nextMaxId))
            } catch (e: ApiError) {
                if (seq != statusSeq) return@launch
                tabs = tabs + (tab to TabListState(error = "Could not load these posts: the server returned error ${e.status}. Check your connection, then retry."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq != statusSeq) return@launch
                tabs = tabs + (tab to TabListState(error = "Could not load these posts: ${e.message ?: "network error"}. Check your connection, then retry."))
            }
        }
    }

    fun showNotice(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun updateStatus(updated: Status) {
        // Apply to every cached tab, including inside boost wrappers,
        // so a favourite or edit on one tab cannot go stale on another (K37).
        tabs = tabs.mapValues { (_, tabState) ->
            tabState.copy(items = ProfileTabUpdates.updatedItems(tabState.items, updated))
        }
    }

    fun loadNextPage() {
        val current = tabs[selectedTab] ?: return
        val maxId = current.nextMaxId ?: return // end reached
        if (current.loadingMore || current.loading) return
        val tab = selectedTab
        val seq = ++statusSeq

        tabs = tabs + (tab to current.copy(loadingMore = true))
        scope.launch {
            try {
                val page = ProfileRepository.statuses(api, accountId, maxId, tab = tab)
                if (seq != statusSeq) return@launch
                val base = tabs[tab]?.items ?: current.items
                val baseIds = base.mapTo(HashSet()) { it.id }
                val fresh = page.items.filterNot { it.id in baseIds }
                tabs = tabs + (tab to TabListState(items = base + fresh, nextMaxId = page.nextMaxId))
            } catch (e: ApiError) {
                if (seq != statusSeq) return@launch
                tabs[tab]?.let { latest -> tabs = tabs + (tab to latest.copy(loadingMore = false)) }
                showNotice("Could not load older posts: the server returned error ${e.status}. Scroll to try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq != statusSeq) return@launch
                tabs[tab]?.let { latest -> tabs = tabs + (tab to latest.copy(loadingMore = false)) }
                showNotice("Could not load older posts: ${e.message ?: "network error"}. Scroll to try again.")
            }
        }
    }

    fun toggleFollow() {
        val rel = relationship ?: return
        if (relBusy) return
        val previous = rel
        // Optimistic: assume the call lands; revert to `previous` on error.
        relationship = if (previous.following || previous.requested) {
            previous.copy(following = false, requested = false)
        } else {
            val locked = (headerState as? HeaderState.Ready)?.account?.locked == true
            previous.copy(following = !locked, requested = locked)
        }
        relBusy = true
        scope.launch {
            try {
                relationship = if (previous.following || previous.requested) {
                    ProfileRepository.unfollow(api, accountId)
                } else {
                    ProfileRepository.follow(api, accountId)
                }
            } catch (e: ApiError) {
                relationship = previous
                showNotice("Could not update the follow state: the server returned error ${e.status}. Your previous relationship was restored; try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                relationship = previous
                showNotice("Could not update the follow state: ${e.message ?: "network error"}. Your previous relationship was restored; try again.")
            } finally {
                relBusy = false
            }
        }
    }

    /** Instant unmute; muting goes through [MuteDialog] to pick a duration. */
    fun unmuteAccount() {
        val rel = relationship ?: return
        if (relBusy) return
        val previous = rel
        relationship = previous.copy(muting = false)
        relBusy = true
        scope.launch {
            try {
                MuteRepository.unmute(api, session.token, accountId)
                relationship = ProfileRepository.relationship(api, accountId)
            } catch (e: ApiError) {
                relationship = previous
                showNotice("Could not unmute: the server returned error ${e.status}. Your previous state was restored; try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                relationship = previous
                showNotice("Could not unmute: ${e.message ?: "network error"}. Your previous state was restored; try again.")
            } finally {
                relBusy = false
            }
        }
    }

    fun selectTab(tab: ProfileRepository.StatusesTab) {
        if (tab.name == selectedTabName) return
        // The seq bump below drops any request still in flight for the tab
        // being left, so its transient flags must be reset here too, or
        // returning to it shows a permanent spinner / dead load-more.
        val leaving = selectedTab
        tabs[leaving]?.let { left ->
            tabs = when {
                left.loading -> tabs - leaving // first page never landed: refetch on return
                left.loadingMore -> tabs + (leaving to left.copy(loadingMore = false))
                else -> tabs
            }
        }
        selectedTabName = tab.name
        statusSeq++ // discard anything still in flight for the previous tab
        val existing = tabs[tab]
        if (existing == null || existing.error != null) {
            loadFirstPage(tab)
        }
    }

    LaunchedEffect(api, accountId) {
        loadAccount()
        if (headerState is HeaderState.Ready) loadFirstPage(selectedTab)
    }

    // Tab switch: reload page 1 for the new tab without touching the account,
    // then scroll back to the top so the header is in view.
    LaunchedEffect(selectedTab) {
        if (headerState is HeaderState.Ready) {
            listState.scrollToItem(0)
            if (tabs[selectedTab] == null) loadFirstPage(selectedTab)
        }
    }

    // Infinite scroll: same pattern as the home timeline; loadNextPage
    // re-checks the guards so this cannot double-fetch a page.
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to tabs[selectedTab]
        }.collect { (lastVisible, snapshot) ->
            if (snapshot == null) return@collect
            if (snapshot.nextMaxId == null || snapshot.loadingMore || snapshot.loading) return@collect
            if (lastVisible >= snapshot.items.size - 3) loadNextPage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val header = headerState) {
            HeaderState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading the profile" },
                )
            }

            is HeaderState.Error -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(header.message, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = {
                            scope.launch {
                                loadAccount()
                                if (headerState is HeaderState.Ready) loadFirstPage(selectedTab)
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Retry loading the profile" },
                    ) {
                        Text("Retry")
                    }
                }
            }

            is HeaderState.Ready -> {
                val list = tabs[selectedTab] ?: TabListState(loading = true)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    item(key = "header") {
                        ProfileHeader(
                            account = header.account,
                            isSelf = isSelf,
                            relationship = relationship,
                            relBusy = relBusy,
                            relError = relError,
                            onRetryRelationship = { retryRelationship() },
                            onToggleFollow = {
                                val rel = relationship
                                if (settings.confirmBeforeFollow && rel != null && !rel.following && !rel.requested) {
                                    confirmFollow = true
                                } else {
                                    toggleFollow()
                                }
                            },
                            onMuteClick = if (isSelf) {
                                null
                            } else {
                                {
                                    if (relationship?.muting == true) unmuteAccount() else muteDialogOpen = true
                                }
                            },
                            onNoteClick = if (isSelf) null else ({ noteDialogOpen = true }),
                            onOpenAccountList = onOpenAccountList,
                        )
                    }
                    item(key = "tabs") {
                        ProfileTabRow(
                            selectedTab = selectedTab,
                            onSelect = { selectTab(it) },
                        )
                    }
                    when {
                        list.loading -> item(key = "tab-loading") {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    modifier = Modifier.semantics { contentDescription = "Loading posts" },
                                )
                            }
                        }

                        list.error != null -> item(key = "tab-error") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(list.error, style = MaterialTheme.typography.bodyMedium)
                                Button(
                                    onClick = { loadFirstPage(selectedTab) },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics { contentDescription = "Retry loading these posts" },
                                ) {
                                    Text("Retry")
                                }
                            }
                        }

                        list.items.isEmpty() -> item(key = "tab-empty") {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = when (selectedTab) {
                                        ProfileRepository.StatusesTab.Posts -> "This account has not posted anything here yet."
                                        ProfileRepository.StatusesTab.PostsAndReplies -> "No posts or replies yet."
                                        ProfileRepository.StatusesTab.Media -> "No media posts yet."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        else -> items(count = list.items.size, key = { list.items[it].id }) { index ->
                            StatusCard(
                                status = list.items[index],
                                api = api,
                                baseUrl = session.base,
                                onStatusUpdated = { updateStatus(it) },
                                onNotice = { showNotice(it) },
                                onOpenThread = onOpenThread,
                                // The M3 binding signature has no compose callback;
                                // surface the same notice SearchScreen uses.
                                onCompose = {
                                    showNotice("Replying is not available from profiles. Open the post and reply there.")
                                },
                            )
                            if (index < list.items.lastIndex) HorizontalDivider()
                        }
                    }
                    if (list.loadingMore) {
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
    }

    val followTarget = (headerState as? HeaderState.Ready)?.account
    if (confirmFollow && followTarget != null) {
        AlertDialog(
            onDismissRequest = { confirmFollow = false },
            title = { Text("Follow @${followTarget.acct}?") },
            text = { Text("Your follow request will be sent to this account.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmFollow = false
                    toggleFollow()
                }) { Text("Follow") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFollow = false }) { Text("Cancel") }
            },
        )
    }

    if (muteDialogOpen) {
        val account = (headerState as? HeaderState.Ready)?.account
        if (account != null) {
            MuteDialog(
                account = account,
                api = api,
                token = session.token,
                onDismiss = { notice ->
                    muteDialogOpen = false
                    if (notice != null) {
                        showNotice(notice)
                        scope.launch {
                            runCatching { ProfileRepository.relationship(api, accountId) }
                                .onSuccess { relationship = it }
                        }
                    }
                },
            )
        } else {
            muteDialogOpen = false
        }
    }

    if (noteDialogOpen) {
        var noteText by remember(relationship?.id) { mutableStateOf(relationship?.note.orEmpty()) }
        var savingNote by remember { mutableStateOf(false) }
        var noteError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!savingNote) noteDialogOpen = false },
            title = { Text("Private note") },
            text = {
                Column {
                    Text(
                        "Only you can see this note. It is stored on your server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        placeholder = { Text("Note about this account") },
                        minLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .semantics { contentDescription = "Private note text" },
                    )
                    noteError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        savingNote = true
                        noteError = null
                        scope.launch {
                            try {
                                relationship = api.postForm(
                                    path = "/api/v1/accounts/$accountId/note",
                                    form = mapOf("comment" to noteText),
                                    serializer = Relationship.serializer(),
                                )
                                noteDialogOpen = false
                                showNotice("Note saved.")
                            } catch (e: ApiError) {
                                noteError = "Could not save the note: the server returned error ${e.status}. Try again."
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                noteError = "Could not save the note: ${e.message ?: "network error"}. Try again."
                            }
                            savingNote = false
                        }
                    },
                    enabled = !savingNote,
                    modifier = Modifier.semantics { contentDescription = "Save the private note" },
                ) { Text(if (savingNote) "Saving\u2026" else "Save") }
            },
            dismissButton = {
                TextButton(onClick = { noteDialogOpen = false }, enabled = !savingNote) { Text("Cancel") }
            },
        )
    }
}

/** The three profile timeline tabs (web parity: posts / replies / media). */
@Composable
private fun ProfileTabRow(
    selectedTab: ProfileRepository.StatusesTab,
    onSelect: (ProfileRepository.StatusesTab) -> Unit,
) {
    SecondaryTabRow(selectedTabIndex = selectedTab.ordinal) {
        ProfileRepository.StatusesTab.values().forEach { tab ->
            Tab(
                selected = tab == selectedTab,
                onClick = { onSelect(tab) },
                text = {
                    Text(
                        text = when (tab) {
                            ProfileRepository.StatusesTab.Posts -> "Posts"
                            ProfileRepository.StatusesTab.PostsAndReplies -> "Posts and replies"
                            ProfileRepository.StatusesTab.Media -> "Media"
                        },
                    )
                },
            )
        }
    }
}

/** Header block: avatar, names, note, fields, counts, joined date, relationship button. */
@Composable
private fun ProfileHeader(
    account: Account,
    isSelf: Boolean,
    relationship: Relationship?,
    relBusy: Boolean,
    relError: Boolean = false,
    onRetryRelationship: () -> Unit = {},
    onToggleFollow: () -> Unit,
    onMuteClick: (() -> Unit)? = null,
    onNoteClick: (() -> Unit)? = null,
    onOpenAccountList: ((String) -> Unit)? = null,
) {
    val actions = LocalAppActions.current
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = account.avatar_static ?: account.avatar,
                contentDescription = "Avatar of ${account.display_name ?: account.acct}",
                modifier = Modifier.size(56.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = account.display_name?.takeIf { it.isNotBlank() } ?: account.username,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "@${account.acct}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (isSelf) {
            TextButton(
                onClick = { actions?.openEditProfile() },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Edit your profile" },
            ) {
                Text("Edit profile")
            }
        } else if (relationship != null) {
            RelationshipButton(
                relationship = relationship,
                busy = relBusy,
                onToggleFollow = onToggleFollow,
            )
            if (onMuteClick != null) {
                TextButton(
                    onClick = onMuteClick,
                    enabled = !relBusy,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription =
                                if (relationship.muting) "Unmute this account" else "Mute this account"
                        },
                ) {
                    Text(if (relationship.muting) "Unmute" else "Mute")
                }
                if (onNoteClick != null) {
                    TextButton(
                        onClick = onNoteClick,
                        enabled = !relBusy,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Edit your private note about this account" },
                    ) {
                        Text(if (relationship.note.isBlank()) "Add note" else "Edit note")
                    }
                }
            }
        } else if (!isSelf && relError) {
            // Relationship fetch failed (K36): the follow state is unknown,
            // so the follow button stays disabled until the retry succeeds.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Follow this account; the follow state is not loaded yet" },
                ) {
                    Text("Follow")
                }
                TextButton(
                    onClick = onRetryRelationship,
                    enabled = !relBusy,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Retry loading the follow state" },
                ) {
                    Text("Retry")
                }
            }
        }

        if (account.note.isNotBlank()) {
            // Server HTML; sanitized inside HtmlText before display.
            HtmlText(html = account.note, baseUrl = account.url)
        }

        if (account.fields.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                account.fields.forEach { field ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = field.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        // Server HTML; sanitized inside HtmlText before display.
                        HtmlText(html = field.value, baseUrl = account.url)
                    }
                }
            }
        }

        // Follower/following counts open their lists on tap (web parity);
        // the posts count stays plain text (no posts-list route).
        if (onOpenAccountList != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CountLink(
                    label = "${account.following_count} following",
                    description = "Open the following list for ${account.display_name ?: account.acct}",
                ) { onOpenAccountList("following") }
                CountLink(
                    label = "${account.followers_count} followers",
                    description = "Open the followers list for ${account.display_name ?: account.acct}",
                ) { onOpenAccountList("followers") }
                Text(
                    text = "${account.statuses_count} posts",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .padding(top = 14.dp),
                )
            }
        } else {
            Text(
                text = "${account.followers_count} followers, ${account.following_count} following, ${account.statuses_count} posts",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // K38/G3-18: parse and format once per account, not per recomposition.
        val joinedText = remember(account.created_at) {
            account.created_at.takeIf { it.isNotBlank() }
                ?.let { joined -> runCatching { OffsetDateTime.parse(joined) }.getOrNull() }
                ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
        if (joinedText != null) {
            Text(
                text = "Joined $joinedText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RelationshipButton(
    relationship: Relationship,
    busy: Boolean,
    onToggleFollow: () -> Unit,
) {
    val (label, description) = when {
        relationship.following -> "Unfollow" to "Unfollow this account"
        relationship.requested -> "Requested" to "Cancel the pending follow request"
        relationship.followed_by && !relationship.following -> "Follow back" to "Follow this account back"
        else -> "Follow" to "Follow this account"
    }
    if (relationship.requested) {
        OutlinedButton(
            onClick = onToggleFollow,
            enabled = !busy,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = description },
        ) {
            Text(label)
        }
    } else {
        Button(
            onClick = onToggleFollow,
            enabled = !busy,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = description },
        ) {
            Text(label)
        }
    }
}

/** Tappable follower/following count; opens the matching account list. */
@Composable
private fun CountLink(label: String, description: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description },
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
