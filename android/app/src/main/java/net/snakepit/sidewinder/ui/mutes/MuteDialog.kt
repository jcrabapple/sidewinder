package net.snakepit.sidewinder.ui.mutes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.mute.MuteRepository
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError

/** One mute-duration choice: seconds (0 = indefinite) and its label. */
private data class DurationChoice(val seconds: Long, val label: String)

private val DURATIONS = listOf(
    DurationChoice(3600L, "1 hour"),
    DurationChoice(21600L, "6 hours"),
    DurationChoice(86400L, "1 day"),
    DurationChoice(604800L, "7 days"),
    DurationChoice(2592000L, "30 days"),
    DurationChoice(15552000L, "180 days"),
    DurationChoice(0L, "Indefinite"),
)

/**
 * Mute confirmation dialog for [account]. Duration chips (1 hour through
 * 180 days, plus indefinite), a "Also mute notifications" switch that
 * defaults to on, and Confirm/Cancel buttons. Confirm shows progress on
 * the button only; on success the dialog closes with a notice, and on
 * failure an inline error names the problem so the user can retry.
 *
 * [onDismiss] receives the success notice (e.g. "Muted @user"), or null
 * when the dialog closes without completing the mute.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MuteDialog(
    account: Account,
    api: Api,
    token: String,
    onDismiss: (notice: String?) -> Unit,
) {
    var selectedSeconds by remember { mutableStateOf(0L) }
    var alsoNotifications by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun confirm() {
        if (submitting) return
        submitting = true
        errorText = null
        scope.launch {
            try {
                MuteRepository.mute(api, token, account.id, notifications = alsoNotifications, durationSeconds = selectedSeconds)
                onDismiss("Muted @${account.acct}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                submitting = false
                errorText = "Could not mute @${account.acct}: the server returned error ${e.status}. Try again."
            } catch (e: Exception) {
                submitting = false
                errorText = "Could not mute @${account.acct}: ${e.message ?: "network error"}. Check your connection and try again."
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss(null) },
        title = { Text("Mute @${account.acct}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "You will not see posts from this account in your home timelines. " +
                        "They can still follow and interact with you.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Duration", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DURATIONS.forEach { choice ->
                        val label = "Mute duration: ${choice.label}"
                        FilterChip(
                            selected = selectedSeconds == choice.seconds,
                            onClick = { selectedSeconds = choice.seconds },
                            label = { Text(choice.label) },
                            modifier = Modifier.semantics { contentDescription = label },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = alsoNotifications,
                            role = Role.Switch,
                            onValueChange = { alsoNotifications = it },
                        )
                        .semantics { contentDescription = "Also mute notifications from this account" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Also mute notifications", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = alsoNotifications,
                        onCheckedChange = null,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (submitting) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = "Muting account" },
                )
            } else {
                TextButton(
                    onClick = ::confirm,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Confirm mute" },
                ) {
                    Text("Confirm")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onDismiss(null) },
                enabled = !submitting,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Cancel mute" },
            ) {
                Text("Cancel")
            }
        },
    )
}
