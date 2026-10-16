@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.snakepit.sidewinder.ui.moderation

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.moderation.ModerationRepository
import net.snakepit.sidewinder.core.moderation.ModerationRepository.AdminAccount
import net.snakepit.sidewinder.core.moderation.ModerationRepository.AdminDomainBlock
import net.snakepit.sidewinder.core.moderation.ModerationRepository.AdminReport
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError

private val TAB_LABELS = listOf("Signups", "Reports", "Accounts", "Domains")

/** True when the session token carries the admin scope. Port of hasAdminScope(). */
private fun hasAdminScope(session: Session): Boolean =
    session.scopes?.split(Regex("\\s+"))?.contains("admin") == true

/** Four-state holder shared by every tab's list. */
private sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Error(val message: String) : LoadState<Nothing>
    data object Empty : LoadState<Nothing>
    data class Content<T>(val items: List<T>, val nextMaxId: String?) : LoadState<T>
}

/** Detail copy for the no-admin-scope hint state. */
private const val ADMIN_HINT =
    "This server manages moderation from the admin account. Sign in with the admin scope enabled."

/**
 * Moderation: the admin's work surface. Port of web
 * `src/views/Moderation.tsx`: signups, reports, accounts, and domain
 * blocks, every mutating action behind a confirm dialog because admin
 * calls are destructive. Without the admin scope only the hint renders.
 */
@Composable
fun ModerationScreen(
    api: Api,
    session: Session,
) {
    var tab by remember { mutableStateOf(0) }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "Moderation",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (!hasAdminScope(session)) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = ADMIN_HINT,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@Column
            }

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

            when (tab) {
                0 -> SignupsTab(api = api, base = session.base)
                1 -> ReportsTab(api = api)
                2 -> AccountsTab(api = api)
                else -> DomainsTab(api = api)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Signups
// ---------------------------------------------------------------------------

@Composable
private fun SignupsTab(api: Api, base: String) {
    var state by remember { mutableStateOf<LoadState<AdminAccount>>(LoadState.Loading) }
    var approvedAcct by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        state = LoadState.Loading
        state = try {
            val items = ModerationRepository.fetchPendingSignups(api, base)
            if (items.isEmpty()) LoadState.Empty else LoadState.Content(items, nextMaxId = null)
        } catch (e: ApiError) {
            LoadState.Error("Signups could not be loaded: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadState.Error("Signups could not be loaded: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    LaunchedEffect(api, base) { load() }

    fun remove(id: String) {
        val current = state as? LoadState.Content<AdminAccount> ?: return
        val remaining = current.items.filterNot { it.id == id }
        state = if (remaining.isEmpty()) LoadState.Empty else current.copy(items = remaining)
    }

    when (val s = state) {
        LoadState.Loading -> LoadingBlock("Loading pending signups")

        is LoadState.Error -> ErrorBlock(s.message, onRetry = { scope.launch { load() } })

        LoadState.Empty -> EmptyBlock(
            title = "No pending signups",
            body = "New signups also arrive as notifications with approve and deny buttons.",
        )

        is LoadState.Content<AdminAccount> -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            approvedAcct?.let { acct ->
                item(key = "approved-note") {
                    Text(
                        text = "Approved @$acct. The account still needs its email confirmed on the server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            item(key = "help") {
                Text(
                    text = "${s.items.size} pending signup${if (s.items.size == 1) "" else "s"}. " +
                        "Approve also starts their access, but GoToSocial has no email-confirm API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(count = s.items.size, key = { s.items[it].id }) { index ->
                SignupCard(
                    api = api,
                    account = s.items[index],
                    onDone = { removed ->
                        if (removed.approved) approvedAcct = removed.acct
                        remove(removed.id)
                    },
                )
                if (index < s.items.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SignupCard(
    api: Api,
    account: AdminAccount,
    onDone: (AdminAccount) -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var confirmDeny by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val name = account.displayName
    val acct = account.acct
    val reason = ModerationRepository.signupReason(account)

    fun decide(approve: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                if (approve) ModerationRepository.approveSignup(api, account.id)
                else ModerationRepository.denySignup(api, account.id)
                onDone(account.copy(approved = approve))
            } catch (e: ApiError) {
                error = "Could not ${if (approve) "approve" else "deny"} @$acct: the server returned error ${e.status}. Try again."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Could not ${if (approve) "approve" else "deny"} @$acct: ${e.message ?: "network error"}. Check your connection and try again."
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = account.avatarUrl,
                contentDescription = null, // decorative; the row text carries the identity
                modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@$acct", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (reason.isNotEmpty()) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Text(
                text = "No signup reason was provided.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            Button(
                onClick = { decide(approve = true) },
                enabled = !busy,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Approve signup from @$acct" },
            ) {
                Text(if (busy) "Approving" else "Approve")
            }
            OutlinedButton(
                onClick = { confirmDeny = true },
                enabled = !busy,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Deny signup from @$acct" },
            ) {
                Text("Deny", color = MaterialTheme.colorScheme.error)
            }
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (confirmDeny) {
        AlertDialog(
            onDismissRequest = { confirmDeny = false },
            title = { Text(text = "Deny @$acct's signup?") },
            text = { Text(text = "Their pending account will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeny = false
                        decide(approve = false)
                    },
                    modifier = Modifier.semantics { contentDescription = "Confirm denying signup from @$acct" },
                ) {
                    Text(text = "Deny", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDeny = false },
                    modifier = Modifier.semantics { contentDescription = "Cancel denying signup" },
                ) {
                    Text(text = "Cancel")
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Reports
// ---------------------------------------------------------------------------

@Composable
private fun ReportsTab(api: Api) {
    var resolved by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<LoadState<AdminReport>>(LoadState.Loading) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    suspend fun load(showResolved: Boolean) {
        state = LoadState.Loading
        state = try {
            val page = ModerationRepository.fetchReports(api, showResolved)
            if (page.items.isEmpty()) LoadState.Empty else LoadState.Content(page.items, page.nextMaxId)
        } catch (e: ApiError) {
            LoadState.Error("Reports could not be loaded: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadState.Error("Reports could not be loaded: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    // Single in-flight page guard, dedupe by id, same as every collection.
    fun loadMore() {
        val current = state as? LoadState.Content<AdminReport> ?: return
        val maxId = current.nextMaxId ?: return
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            try {
                val page = ModerationRepository.fetchReports(api, resolved, maxId)
                val base = (state as? LoadState.Content<AdminReport>)?.items ?: current.items
                val seen = base.mapTo(HashSet()) { it.id }
                state = LoadState.Content(base + page.items.filterNot { it.id in seen }, page.nextMaxId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the list; the next scroll attempt retries the page.
            } finally {
                loadingMore = false
            }
        }
    }

    fun onUpdated(report: AdminReport) {
        val current = state as? LoadState.Content<AdminReport> ?: return
        state = if (resolved) {
            current.copy(items = current.items.map { if (it.id == report.id) report else it })
        } else {
            val remaining = current.items.filterNot { it.id == report.id }
            if (remaining.isEmpty()) LoadState.Empty else current.copy(items = remaining)
        }
    }

    LaunchedEffect(api, resolved) { load(resolved) }

    // Infinite scroll one page ahead of the end, guarded against double-fetch
    // by replacing the cursor only after the page lands.
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to state
        }.collect { (lastVisible, snapshot) ->
            val current = snapshot as? LoadState.Content<AdminReport> ?: return@collect
            if (current.nextMaxId == null) return@collect
            if (lastVisible >= current.items.size - 3) loadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChipRow(
                options = listOf("Open" to false, "Resolved" to true),
                selected = resolved,
                onSelect = { resolved = it },
            )
        }
        when (val s = state) {
            LoadState.Loading -> LoadingBlock("Loading ${if (resolved) "resolved" else "open"} reports")

            is LoadState.Error -> ErrorBlock(s.message, onRetry = { scope.launch { load(resolved) } })

            LoadState.Empty -> EmptyBlock(
                title = if (resolved) "No resolved reports" else "No open reports",
                body = "Reports filed on your server arrive here and as notifications.",
            )

            is LoadState.Content<AdminReport> -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(count = s.items.size, key = { s.items[it].id }) { index ->
                    ReportCard(
                        api = api,
                        report = s.items[index],
                        onUpdated = ::onUpdated,
                    )
                    if (index < s.items.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ReportCard(
    api: Api,
    report: AdminReport,
    onUpdated: (AdminReport) -> Unit,
) {
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmSuspend by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val reporter = report.account
    val target = report.target_account

    fun resolve(withSuspend: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                if (withSuspend) {
                    ModerationRepository.suspendAccount(api, target.id, "Report ${report.id}")
                }
                ModerationRepository.resolveReport(api, report.id, comment)
                onUpdated(
                    report.copy(
                        action_taken = true,
                        action_taken_comment = comment.trim().ifEmpty { null },
                    ),
                )
            } catch (e: ApiError) {
                error = "Could not resolve the report: the server returned error ${e.status}. Try again."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Could not resolve the report: ${e.message ?: "network error"}. Check your connection and try again."
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = reporter.avatar_static ?: reporter.avatar,
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${reporter.display_name.ifBlank { reporter.username }} reported ${target.display_name.ifBlank { target.username }}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (target.domain != null) {
                    Text(
                        text = "@${target.acct}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (report.comment.isNotBlank()) {
            Text(
                text = report.comment,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (report.statuses.isNotEmpty()) {
            Text(
                text = "${report.statuses.size} reported post${if (report.statuses.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (report.action_taken) {
            Text(
                text = report.action_taken_comment?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Resolved (no comment recorded)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .semantics { contentDescription = "Action comment, visible to the reporter" },
                label = { Text("Action comment") },
                supportingText = { Text("Visible to the reporter") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                OutlinedButton(
                    onClick = { resolve(withSuspend = false) },
                    enabled = !busy,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Resolve report from @${reporter.acct}" },
                ) {
                    Text(if (busy) "Resolving" else "Resolve")
                }
                OutlinedButton(
                    onClick = { confirmSuspend = true },
                    enabled = !busy,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Suspend @${target.acct} and resolve the report" },
                ) {
                    Text("Suspend and resolve", color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                text = "Suspending cannot be undone from here: lifting a suspend takes the server CLI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (confirmSuspend) {
        AlertDialog(
            onDismissRequest = { confirmSuspend = false },
            title = { Text(text = "Suspend @${target.acct} and resolve the report?") },
            text = {
                Text(
                    text = "GoToSocial has no API to undo a suspend. Lifting one takes the server CLI " +
                        "(admin account unsuspend).",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSuspend = false
                        resolve(withSuspend = true)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm suspending @${target.acct} and resolving the report"
                    },
                ) {
                    Text(text = "Suspend and resolve", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmSuspend = false },
                    modifier = Modifier.semantics { contentDescription = "Cancel suspend and resolve" },
                ) {
                    Text(text = "Cancel")
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Accounts
// ---------------------------------------------------------------------------

@Composable
private fun AccountsTab(api: Api) {
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("all") } // all / local / remote / suspended
    var state by remember { mutableStateOf<LoadState<AdminAccount>>(LoadState.Loading) }
    var searchId by remember { mutableStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    suspend fun run(q: String, m: String) {
        state = LoadState.Loading
        state = try {
            val page = ModerationRepository.searchAdminAccounts(
                api = api,
                username = q.trim().ifEmpty { null },
                remote = m == "remote",
                suspended = m == "suspended",
            )
            if (page.items.isEmpty()) LoadState.Empty else LoadState.Content(page.items, page.nextMaxId)
        } catch (e: ApiError) {
            LoadState.Error("Account search failed: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadState.Error("Account search failed: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    fun loadMore() {
        val current = state as? LoadState.Content<AdminAccount> ?: return
        val maxId = current.nextMaxId ?: return
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            try {
                val page = ModerationRepository.searchAdminAccounts(
                    api = api,
                    username = query.trim().ifEmpty { null },
                    remote = mode == "remote",
                    suspended = mode == "suspended",
                    maxId = maxId,
                )
                val base = (state as? LoadState.Content<AdminAccount>)?.items ?: current.items
                val seen = base.mapTo(HashSet()) { it.id }
                state = LoadState.Content(base + page.items.filterNot { it.id in seen }, page.nextMaxId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the list; the next scroll attempt retries the page.
            } finally {
                loadingMore = false
            }
        }
    }

    LaunchedEffect(api, searchId) { run(query, mode) }

    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to state
        }.collect { (lastVisible, snapshot) ->
            val current = snapshot as? LoadState.Content<AdminAccount> ?: return@collect
            if (current.nextMaxId == null) return@collect
            if (lastVisible >= current.items.size - 3) loadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "Search accounts by username" },
            label = { Text("Search accounts") },
            singleLine = true,
            trailingIcon = {
                TextButton(
                    onClick = { searchId++ },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Run account search" },
                ) {
                    Text("Search")
                }
            },
        )
        FilterChipRow(
            options = listOf(
                "All accounts" to "all",
                "Local only" to "local",
                "Remote only" to "remote",
                "Suspended" to "suspended",
            ),
            selected = mode,
            onSelect = {
                mode = it
                searchId++
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        when (val s = state) {
            LoadState.Loading -> LoadingBlock("Searching")

            is LoadState.Error -> ErrorBlock(s.message, onRetry = { searchId++ })

            LoadState.Empty -> EmptyBlock(
                title = "No matching accounts",
                body = "Nothing matched that search.",
            )

            is LoadState.Content<AdminAccount> -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(count = s.items.size, key = { s.items[it].id }) { index ->
                    AdminAccountCard(api = api, account = s.items[index])
                    if (index < s.items.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AdminAccountCard(api: Api, account: AdminAccount) {
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmSuspend by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val name = account.displayName
    val acct = account.acct
    val isRemote = account.domain != null

    fun doSuspend() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                ModerationRepository.suspendAccount(api, account.id, reason)
            } catch (e: ApiError) {
                error = "Could not suspend @$acct: the server returned error ${e.status}. Try again."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Could not suspend @$acct: ${e.message ?: "network error"}. Check your connection and try again."
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = account.avatarUrl,
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = acct + if (isRemote) " (remote)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .semantics { contentDescription = "Reason for the suspension, recorded with the action" },
            label = { Text("Why") },
            supportingText = { Text("Recorded with the action") },
            singleLine = true,
        )
        // Full-width action row: the old trailing "Suspended"-labeled button
        // overflowed narrow cards; the button names its action and spans.
        Button(
            onClick = { confirmSuspend = true },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Suspend @$acct" },
        ) {
            Text(if (busy) "Suspending\u2026" else "Suspend")
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (confirmSuspend) {
        AlertDialog(
            onDismissRequest = { confirmSuspend = false },
            title = { Text(text = "Suspend @$acct?") },
            text = {
                Text(
                    text = (if (isRemote) {
                        "Their instance still hosts the account; this instance stops ingesting their content."
                    } else {
                        "Their profile becomes inaccessible and their content is dropped."
                    }) + " GoToSocial has no API to undo a suspend. Lifting one takes the server CLI.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSuspend = false
                        doSuspend()
                    },
                    modifier = Modifier.semantics { contentDescription = "Confirm suspending @$acct" },
                ) {
                    Text(text = "Suspend", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmSuspend = false },
                    modifier = Modifier.semantics { contentDescription = "Cancel suspending @$acct" },
                ) {
                    Text(text = "Cancel")
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Domain blocks
// ---------------------------------------------------------------------------

@Composable
private fun DomainsTab(api: Api) {
    var state by remember { mutableStateOf<LoadState<AdminDomainBlock>>(LoadState.Loading) }
    var domain by remember { mutableStateOf("") }
    var publicComment by remember { mutableStateOf("") }
    var obfuscate by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AdminDomainBlock?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        state = LoadState.Loading
        state = try {
            val page = ModerationRepository.fetchDomainBlocks(api)
            if (page.items.isEmpty()) LoadState.Empty else LoadState.Content(page.items, page.nextMaxId)
        } catch (e: ApiError) {
            LoadState.Error("Domain blocks could not be loaded: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadState.Error("Domain blocks could not be loaded: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    fun add() {
        val d = domain.trim()
        if (d.isEmpty() || adding) return
        adding = true
        error = null
        scope.launch {
            try {
                ModerationRepository.createDomainBlock(api, d, publicComment, obfuscate)
                domain = ""
                publicComment = ""
                obfuscate = false
                load()
            } catch (e: ApiError) {
                error = "Could not block $d: the server returned error ${e.status}. Try again."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Could not block $d: ${e.message ?: "network error"}. Check your connection and try again."
            } finally {
                adding = false
            }
        }
    }

    fun remove(block: AdminDomainBlock) {
        error = null
        scope.launch {
            try {
                ModerationRepository.deleteDomainBlock(api, block.id)
                val current = state as? LoadState.Content<AdminDomainBlock>
                if (current != null) {
                    val remaining = current.items.filterNot { it.id == block.id }
                    state = if (remaining.isEmpty()) LoadState.Empty else current.copy(items = remaining)
                }
            } catch (e: ApiError) {
                error = "Could not unblock ${block.domain}: the server returned error ${e.status}. Try again."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Could not unblock ${block.domain}: ${e.message ?: "network error"}. Check your connection and try again."
            }
        }
    }

    LaunchedEffect(api) { load() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = domain,
            onValueChange = { domain = it },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Domain to block" },
            label = { Text("Domain to block") },
            placeholder = { Text("domain.example") },
            singleLine = true,
        )
        OutlinedTextField(
            value = publicComment,
            onValueChange = { publicComment = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .semantics { contentDescription = "Public comment on the block" },
            label = { Text("Public comment (optional)") },
            singleLine = true,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Switch) { obfuscate = !obfuscate }
                .semantics { contentDescription = "Obfuscate domain name publicly, ${if (obfuscate) "on" else "off"}" }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Obfuscate name publicly",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = obfuscate,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        Button(
            onClick = { add() },
            enabled = !adding && domain.isNotBlank(),
            modifier = Modifier
                .padding(top = 8.dp)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Block the entered domain" },
        ) {
            Text(if (adding) "Blocking" else "Block domain")
        }
        Text(
            text = "Blocking a domain stops fetching anything from it: profiles, posts, boosts, media. Undo is instant, from this list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        when (val s = state) {
            LoadState.Loading -> Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading domain blocks" },
                )
            }

            is LoadState.Error -> Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { scope.launch { load() } },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Retry loading domain blocks" },
                    ) {
                        Text("Retry")
                    }
                }
            }

            LoadState.Empty -> Text(
                text = "No domains blocked.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 24.dp),
            )

            is LoadState.Content<AdminDomainBlock> -> Column {
                s.items.forEach { block ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = block.domain,
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            block.public_comment?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { pendingDelete = block },
                            enabled = !adding,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "Unblock ${block.domain}" },
                        ) {
                            Text("Unblock")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { block ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = "Unblock ${block.domain}?") },
            text = { Text(text = "Their posts and accounts can federate again immediately.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        remove(block)
                    },
                    modifier = Modifier.semantics { contentDescription = "Confirm unblocking ${block.domain}" },
                ) {
                    Text(text = "Unblock", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    modifier = Modifier.semantics { contentDescription = "Cancel unblocking ${block.domain}" },
                ) {
                    Text(text = "Cancel")
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Shared state blocks and small pieces
// ---------------------------------------------------------------------------

@Composable
private fun LoadingBlock(label: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Retry" },
            ) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun EmptyBlock(title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Segmented filter chips used by the reports and accounts tabs. */
@Composable
private fun <T> FilterChipRow(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            val isSelected = value == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "$label filter${if (isSelected) ", selected" else ""}" },
            )
        }
    }
}
