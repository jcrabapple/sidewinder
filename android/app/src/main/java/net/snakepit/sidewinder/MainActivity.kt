package net.snakepit.sidewinder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.datastore.SessionStore
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.notify.NotifyScheduler
import net.snakepit.sidewinder.core.notifications.UnreadStore
import net.snakepit.sidewinder.core.search.SearchRepository
import net.snakepit.sidewinder.core.settings.AppSettings
import net.snakepit.sidewinder.core.settings.SettingsStore
import net.snakepit.sidewinder.core.settings.ThemePref
import net.snakepit.sidewinder.core.thread.ThreadRepository
import net.snakepit.sidewinder.core.timeline.TimelineSource
import net.snakepit.sidewinder.ui.account.AccountSwitcher
import net.snakepit.sidewinder.ui.catchup.CatchUpScreen
import net.snakepit.sidewinder.ui.insights.InsightsScreen
import net.snakepit.sidewinder.ui.lists.ListMembersScreen
import net.snakepit.sidewinder.ui.lists.ListsScreen
import net.snakepit.sidewinder.ui.moderation.ModerationScreen
import net.snakepit.sidewinder.ui.app.AppActions
import net.snakepit.sidewinder.ui.app.LocalAppActions
import net.snakepit.sidewinder.ui.app.LocalAppSettings
import net.snakepit.sidewinder.ui.app.openInBrowser
import net.snakepit.sidewinder.ui.compose.ComposeScreen
import net.snakepit.sidewinder.ui.login.LoginScreen
import net.snakepit.sidewinder.ui.login.handleOauthRedirect
import net.snakepit.sidewinder.ui.notifications.NotificationsScreen
import net.snakepit.sidewinder.ui.profile.AccountListScreen
import net.snakepit.sidewinder.ui.profile.ProfileScreen
import net.snakepit.sidewinder.ui.requests.FollowRequestsScreen
import net.snakepit.sidewinder.ui.search.SearchScreen
import net.snakepit.sidewinder.ui.settings.SettingsScreen
import net.snakepit.sidewinder.ui.theme.SidewinderTheme
import net.snakepit.sidewinder.ui.thread.ThreadScreen
import net.snakepit.sidewinder.ui.timeline.TagFollowButton
import net.snakepit.sidewinder.ui.timeline.TimelineStore
import net.snakepit.sidewinder.ui.timeline.TimelineScreen

/** Top-level destinations; screen features land inside these tabs. */
private enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Filled.Home),
    Notifications("notifications", "Notifications", Icons.Filled.Notifications),
    Search("search", "Search", Icons.Filled.Search),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

/** Content shared into the app via ACTION_SEND / ACTION_SEND_MULTIPLE. */
internal data class SharePayload(val text: String?, val media: List<Uri>)

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent extra naming a destination to open (notification taps). */
        const val EXTRA_OPEN = "net.snakepit.sidewinder.OPEN"
        const val OPEN_NOTIFICATIONS = "notifications"
    }

    /** OAuth redirect captured from the Custom Tab; consumed by [AppRoot]. */
    internal val redirectUri = mutableStateOf<Uri?>(null)

    /** Shared text/images waiting for the composer; consumed by AppTabs. */
    internal val pendingShare = mutableStateOf<SharePayload?>(null)

    /** Destination requested by a notification tap; consumed by AppTabs. */
    internal val pendingOpen = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation, process restore) must not replay
        // the launch intent's share or deep link a second time; the OAuth
        // redirect is always captured (it is idempotent and state-checked).
        captureIntent(intent, includeOneShots = savedInstanceState == null)
        setContent { AppRoot(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureIntent(intent, includeOneShots = true)
        setIntent(intent)
    }

    private fun captureIntent(intent: Intent?, includeOneShots: Boolean) {
        if (intent == null) return
        intent.data?.let { data ->
            if (data.scheme == "sidewinder" && data.host == "oauth") redirectUri.value = data
        }
        if (!includeOneShots) return
        intent.getStringExtra(EXTRA_OPEN)?.let { pendingOpen.value = it }
        captureShare(intent)?.let { pendingShare.value = it }
    }

    private fun captureShare(intent: Intent): SharePayload? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { body ->
            // Browsers send the page title as EXTRA_SUBJECT; keep it as a lead line.
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            if (!subject.isNullOrBlank() && !body.contains(subject)) "$subject\n$body" else body
        }
        val media: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
            else -> return null
        }
        if (text.isNullOrBlank() && media.isEmpty()) return null
        return SharePayload(text, media)
    }

    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)

    private fun Intent.streamUris(): List<Uri> =
        (
            if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            )?.filterNotNull().orEmpty()
}

@Composable
private fun AppRoot(activity: MainActivity) {
    val store = remember { SessionStore(activity) }
    val settingsStore = remember { SettingsStore(activity) }
    var session by remember { mutableStateOf<Session?>(null) }
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var sessionLoaded by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(AppSettings()) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var askedPermission by remember { mutableStateOf(false) }
    val unreadStore = remember { UnreadStore(activity) }
    var unreadCount by remember { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val redirect by activity.redirectUri

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* background polling just stays silent without the permission */ }

    LaunchedEffect(Unit) {
        settings = settingsStore.settings()
        sessions = store.list()
        session = store.active()
        // Seed the badge from the cached count, then recount over the
        // newest window so the badge is honest before the first render.
        session?.let { s -> unreadCount = unreadStore.count(s.id) }
        sessionLoaded = true
    }

    // Recount unread notifications whenever the active account changes.
    LaunchedEffect(session?.id, sessionLoaded) {
        val s = session ?: return@LaunchedEffect
        if (!sessionLoaded) return@LaunchedEffect
        unreadCount = runCatching {
            UnreadStore.recount(Api(s.base, s.token), s.id, unreadStore)
        }.getOrDefault(unreadStore.count(s.id))
    }

    // Keep background polling in step with the setting and the session,
    // and ask for the notification permission once when enabling.
    // A signed-out app (session == null) must cancel the periodic work:
    // the old early-return left polling running after the last sign-out.
    LaunchedEffect(settings.backgroundNotifications, session?.id) {
        if (settings.backgroundNotifications && session != null) {
            NotifyScheduler.ensure(activity)
            NotifyScheduler.runNow(activity)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED &&
                !askedPermission
            ) {
                askedPermission = true
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            NotifyScheduler.cancel(activity)
        }
    }

    // A completed OAuth round-trip lands here from the Custom Tab.
    LaunchedEffect(redirect) {
        val uri = redirect ?: return@LaunchedEffect
        try {
            handleOauthRedirect(uri)
            session = store.active()
            sessions = store.list()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            loginError = e.message ?: "Login failed"
        } finally {
            // Clear only after the work settles; nulling earlier would
            // change this effect's key and cancel the exchange mid-flight.
            if (activity.redirectUri.value == uri) activity.redirectUri.value = null
        }
    }

    LaunchedEffect(loginError) {
        loginError?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            loginError = null
        }
    }

    val dark = when (settings.theme) {
        ThemePref.DARK -> true
        ThemePref.LIGHT -> false
        ThemePref.SYSTEM -> isSystemInDarkTheme()
    }
    SidewinderTheme(darkTheme = dark) {
      CompositionLocalProvider(LocalAppSettings provides settings) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val active = session
                when {
                    !sessionLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    active == null -> LoginScreen(
                        onLoggedIn = { /* redirect path updates session above */ },
                        onCancel = null,
                    )
                    else -> AppTabs(
                        activity = activity,
                        session = active,
                        sessions = sessions,
                        store = store,
                        settingsStore = settingsStore,
                        unreadStore = unreadStore,
                        unreadCount = unreadCount,
                        onUnreadChanged = { unreadCount = it },
                        onSessionsChanged = {
                            sessions = store.list()
                            session = store.active()
                            // Signed-out accounts' cached timelines go with them.
                            TimelineStore.retainSessions(sessions.mapTo(HashSet()) { it.id })
                        },
                        onSettingsChanged = { settings = settingsStore.settings() },
                    )
                }
            }
        }
      }
    }
}

/** Feeds offered as tabs on the Home destination (Tusky/Moshidon layout). */
private val HOME_FEEDS = listOf(
    "Home" to TimelineSource.Home,
    "Local" to TimelineSource.Local,
    "Federated" to TimelineSource.Federated,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTabs(
    activity: MainActivity,
    session: Session,
    sessions: List<Session>,
    store: SessionStore,
    settingsStore: SettingsStore,
    unreadStore: UnreadStore,
    unreadCount: Int,
    onUnreadChanged: (Int) -> Unit,
    onSessionsChanged: suspend () -> Unit,
    onSettingsChanged: suspend () -> Unit,
) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val api = remember(session.token) { Api(session.base, session.token) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val topLevelRoutes = remember { TopLevel.entries.map { it.route }.toSet() }
    val openThread = remember(nav) { { id: String -> nav.navigate("thread/$id") } }
    val openProfile = remember(nav) { { id: String -> nav.navigate("profile/$id") } }
    val openTag = remember(nav) { { name: String -> nav.navigate("tag/${Uri.encode(name)}") } }
    val openCompose = remember(nav) { { replyTo: String? ->
        nav.navigate(if (replyTo != null) "compose?replyTo=$replyTo" else "compose")
    } }
    fun navigateTopLevel(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Bottom-nav reselect bus: tapping the current tab asks that screen to
    // jump to its top (or refresh when already there).
    val reselect = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    val homeReselect = remember { reselect.filter { it == TopLevel.Home.route }.map { } }

    // In-app link routing for every StatusCard / HtmlText below.
    val actions = remember(nav, api, session.account.id) {
        AppActions(
            openThread = openThread,
            openProfile = openProfile,
            openTag = openTag,
            openCompose = openCompose,
            openRemoteAccount = { url, acct ->
                scope.launch {
                    // Webfinger/fetch the account on our own instance so it opens in-app.
                    val id = try {
                        SearchRepository.search(api, query = url, type = "accounts", limit = 1, resolve = true)
                            .accounts.firstOrNull()?.id
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (id != null) {
                        openProfile(id)
                    } else {
                        snackbar.showSnackbar("Could not find @${acct ?: "that account"} from your server. Opening it in the browser.")
                        openInBrowser(context, url)
                    }
                }
            },
            openUrl = { url -> openInBrowser(context, url) },
            selfAccountId = session.account.id,
            openEdit = { id -> nav.navigate("compose?edit=$id") },
        )
    }

    // Content shared into the app opens the composer once signed in.
    var shareForCompose by remember { mutableStateOf<SharePayload?>(null) }
    val pendingShare by activity.pendingShare
    LaunchedEffect(pendingShare) {
        val payload = pendingShare ?: return@LaunchedEffect
        activity.pendingShare.value = null
        shareForCompose = payload
        nav.navigate("compose?share=true")
    }

    // Notification taps land on the Notifications tab.
    val pendingOpen by activity.pendingOpen
    LaunchedEffect(pendingOpen) {
        val target = pendingOpen ?: return@LaunchedEffect
        activity.pendingOpen.value = null
        if (target == MainActivity.OPEN_NOTIFICATIONS) navigateTopLevel(TopLevel.Notifications.route)
    }

    CompositionLocalProvider(LocalAppActions provides actions) {
    Scaffold(
        // Not edge-to-edge: the decor already applies system-bar insets,
        // so the default contentWindowInsets would pad the content a
        // second time below the top bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (currentRoute in topLevelRoutes) {
                TopAppBar(
                    // The activity is not edge-to-edge (the system already
                    // pads the content below the status bar); the default
                    // windowInsets would add that height a second time.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        Text(
                            "Sidewinder",
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { nav.navigate("catchup") },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Filled.History, contentDescription = "Catch up on posts you missed")
                        }
                        IconButton(
                            onClick = { nav.navigate("insights") },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Filled.QueryStats, contentDescription = "Your posting insights")
                        }
                        AccountSwitcher(
                            sessions = sessions,
                            active = session,
                            onSelect = { candidate ->
                                scope.launch {
                                    store.setActive(candidate.id)
                                    onSessionsChanged()
                                }
                            },
                            onAddAccount = { nav.navigate("login") },
                            onOpenMyProfile = { openProfile(session.account.id) },
                            onOpenBookmarks = { nav.navigate("bookmarks") },
                            onOpenFavourites = { nav.navigate("favourites") },
                            onOpenFollowRequests = { nav.navigate("follow_requests") },
                        )
                    },
                )
            } else {
                // Sub-screens get a title and a back button; compose and
                // login draw their own chrome.
                val title = subScreenTitle(currentRoute, backStackEntry?.arguments)
                if (title != null) {
                    TopAppBar(
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(
                                onClick = { nav.popBackStack() },
                                modifier = Modifier.semantics { contentDescription = "Back" },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        },
                        actions = {
                            if (currentRoute == "tag/{name}") {
                                backStackEntry?.arguments?.getString("name")?.let { name ->
                                    TagFollowButton(
                                        api = api,
                                        name = name,
                                        onNotice = { message -> scope.launch { snackbar.showSnackbar(message) } },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentRoute == TopLevel.Home.route) {
                FloatingActionButton(
                    onClick = { openCompose(null) },
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "New post")
                }
            }
        },
        bottomBar = {
            if (currentRoute in topLevelRoutes) {
                NavigationBar {
                    TopLevel.entries.forEach { item ->
                        val badgeCount = if (item == TopLevel.Notifications) unreadCount else 0
                        val badgeLabel = if (badgeCount >= UnreadStore.WINDOW) "${UnreadStore.WINDOW}+" else badgeCount.toString()
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                if (currentRoute == item.route) {
                                    reselect.tryEmit(item.route)
                                } else {
                                    navigateTopLevel(item.route)
                                }
                            },
                            icon = {
                                if (badgeCount > 0) {
                                    BadgedBox(
                                        badge = { Badge { Text(badgeLabel) } },
                                    ) {
                                        Icon(
                                            item.icon,
                                            contentDescription = "${item.label}, $badgeLabel unread",
                                        )
                                    }
                                } else {
                                    Icon(item.icon, contentDescription = item.label)
                                }
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = TopLevel.Home.route,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(TopLevel.Home.route) {
                var feed by rememberSaveable { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize()) {
                    SecondaryTabRow(selectedTabIndex = feed) {
                        HOME_FEEDS.forEachIndexed { index, (label, _) ->
                            Tab(
                                selected = feed == index,
                                onClick = {
                                    // Re-tapping the active feed tab also jumps to top.
                                    if (feed == index) reselect.tryEmit(TopLevel.Home.route) else feed = index
                                },
                                text = { Text(label) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    key(session.id, feed) {
                        TimelineScreen(
                            api = api,
                            session = session,
                            onOpenThread = openThread,
                            onCompose = openCompose,
                            source = HOME_FEEDS[feed].second,
                            scrollToTop = homeReselect,
                        )
                    }
                }
            }
            composable(TopLevel.Notifications.route) {
                key(session.id) {
                    NotificationsScreen(
                        api = api,
                        session = session,
                        onOpenThread = openThread,
                        onOpenProfile = openProfile,
                        onOpenFollowRequests = { nav.navigate("follow_requests") },
                        onNewestLoaded = { newest ->
                            if (newest != null) scope.launch {
                                unreadStore.setSeenTop(session.id, newest)
                                onUnreadChanged(0)
                            }
                        },
                    )
                }
            }
            composable(TopLevel.Search.route) {
                SearchScreen(
                    api = api,
                    session = session,
                    onOpenThread = openThread,
                    onOpenProfile = openProfile,
                    onOpenTag = openTag,
                )
            }
            composable(TopLevel.Settings.route) {
                key(session.id) {
                    SettingsScreen(
                        session = session,
                        settingsStore = settingsStore,
                        sessionStore = store,
                        onSessionsChanged = onSessionsChanged,
                        onSettingsChanged = onSettingsChanged,
                        onOpenModeration = { nav.navigate("moderation") },
                        onOpenLists = { nav.navigate("lists") },
                    )
                }
            }
            composable("thread/{id}") { entry ->
                val id = entry.arguments?.getString("id")
                if (id == null) {
                    PlaceholderScreen("That post link is malformed.")
                } else {
                    ThreadScreen(
                        api = api,
                        session = session,
                        statusId = id,
                        onOpenThread = openThread,
                        onCompose = { replyTo -> openCompose(replyTo) },
                        onDeleted = { nav.popBackStack() },
                        onEdit = { editId -> nav.navigate("compose?edit=$editId") },
                    )
                }
            }
            composable("compose?replyTo={replyTo}&share={share}&edit={edit}") { entry ->
                val replyToId = entry.arguments?.getString("replyTo")
                val editId = entry.arguments?.getString("edit")
                val fromShare = entry.arguments?.getString("share") == "true"
                // Captured once per composer entry; later shares open a new entry.
                val share = remember(entry) { if (fromShare) shareForCompose else null }
                val close: () -> Unit = {
                    if (fromShare) shareForCompose = null
                    nav.popBackStack()
                }
                var parent by remember(replyToId) { mutableStateOf<Status?>(null) }
                var parentFailed by remember(replyToId) { mutableStateOf(false) }
                LaunchedEffect(replyToId) {
                    if (replyToId != null) {
                        parent = try {
                            ThreadRepository.status(api, replyToId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            parentFailed = true
                            null
                        }
                    }
                }
                // Edit mode: the post itself plus its editable source text.
                var editTarget by remember(editId) { mutableStateOf<Status?>(null) }
                var editSource by remember(editId) { mutableStateOf<String?>(null) }
                var editFailed by remember(editId) { mutableStateOf(false) }
                LaunchedEffect(editId) {
                    if (editId == null) return@LaunchedEffect
                    try {
                        val target = ThreadRepository.status(api, editId)
                        // Prefer the server's stored source; fall back to the
                        // status's own text field (GTS includes it on own posts).
                        editSource = try {
                            ThreadRepository.source(api, editId).text.takeIf { it.isNotEmpty() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        editTarget = target
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        editFailed = true
                    }
                }
                when {
                    editId != null -> when {
                        editFailed -> PlaceholderScreen("Could not load the post to edit. Go back and try again.")
                        editTarget == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        else -> ComposeScreen(
                            api = api,
                            session = session,
                            replyTo = null,
                            onPosted = { close() },
                            onDismiss = close,
                            editing = editTarget,
                            editSourceText = editSource,
                        )
                    }
                    replyToId == null -> ComposeScreen(
                        api = api,
                        session = session,
                        replyTo = null,
                        onPosted = { close() },
                        onDismiss = close,
                        initialText = share?.text,
                        initialMedia = share?.media.orEmpty(),
                    )
                    // Gate the composer on the parent fetch: composing with
                    // replyTo = null first would render a new-post composer
                    // (wrong draft, wrong visibility default) for a reply.
                    parentFailed -> PlaceholderScreen(
                        "Could not load the post you are replying to. Go back and try again.",
                    )
                    parent == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    else -> ComposeScreen(
                        api = api,
                        session = session,
                        replyTo = parent,
                        onPosted = { close() },
                        onDismiss = close,
                    )
                }
            }
            composable("profile/{id}") { entry ->
                val id = entry.arguments?.getString("id")
                if (id == null) {
                    PlaceholderScreen("That profile link is malformed.")
                } else {
                    key(session.id) {
                        ProfileScreen(
                            api = api,
                            session = session,
                            accountId = id,
                            onOpenThread = openThread,
                            onOpenProfile = openProfile,
                            onOpenAccountList = { kind -> nav.navigate("profile/$id/$kind") },
                        )
                    }
                }
            }
            composable("profile/{id}/{kind}") { entry ->
                val id = entry.arguments?.getString("id")
                val kind = entry.arguments?.getString("kind")
                if (id == null || (kind != "followers" && kind != "following")) {
                    PlaceholderScreen("That account list link is malformed.")
                } else {
                    AccountListScreen(
                        api = api,
                        session = session,
                        accountId = id,
                        kind = kind,
                        onOpenProfile = openProfile,
                    )
                }
            }
            composable("follow_requests") {
                FollowRequestsScreen(api = api, session = session, onChanged = { })
            }
            composable("bookmarks") {
                key(session.id) {
                    TimelineScreen(
                        api = api,
                        session = session,
                        onOpenThread = openThread,
                        onCompose = openCompose,
                        source = TimelineSource.Bookmarks,
                    )
                }
            }
            composable("favourites") {
                key(session.id) {
                    TimelineScreen(
                        api = api,
                        session = session,
                        onOpenThread = openThread,
                        onCompose = openCompose,
                        source = TimelineSource.Favourites,
                    )
                }
            }
            composable("lists") {
                key(session.id) {
                    ListsScreen(
                        api = api,
                        session = session,
                        onOpenList = { record ->
                            nav.navigate("list_members/${record.id}/${Uri.encode(record.title)}")
                        },
                    )
                }
            }
            composable("list_members/{id}/{title}") { entry ->
                val id = entry.arguments?.getString("id")
                val title = entry.arguments?.getString("title").orEmpty()
                if (id == null) {
                    PlaceholderScreen("That list link is malformed.")
                } else {
                    key(session.id, id) {
                        ListMembersScreen(
                            api = api,
                            session = session,
                            listId = id,
                            listTitle = title,
                            onOpenTimeline = { nav.navigate("list_tl/$id/${Uri.encode(title)}") },
                        )
                    }
                }
            }
            composable("list_tl/{id}/{title}") { entry ->
                val id = entry.arguments?.getString("id")
                val title = entry.arguments?.getString("title").orEmpty()
                if (id == null) {
                    PlaceholderScreen("That list link is malformed.")
                } else {
                    key(session.id, id) {
                        TimelineScreen(
                            api = api,
                            session = session,
                            onOpenThread = openThread,
                            onCompose = openCompose,
                            source = TimelineSource.ListTimeline(id, title),
                        )
                    }
                }
            }
            composable("catchup") {
                key(session.id) {
                    CatchUpScreen(api = api, session = session)
                }
            }
            composable("insights") {
                key(session.id) {
                    InsightsScreen(api = api, session = session)
                }
            }
            composable("moderation") {
                key(session.id) {
                    ModerationScreen(api = api, session = session)
                }
            }
            composable("tag/{name}") { entry ->
                val name = entry.arguments?.getString("name")
                if (name == null) {
                    PlaceholderScreen("That hashtag link is malformed.")
                } else {
                    key(session.id, name) {
                        TimelineScreen(
                            api = api,
                            session = session,
                            onOpenThread = openThread,
                            onCompose = openCompose,
                            source = TimelineSource.Hashtag(name),
                        )
                    }
                }
            }
            composable("login") {
                val entrySessionId = remember { session.id }
                LoginScreen(
                    onLoggedIn = { /* redirect path updates the session */ },
                    onCancel = { nav.popBackStack() },
                )
                LaunchedEffect(session.id) {
                    if (session.id != entrySessionId) nav.popBackStack()
                }
            }
        }
    }
    }
}

/** Top-bar title for a non-top-level route; null where the screen draws its own chrome. */
private fun subScreenTitle(route: String?, args: Bundle?): String? = when (route) {
    "thread/{id}" -> "Post"
    "profile/{id}" -> "Profile"
    "profile/{id}/{kind}" -> if (args?.getString("kind") == "following") "Following" else "Followers"
    "follow_requests" -> "Follow requests"
    "bookmarks" -> "Bookmarks"
    "favourites" -> "Favourites"
    "tag/{name}" -> "#" + (args?.getString("name") ?: "")
    else -> null
}

@Composable
private fun PlaceholderScreen(body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
