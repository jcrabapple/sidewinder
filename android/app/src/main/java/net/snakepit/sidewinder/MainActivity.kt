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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import net.snakepit.sidewinder.R
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.datastore.SessionStore
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.notify.NotifyScheduler
import net.snakepit.sidewinder.core.notify.NotifyState
import net.snakepit.sidewinder.core.notifications.UnreadStore
import net.snakepit.sidewinder.core.search.SearchRepository
import net.snakepit.sidewinder.core.settings.AppSettings
import net.snakepit.sidewinder.core.settings.SettingsStore
import net.snakepit.sidewinder.core.settings.ThemePref
import net.snakepit.sidewinder.core.thread.ThreadRepository
import net.snakepit.sidewinder.core.filters.FilterCache
import net.snakepit.sidewinder.core.stream.StreamEvent
import net.snakepit.sidewinder.core.stream.StreamEventBus
import net.snakepit.sidewinder.core.stream.StreamingClient
import net.snakepit.sidewinder.core.timeline.TimelineSource
import net.snakepit.sidewinder.ui.account.AccountSwitcher
import net.snakepit.sidewinder.ui.catchup.CatchUpScreen
import net.snakepit.sidewinder.ui.insights.InsightsScreen
import net.snakepit.sidewinder.ui.lists.ListMembersScreen
import net.snakepit.sidewinder.ui.lists.ListsScreen
import net.snakepit.sidewinder.ui.moderation.ModerationScreen
import net.snakepit.sidewinder.ui.mutes.MutesScreen
import net.snakepit.sidewinder.ui.app.AppActions
import net.snakepit.sidewinder.ui.app.LocalAppActions
import net.snakepit.sidewinder.ui.app.LocalAppSettings
import net.snakepit.sidewinder.ui.app.openInBrowser
import net.snakepit.sidewinder.ui.compose.ComposeScreen
import net.snakepit.sidewinder.ui.dm.DmsScreen
import net.snakepit.sidewinder.ui.filters.FiltersScreen
import net.snakepit.sidewinder.ui.login.LoginScreen
import net.snakepit.sidewinder.ui.login.handleOauthRedirect
import net.snakepit.sidewinder.ui.notifications.NotificationsScreen
import net.snakepit.sidewinder.ui.profile.AccountListScreen
import net.snakepit.sidewinder.ui.profile.EditProfileScreen
import net.snakepit.sidewinder.ui.profile.ProfileScreen
import net.snakepit.sidewinder.ui.requests.FollowRequestsScreen
import net.snakepit.sidewinder.ui.search.SearchScreen
import net.snakepit.sidewinder.ui.schedule.ScheduledScreen
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

private fun topLevelBadgeLabel(count: Int): String =
    if (count >= UnreadStore.WINDOW) "${UnreadStore.WINDOW}+" else count.toString()

@Composable
private fun TopLevelNavigationIcon(item: TopLevel, badgeCount: Int) {
    if (badgeCount > 0) {
        BadgedBox(
            badge = { Badge { Text(topLevelBadgeLabel(badgeCount)) } },
        ) {
            Icon(item.icon, contentDescription = null)
        }
    } else {
        Icon(item.icon, contentDescription = item.label)
    }
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
    var startupError by remember { mutableStateOf<String?>(null) }
    var startupRetry by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(AppSettings()) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var askedPermission by rememberSaveable { mutableStateOf(false) }
    val unreadStore = remember { UnreadStore(activity) }
    var unreadCount by remember { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val redirect by activity.redirectUri

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* background polling just stays silent without the permission */ }

    LaunchedEffect(startupRetry) {
        sessionLoaded = false
        startupError = null
        try {
            // Commit only a complete load. In particular, a settings failure
            // must not turn an unreadable account file into a signed-out UI.
            val loadedSettings = settingsStore.settings()
            val loadedSessions = store.list()
            val loadedSession = store.active()
            settings = loadedSettings
            sessions = loadedSessions
            session = loadedSession
            loadedSession?.let { s -> unreadCount = unreadStore.count(s.id) }
            sessionLoaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            startupError = "Sidewinder could not read your saved accounts and settings. Nothing has been changed or signed out. Retry to read them again."
        }
    }

    // Recount on account changes and every foreground return. Each account's
    // effect owns its jobs, so a slow response from the previous account can
    // never overwrite the newly selected account's badge.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(session?.id, sessionLoaded, lifecycleOwner) {
        val target = session ?: return@LaunchedEffect
        if (!sessionLoaded) return@LaunchedEffect

        suspend fun recount() {
            if (session?.id != target.id) return
            unreadCount = unreadStore.count(target.id)
            try {
                val count = UnreadStore.recount(Api(target.base, target.token), target.id, unreadStore)
                if (session?.id == target.id) unreadCount = count
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session?.id == target.id) unreadCount = unreadStore.count(target.id)
            }
        }

        var recountJob: Job? = null
        fun requestRecount() {
            recountJob?.cancel()
            recountJob = launch { recount() }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) requestRecount()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        requestRecount()
        try {
            awaitCancellation()
        } finally {
            lifecycleOwner.lifecycle.removeObserver(observer)
            recountJob?.cancel()
        }
    }

    // Keep background polling in step with the setting and the session,
    // and ask for the notification permission once when enabling.
    // A signed-out app (session == null) must cancel the periodic work:
    // the old early-return left polling running after the last sign-out.
    LaunchedEffect(settings.backgroundNotifications, session?.id, sessionLoaded, startupError) {
        if (!sessionLoaded || startupError != null) return@LaunchedEffect
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
    LaunchedEffect(redirect, sessionLoaded, startupError) {
        if (!sessionLoaded || startupError != null) return@LaunchedEffect
        val uri = redirect ?: return@LaunchedEffect
        try {
            handleOauthRedirect(activity, uri)
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
        ThemePref.AMOLED -> true
        ThemePref.SYSTEM -> isSystemInDarkTheme()
    }
    SidewinderTheme(
        themeId = settings.themeId,
        darkTheme = dark,
        dynamicColor = settings.dynamicColor,
        pureBlack = settings.pureBlack,
        reduceMotion = settings.reduceMotion,
    ) {
      // The theme can now be light or dark regardless of the system; keep
      // status/nav icons visible and repaint the window behind every theme.
      val view = LocalView.current
      val window = activity.window
      val scheme = MaterialTheme.colorScheme
      SideEffect {
          val lightIcons = scheme.background.luminance() > 0.5f
          WindowCompat.getInsetsController(window, view).apply {
              isAppearanceLightStatusBars = lightIcons
              isAppearanceLightNavigationBars = lightIcons
          }
          window.setBackgroundDrawable(ColorDrawable(scheme.background.toArgb()))
      }
      val baseDensity = LocalDensity.current
      CompositionLocalProvider(
          LocalAppSettings provides settings,
          LocalDensity provides Density(
              density = baseDensity.density,
              fontScale = baseDensity.fontScale * settings.textScale.coerceIn(0.8f, 1.3f),
          ),
      ) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val active = session
                val loadError = startupError
                when {
                    loadError != null -> StartupErrorScreen(
                        message = loadError,
                        onRetry = { startupRetry++ },
                    )
                    !sessionLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    active == null -> LoginScreen(
                        onLoggedIn = { /* redirect path updates session above */ },
                        onCancel = null,
                    )
                    else -> key(active.id) {
                        AppTabs(
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
    val notifyState = remember { NotifyState(activity) }
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val api = remember(session.token) { Api(session.base, session.token) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    // Resolve filters alongside the first timeline request, not only after
    // its rows compose. Cards still keep their neutral cold-cache guard.
    LaunchedEffect(api, session.id) {
        try {
            FilterCache.current(api, session.token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Card-level loading exposes the content if filters are unavailable.
        }
    }
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

    // Live streaming (foreground only): the user stream carries home-timeline
    // updates AND notifications for this session. Events patch the cached
    // feeds directly and reach the open notifications screen via the bus;
    // the 15-minute poll worker keeps covering background badge counts.
    var streamingClient by remember { mutableStateOf<StreamingClient?>(null) }
    val liveCurrentRoute by rememberUpdatedState(currentRoute)
    val liveUnreadCount by rememberUpdatedState(unreadCount)
    fun restartStreaming(live: Boolean) {
        streamingClient?.stop()
        streamingClient = null
        if (!live) return
        StreamingClient(
            baseUrl = session.base,
            token = session.token,
            scope = scope,
            onEvent = { event ->
                when (event) {
                    is StreamEvent.StatusUpdate -> TimelineStore.applyStreamed(session.id, event.status)
                    is StreamEvent.StatusDeleted -> TimelineStore.applyDelete(event.statusId)
                    is StreamEvent.NotificationArrived -> {
                        StreamEventBus.publish(event)
                        if (liveCurrentRoute != TopLevel.Notifications.route) {
                            scope.launch {
                                // The store serializes a burst on the socket
                                // thread; snapshot state alone under-counts it.
                                val nextCount = unreadStore.increment(session.id)
                                if (liveCurrentRoute != TopLevel.Notifications.route) {
                                    onUnreadChanged(maxOf(liveUnreadCount, nextCount))
                                }
                            }
                        }
                    }
                    is StreamEvent.FiltersChanged -> FilterCache.invalidate(session.token)
                }
            },
            onConnectionState = { },
        ).also { client ->
            streamingClient = client
            client.start()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val streamingEnabled = LocalAppSettings.current.liveStreaming
    DisposableEffect(lifecycleOwner, session.id, streamingEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (streamingEnabled) restartStreaming(true)
                Lifecycle.Event.ON_STOP -> restartStreaming(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // The activity may already be resumed when this effect first composes.
        if (streamingEnabled && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            restartStreaming(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            restartStreaming(false)
        }
    }

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
            openEditProfile = { nav.navigate("edit_profile") },
            openQuote = { id -> nav.navigate("compose?quote=$id") },
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (useNavigationRail && currentRoute in topLevelRoutes) {
                NavigationRail {
                    TopLevel.entries.forEach { item ->
                        val badgeCount = if (item == TopLevel.Notifications) unreadCount else 0
                        val badgeLabel = topLevelBadgeLabel(badgeCount)
                        NavigationRailItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                if (currentRoute == item.route) {
                                    reselect.tryEmit(item.route)
                                } else {
                                    navigateTopLevel(item.route)
                                }
                            },
                            modifier = Modifier.semantics {
                                if (badgeCount > 0) stateDescription = "$badgeLabel unread"
                            },
                            icon = { TopLevelNavigationIcon(item, badgeCount) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
    Scaffold(
        modifier = Modifier.weight(1f),
        // Not edge-to-edge: the decor already applies system-bar insets,
        // so the default contentWindowInsets would pad the content a
        // second time below the top bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (currentRoute in topLevelRoutes) {
                TopAppBar(
                    // A compact feed header leaves more room for posts while
                    // retaining 48dp touch targets on every action.
                    modifier = Modifier.height(56.dp),
                    // The activity is not edge-to-edge (the system already
                    // pads the content below the status bar); the default
                    // windowInsets would add that height a second time.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(R.drawable.sidewinder_logo),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Sidewinder",
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        if (currentRoute == TopLevel.Notifications.route || currentRoute == TopLevel.Search.route) {
                            IconButton(
                                onClick = { openCompose(null) },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "New post")
                            }
                        }
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
            if (!useNavigationRail && currentRoute in topLevelRoutes) {
                NavigationBar(
                    modifier = Modifier.height(64.dp),
                    // The decor already keeps the app above system navigation.
                    // Applying the default inset here would pad it a second time
                    // and squeeze the items out of a compact bar.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    TopLevel.entries.forEach { item ->
                        val badgeCount = if (item == TopLevel.Notifications) unreadCount else 0
                        val badgeLabel = topLevelBadgeLabel(badgeCount)
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                if (currentRoute == item.route) {
                                    reselect.tryEmit(item.route)
                                } else {
                                    navigateTopLevel(item.route)
                                }
                            },
                            // The unread count is announced once, in the item's
                            // merged semantics; a contentDescription on the icon
                            // would repeat the label ("Notifications, Notifications").
                            modifier = Modifier.semantics {
                                if (badgeCount > 0) stateDescription = "$badgeLabel unread"
                            },
                            icon = { TopLevelNavigationIcon(item, badgeCount) },
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
                            showDisplayFilters = true,
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
                            if (newest != null) {
                                // Clear the visible badge even if a disk or
                                // worker-state failure needs recovery below.
                                onUnreadChanged(0)
                                scope.launch {
                                    try {
                                        unreadStore.setSeenTop(session.id, newest)
                                        try {
                                            notifyState.setLastNotifiedId(session.id, newest)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            notifyState.quarantineUnreadable()
                                            notifyState.setLastNotifiedId(session.id, newest)
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar(
                                            "Notifications are read on screen, but the read position could not be saved. It may return after a restart.",
                                        )
                                    }
                                }
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
                    onCompose = openCompose,
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
                        onOpenFilters = { nav.navigate("filters") },
                        onOpenMutes = { nav.navigate("mutes") },
                        onOpenScheduled = { nav.navigate("scheduled") },
                        onOpenDms = { nav.navigate("dm") },
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
            composable("compose?replyTo={replyTo}&share={share}&edit={edit}&quote={quote}") { entry ->
                val replyToId = entry.arguments?.getString("replyTo")
                val editId = entry.arguments?.getString("edit")
                val quoteId = entry.arguments?.getString("quote")
                val fromShare = entry.arguments?.getString("share") == "true"
                // Captured once per composer entry; later shares open a new entry.
                val share = remember(entry) { if (fromShare) shareForCompose else null }
                val close: () -> Unit = {
                    if (fromShare) shareForCompose = null
                    nav.popBackStack()
                }
                var parent by remember(replyToId) { mutableStateOf<Status?>(null) }
                var parentFailed by remember(replyToId) { mutableStateOf(false) }
                // Re-run the fetch on Retry; the id alone would not re-fire.
                var parentRetry by remember(replyToId) { mutableIntStateOf(0) }
                LaunchedEffect(replyToId, parentRetry) {
                    if (replyToId != null) {
                        parentFailed = false
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
                var editRetry by remember(editId) { mutableIntStateOf(0) }
                LaunchedEffect(editId, editRetry) {
                    if (editId == null) return@LaunchedEffect
                    editFailed = false
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
                // Quote mode: the quoted post, fetched and gated like replyTo.
                var quoteTarget by remember(quoteId) { mutableStateOf<Status?>(null) }
                var quoteFailed by remember(quoteId) { mutableStateOf(false) }
                var quoteRetry by remember(quoteId) { mutableIntStateOf(0) }
                LaunchedEffect(quoteId, quoteRetry) {
                    if (quoteId == null) return@LaunchedEffect
                    quoteFailed = false
                    quoteTarget = try {
                        ThreadRepository.status(api, quoteId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        quoteFailed = true
                        null
                    }
                }
                when {
                    editId != null -> when {
                        editFailed -> LoadFailedScreen(
                            message = "Could not load the post to edit. Check your connection, then retry.",
                            onBack = { nav.popBackStack() },
                            onRetry = {
                                editFailed = false
                                editRetry++
                            },
                        )
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
                    quoteId != null -> when {
                        quoteFailed -> LoadFailedScreen(
                            message = "Could not load the post you are quoting. Check your connection, then retry.",
                            onBack = { nav.popBackStack() },
                            onRetry = {
                                quoteFailed = false
                                quoteRetry++
                            },
                        )
                        quoteTarget == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        else -> ComposeScreen(
                            api = api,
                            session = session,
                            replyTo = null,
                            quoting = quoteTarget,
                            onPosted = { close() },
                            onDismiss = close,
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
                    parentFailed -> LoadFailedScreen(
                        message = "Could not load the post you are replying to. Check your connection, then retry.",
                        onBack = { nav.popBackStack() },
                        onRetry = {
                            parentFailed = false
                            parentRetry++
                        },
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
            composable("filters") {
                key(session.id) {
                    FiltersScreen(api = api, session = session)
                }
            }
            composable("mutes") {
                key(session.id) {
                    MutesScreen(api = api, session = session, onOpenProfile = openProfile)
                }
            }
            composable("scheduled") {
                key(session.id) {
                    ScheduledScreen(api = api, session = session)
                }
            }
            composable("dm") {
                key(session.id) {
                    DmsScreen(api = api, session = session, onOpenThread = openThread)
                }
            }
            composable("edit_profile") {
                key(session.id) {
                    EditProfileScreen(api = api, session = session, onSaved = { nav.popBackStack() })
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
    }
}

/** Top-bar title for a non-top-level route; null where the screen draws its own chrome. */
private fun subScreenTitle(route: String?, args: Bundle?): String? = when (route) {
    "thread/{id}" -> "Post"
    "profile/{id}" -> "Profile"
    "profile/{id}/{kind}" -> if (args?.getString("kind") == "following") "Following" else "Followers"
    "follow_requests" -> "Follow requests"
    "filters" -> "Filters"
    "mutes" -> "Muted accounts"
    "scheduled" -> "Scheduled posts"
    "dm" -> "Direct messages"
    "edit_profile" -> "Edit profile"
    "bookmarks" -> "Bookmarks"
    "favourites" -> "Favourites"
    "tag/{name}" -> "#" + (args?.getString("name") ?: "")
    "lists" -> "Lists"
    "list_members/{id}/{title}" -> args?.getString("title")?.takeIf { it.isNotBlank() } ?: "List members"
    "list_tl/{id}/{title}" -> args?.getString("title")?.takeIf { it.isNotBlank() } ?: "List"
    "catchup" -> "Catch up"
    "insights" -> "Insights"
    "moderation" -> "Moderation"
    else -> null
}

@Composable
private fun StartupErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Retry reading saved accounts and settings" },
        ) {
            Text("Retry")
        }
    }
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

/** A failed pre-composer load: explain, then offer Back and Retry. */
@Composable
private fun LoadFailedScreen(message: String, onBack: () -> Unit, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onBack,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Go back" },
            ) {
                Text("Back")
            }
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Retry loading the post" },
            ) {
                Text("Retry")
            }
        }
    }
}
