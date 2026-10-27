@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.snakepit.sidewinder.ui.settings

import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.defaultMinSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.snakepit.sidewinder.core.datastore.SessionStore
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.settings.AppSettings
import net.snakepit.sidewinder.core.settings.SettingsStore
import net.snakepit.sidewinder.core.settings.ThemePref
import net.snakepit.sidewinder.core.settings.migrateTheme
import net.snakepit.sidewinder.ui.theme.AppThemes
import net.snakepit.sidewinder.ui.theme.appSwitchColors

private val visibilityLabels = mapOf(
    "public" to "Public",
    "unlisted" to "Unlisted",
    "private" to "Private",
    "direct" to "Direct",
)

/** Visibility values a settings file may carry; anything else is invalid. */
internal val VALID_VISIBILITIES = visibilityLabels.keys

/** Interface text-scale choices; 100% follows the system font size. */
internal val textScaleOptions = listOf(
    0.8f to "80%",
    0.9f to "90%",
    1.0f to "100%",
    1.1f to "110%",
    1.2f to "120%",
    1.3f to "130%",
)

internal fun textScaleLabel(value: Float): String =
    textScaleOptions.firstOrNull { it.first == value }?.second ?: "100%"

/** Portable copy of the app settings for export/import (SAF JSON files). */
@Serializable
internal data class SettingsBackup(
    val theme: String,
    val relativeTimes: Boolean,
    val defaultVisibility: String,
    val requireAltText: Boolean,
    val backgroundNotifications: Boolean,
    val hideEngagementCounts: Boolean,
    val confirmBeforeBoost: Boolean,
    val confirmBeforeFollow: Boolean,
    val hideMediaPreviews: Boolean,
    val hideNoAltPosts: Boolean,
    val collapseLongPosts: Boolean,
    val dynamicColor: Boolean,
    val textScale: Float,
    val reduceMotion: Boolean,
    /** Default true so files exported before live streaming existed keep it on. */
    val liveStreaming: Boolean = true,
    /** Default keeps the Sidewinder look for backups made before theming. */
    val themeId: String = "sidewinder",
    /** Default false; legacy AMOLED in [theme] still migrates below. */
    val pureBlack: Boolean = false,
    /** Feed display filters; defaults match the old per-screen chips. */
    val showBoosts: Boolean = true,
    val showReplies: Boolean = true,
    val hideSelfBoosts: Boolean = false,
) {
    fun toSettings(): AppSettings {
        val (mode, migratedPureBlack) = migrateTheme(theme, pureBlack)
        return AppSettings(
            theme = mode,
            pureBlack = migratedPureBlack,
            relativeTimes = relativeTimes,
            defaultVisibility = if (defaultVisibility in VALID_VISIBILITIES) defaultVisibility else "public",
            requireAltText = requireAltText,
            backgroundNotifications = backgroundNotifications,
            hideEngagementCounts = hideEngagementCounts,
            confirmBeforeBoost = confirmBeforeBoost,
            confirmBeforeFollow = confirmBeforeFollow,
            hideMediaPreviews = hideMediaPreviews,
            hideNoAltPosts = hideNoAltPosts,
            collapseLongPosts = collapseLongPosts,
            dynamicColor = dynamicColor,
            textScale = textScale.coerceIn(0.8f, 1.3f),
            reduceMotion = reduceMotion,
            liveStreaming = liveStreaming,
            themeId = if (AppThemes.all.any { it.id == themeId }) themeId else "sidewinder",
            showBoosts = showBoosts,
            showReplies = showReplies,
            hideSelfBoosts = hideSelfBoosts,
        )
    }

    companion object {
        fun from(s: AppSettings) = SettingsBackup(
            theme = s.theme.name,
            relativeTimes = s.relativeTimes,
            defaultVisibility = s.defaultVisibility,
            requireAltText = s.requireAltText,
            backgroundNotifications = s.backgroundNotifications,
            hideEngagementCounts = s.hideEngagementCounts,
            confirmBeforeBoost = s.confirmBeforeBoost,
            confirmBeforeFollow = s.confirmBeforeFollow,
            hideMediaPreviews = s.hideMediaPreviews,
            hideNoAltPosts = s.hideNoAltPosts,
            collapseLongPosts = s.collapseLongPosts,
            dynamicColor = s.dynamicColor,
            textScale = s.textScale,
            reduceMotion = s.reduceMotion,
            liveStreaming = s.liveStreaming,
            themeId = s.themeId,
            showBoosts = s.showBoosts,
            showReplies = s.showReplies,
            hideSelfBoosts = s.hideSelfBoosts,
            pureBlack = s.pureBlack,
        )
    }
}

private val settingsJson = Json { ignoreUnknownKeys = true }

/**
 * App settings. Port of web `src/views/Settings.tsx`: local JSON-backed
 * preferences edited in place via [SettingsStore.update], plus sign out
 * for the active session. [onSessionsChanged] runs after the session is
 * removed so the shell can re-evaluate the signed-in state.
 */
@Composable
fun SettingsScreen(
    session: Session,
    settingsStore: SettingsStore,
    sessionStore: SessionStore,
    onSessionsChanged: suspend () -> Unit,
    onSettingsChanged: suspend () -> Unit = {},
    onOpenModeration: () -> Unit = {},
    onOpenLists: () -> Unit = {},
    onOpenFilters: () -> Unit = {},
    onOpenMutes: () -> Unit = {},
    onOpenScheduled: () -> Unit = {},
    onOpenDms: () -> Unit = {},
) {
    var settings by remember { mutableStateOf<AppSettings?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var persistError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(settingsStore) {
        settings = settingsStore.settings()
    }

    fun apply(transform: (AppSettings) -> AppSettings) {
        val previous = settings
        scope.launch {
            persistError = null
            settings = try {
                settingsStore.update(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Persistence failed: keep the previous settings on screen
                // and say so instead of dying silently (or crashing).
                persistError = "Could not save that change: ${e.message ?: "storage error"}. Try again."
                previous
            }
            onSettingsChanged()
        }
    }

    // ---- Export / import via SAF --------------------------------------
    val context = LocalContext.current
    var transferMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val current = settings
        if (uri == null || current == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(settingsJson.encodeToString(SettingsBackup.serializer(), SettingsBackup.from(current)).toByteArray())
                    } ?: throw IllegalStateException("storage unavailable")
                }
                transferMessage = "Settings exported."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transferMessage = "Could not export settings: ${e.message ?: "storage error"}."
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }
                val restored = text?.let { settingsJson.decodeFromString(SettingsBackup.serializer(), it) }?.toSettings()
                if (restored == null) throw IllegalStateException("empty file")
                apply { restored }
                transferMessage = "Settings imported."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transferMessage = "Could not import settings: not a Sidewinder settings file."
            }
        }
    }

    val loaded = settings
    if (loaded == null) {
        Surface(modifier = Modifier.fillMaxSize()) {}
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        persistError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        AppearanceSection(
            settings = loaded,
            onMode = { theme -> apply { it.copy(theme = theme) } },
            onThemeId = { themeId -> apply { it.copy(themeId = themeId) } },
            onDynamicColor = { value -> apply { it.copy(dynamicColor = value) } },
            onPureBlack = { value -> apply { it.copy(pureBlack = value) } },
            onTextScale = { value -> apply { it.copy(textScale = value) } },
        )

        SettingsSectionHeader("Notifications")
        ToggleRow(
            title = "Background notifications",
            subtitle = "Checks for new notifications every 15 minutes in the background. " +
                "Uses the notification permission.",
            checked = loaded.backgroundNotifications,
            label = "Background notifications toggle",
            onCheckedChange = { value -> apply { it.copy(backgroundNotifications = value) } },
        )
        SettingsNavigationRow(
            title = "Direct messages",
            subtitle = "Conversations from direct and mentioned posts.",
            icon = Icons.Outlined.MailOutline,
            description = "Open your direct messages",
            onClick = onOpenDms,
        )

        SettingsSectionHeader("Reading")
        Text(
            text = "Feed filters apply to the Home, Local and Federated feeds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        ToggleRow(
            title = "Show boosts",
            subtitle = "Include posts other people boosted.",
            checked = loaded.showBoosts,
            label = "Show boosts toggle",
            onCheckedChange = { value -> apply { it.copy(showBoosts = value) } },
        )
        ToggleRow(
            title = "Show replies",
            subtitle = "Include replies to other posts.",
            checked = loaded.showReplies,
            label = "Show replies toggle",
            onCheckedChange = { value -> apply { it.copy(showReplies = value) } },
        )
        ToggleRow(
            title = "Hide self-boosts",
            subtitle = "Hide boosts where people share their own posts.",
            checked = loaded.hideSelfBoosts,
            label = "Hide self-boosts toggle",
            onCheckedChange = { value -> apply { it.copy(hideSelfBoosts = value) } },
        )
        ToggleRow(
            title = "Collapse long posts",
            subtitle = "Shorten long timeline posts behind a Show more button.",
            checked = loaded.collapseLongPosts,
            label = "Collapse long posts toggle",
            onCheckedChange = { value -> apply { it.copy(collapseLongPosts = value) } },
        )
        ToggleRow(
            title = "Relative times",
            subtitle = "Show timestamps like \"3m\" instead of absolute dates.",
            checked = loaded.relativeTimes,
            label = "Relative times toggle",
            onCheckedChange = { value -> apply { it.copy(relativeTimes = value) } },
        )
        ToggleRow(
            title = "Live streaming",
            subtitle = "New posts and notifications arrive in real time while the app is open. " +
                "Turn off to load only when you refresh.",
            checked = loaded.liveStreaming,
            label = "Live streaming toggle",
            onCheckedChange = { value -> apply { it.copy(liveStreaming = value) } },
        )
        SettingsNavigationRow(
            title = "Lists",
            subtitle = "Create lists of accounts and follow their timelines.",
            icon = Icons.AutoMirrored.Outlined.List,
            description = "Manage your lists",
            onClick = onOpenLists,
        )

        SettingsSectionHeader("Wellbeing")
        ToggleRow(
            title = "Hide engagement counts",
            subtitle = "Don't show reply, boost and favourite counts on posts.",
            checked = loaded.hideEngagementCounts,
            label = "Hide engagement counts toggle",
            onCheckedChange = { value -> apply { it.copy(hideEngagementCounts = value) } },
        )
        ToggleRow(
            title = "Confirm before boosting",
            subtitle = "Ask before sharing a post with your followers.",
            checked = loaded.confirmBeforeBoost,
            label = "Confirm before boosting toggle",
            onCheckedChange = { value -> apply { it.copy(confirmBeforeBoost = value) } },
        )
        ToggleRow(
            title = "Confirm before following",
            subtitle = "Ask before sending a follow request.",
            checked = loaded.confirmBeforeFollow,
            label = "Confirm before following toggle",
            onCheckedChange = { value -> apply { it.copy(confirmBeforeFollow = value) } },
        )
        ToggleRow(
            title = "Hide media previews",
            subtitle = "Don't show images and videos in timelines. Media stays visible in threads.",
            checked = loaded.hideMediaPreviews,
            label = "Hide media previews toggle",
            onCheckedChange = { value -> apply { it.copy(hideMediaPreviews = value) } },
        )
        ToggleRow(
            title = "Hide posts without alt text",
            subtitle = "Only show timeline posts whose media have descriptions.",
            checked = loaded.hideNoAltPosts,
            label = "Hide posts without alt text toggle",
            onCheckedChange = { value -> apply { it.copy(hideNoAltPosts = value) } },
        )
        ToggleRow(
            title = "Reduce motion",
            subtitle = "Tone down animations like the back-to-top pill.",
            checked = loaded.reduceMotion,
            label = "Reduce motion toggle",
            onCheckedChange = { value -> apply { it.copy(reduceMotion = value) } },
        )
        SettingsNavigationRow(
            title = "Filters",
            subtitle = "Hide or blur posts matching keywords you choose.",
            icon = Icons.Outlined.FilterList,
            description = "Manage your filters",
            onClick = onOpenFilters,
        )
        SettingsNavigationRow(
            title = "Muted accounts",
            subtitle = "Accounts you have muted, with unmute shortcuts.",
            icon = Icons.Outlined.Block,
            description = "Manage your muted accounts",
            onClick = onOpenMutes,
        )

        SettingsSectionHeader("Composer")
        VisibilityRow(
            current = loaded.defaultVisibility,
            onSelect = { visibility -> apply { it.copy(defaultVisibility = visibility) } },
        )
        ToggleRow(
            title = "Require alt text",
            subtitle = "Ask for confirmation before posting media without descriptions.",
            checked = loaded.requireAltText,
            label = "Require alt text toggle",
            onCheckedChange = { value -> apply { it.copy(requireAltText = value) } },
        )
        SettingsNavigationRow(
            title = "Scheduled posts",
            subtitle = "Posts waiting to publish at their scheduled time.",
            icon = Icons.Outlined.Schedule,
            description = "Open your scheduled posts",
            onClick = onOpenScheduled,
        )

        if (session.scopes?.split(Regex("\\s+"))?.contains("admin") == true) {
            SettingsSectionHeader("Administration")
            SettingsNavigationRow(
                title = "Moderation",
                subtitle = "Approve signups, handle reports, suspend accounts, manage domain blocks.",
                icon = Icons.Outlined.AdminPanelSettings,
                description = "Open moderation tools",
                onClick = onOpenModeration,
            )
        }

        SettingsSectionHeader("Backup and restore")
        SettingsActionRow(
            title = "Export settings",
            subtitle = "Save your preferences to a file.",
            icon = Icons.Outlined.Download,
            description = "Export settings to a file",
            onClick = {
                transferMessage = null
                exportLauncher.launch("sidewinder-settings.json")
            },
        )
        SettingsActionRow(
            title = "Import settings",
            subtitle = "Restore preferences from an exported file.",
            icon = Icons.Outlined.Upload,
            description = "Import settings from a file",
            onClick = {
                transferMessage = null
                importLauncher.launch(arrayOf("application/json"))
            },
        )

        transferMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        SignOutRow(
            acct = session.account.acct,
            enabled = !signingOut,
            onClick = { confirmSignOut = true },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(text = "Sign out @${session.account.acct}?") },
            text = { Text(text = "You will need to sign in again to use this account on this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        signingOut = true
                        scope.launch {
                            try {
                                sessionStore.remove(session.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                signingOut = false
                                persistError =
                                    "Could not sign out: ${e.message ?: "storage error"}. Try again."
                                return@launch
                            }
                            onSessionsChanged()
                        }
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm sign out of ${session.account.acct}"
                    },
                ) {
                    Text(text = "Sign out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmSignOut = false },
                    modifier = Modifier.semantics {
                        contentDescription = "Cancel sign out"
                    },
                ) {
                    Text(text = "Cancel")
                }
            },
        )
    }
}

/** Default visibility for new posts as an exposed dropdown. */
@Composable
private fun VisibilityRow(
    current: String,
    onSelect: (String) -> Unit,
) {
    val currentLabel = visibilityLabels[current] ?: current
    DropdownSettingRow(
        title = "Default visibility",
        currentLabel = currentLabel,
        contentDescription = "Default visibility for new posts, currently $currentLabel",
        options = visibilityLabels.entries.map { it.key to it.value },
        onSelect = onSelect,
    )
}

/** Shared dropdown row: a 48dp+ labeled field opening a menu of options. */
@Composable
internal fun <T> DropdownSettingRow(
    title: String,
    currentLabel: String,
    contentDescription: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = currentLabel,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .semantics { this.contentDescription = contentDescription },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 240.dp),
            ) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(text = label) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    )
                }
            }
        }
    }
}

/** Labeled switch row with a supporting line, 48dp minimum height.
 *  The whole row is the toggle (role Switch) and the Switch graphic is
 *  cleared from the semantics tree, so screen readers announce the
 *  label and state exactly once. */
@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { if (enabled) onCheckedChange(it) },
                enabled = enabled,
            )
            // Role.Switch on the toggleable row supplies the on/off state;
            // repeating it here makes screen readers announce state twice.
            .semantics { this.contentDescription = label }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                // Dimmed (still readable) when the row is unavailable.
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = appSwitchColors(),
            modifier = Modifier
                .padding(start = 16.dp)
                .clearAndSetSemantics { },
        )
    }
}

/** Navigation destination with one merged, 48dp-minimum click target. */
@Composable
private fun SettingsNavigationRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** File-transfer action, visually distinct from destination rows. */
@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Error-colored sign-out entry point for the active account. */
@Composable
private fun SignOutRow(
    acct: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "Sign out of $acct" }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Sign out @$acct",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Section label kept below the screen title in the visual hierarchy. */
@Composable
internal fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}
