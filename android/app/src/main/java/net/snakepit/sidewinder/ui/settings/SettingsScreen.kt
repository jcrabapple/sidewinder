@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.snakepit.sidewinder.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.defaultMinSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.datastore.SessionStore
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.settings.AppSettings
import net.snakepit.sidewinder.core.settings.SettingsStore
import net.snakepit.sidewinder.core.settings.ThemePref

private val themeLabels = mapOf(
    ThemePref.SYSTEM to "System",
    ThemePref.DARK to "Dark",
    ThemePref.LIGHT to "Light",
)

private val visibilityLabels = mapOf(
    "public" to "Public",
    "unlisted" to "Unlisted",
    "private" to "Private",
    "direct" to "Direct",
)

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

        ThemeRow(
            current = loaded.theme,
            onSelect = { theme -> apply { it.copy(theme = theme) } },
        )

        ToggleRow(
            title = "Relative times",
            subtitle = "Show timestamps like \"3m\" instead of absolute dates.",
            checked = loaded.relativeTimes,
            label = "Relative times toggle",
            onCheckedChange = { value -> apply { it.copy(relativeTimes = value) } },
        )

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

        ToggleRow(
            title = "Background notifications",
            subtitle = "Checks for new notifications every 15 minutes in the background. " +
                "Uses the notification permission.",
            checked = loaded.backgroundNotifications,
            label = "Background notifications toggle",
            onCheckedChange = { value -> apply { it.copy(backgroundNotifications = value) } },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable(onClick = onOpenLists)
                .semantics { contentDescription = "Manage your lists" }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = "Lists", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Create lists of accounts and follow their timelines.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (session.scopes?.split(Regex("\\s+"))?.contains("admin") == true) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(onClick = onOpenModeration)
                    .semantics { contentDescription = "Open moderation tools" }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = "Moderation", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Approve signups, handle reports, suspend accounts, manage domain blocks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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

/** Theme picker as an exposed dropdown (System / Dark / Light). */
@Composable
private fun ThemeRow(
    current: ThemePref,
    onSelect: (ThemePref) -> Unit,
) {
    DropdownSettingRow(
        title = "Theme",
        currentLabel = themeLabels.getValue(current),
        contentDescription = "Theme, currently ${themeLabels.getValue(current).lowercase()}",
        options = ThemePref.entries.map { it to themeLabels.getValue(it) },
        onSelect = { pref -> onSelect(pref) },
    )
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
private fun <T> DropdownSettingRow(
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
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics { this.contentDescription = "$label, ${if (checked) "on" else "off"}" }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier
                .padding(start = 16.dp)
                .clearAndSetSemantics { },
        )
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
