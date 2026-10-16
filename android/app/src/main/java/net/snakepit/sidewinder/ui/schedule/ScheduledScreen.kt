package net.snakepit.sidewinder.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.schedule.ScheduledRepository
import net.snakepit.sidewinder.core.schedule.ScheduledStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Local "Oct 16, 2026, 3:30 PM" label for an ISO scheduled_at stamp. */
internal fun scheduledAtLabel(iso: String): String =
    runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull()
        ?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
        ?: iso

/**
 * Posts waiting to publish. List, reschedule (date + time pickers), and
 * cancel; content edits are not possible server-side (GTS has no
 * scheduled-status content edit route), so the row exposes only the two
 * operations the API supports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(api: Api, session: Session) {
    var state by remember { mutableStateOf<ScheduledState>(ScheduledState.Loading) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    suspend fun load() {
        state = ScheduledState.Loading
        state = try {
            val items = ScheduledRepository.list(api, session.token)
            if (items.isEmpty()) ScheduledState.Empty else ScheduledState.Content(items)
        } catch (e: ApiError) {
            ScheduledState.Error("Could not load scheduled posts: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScheduledState.Error("Could not load scheduled posts: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    androidx.compose.runtime.LaunchedEffect(api, session.id) { load() }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val s = state) {
            ScheduledState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading scheduled posts" },
                )
            }

            is ScheduledState.Error -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { scope.launch { load() } },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Retry loading scheduled posts" },
                    ) {
                        Text("Retry")
                    }
                }
            }

            ScheduledState.Empty -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No scheduled posts. Toggle the clock in the composer to pick a publish time.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is ScheduledState.Content -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(s.items, key = { it.id }) { item ->
                    ScheduledRow(
                        item = item,
                        onReschedule = { millis ->
                            scope.launch {
                                try {
                                    ScheduledRepository.reschedule(api, session.token, item.id, isoFromMillis(millis))
                                    load()
                                    snackbar.showSnackbar("Rescheduled to ${scheduledAtLabel(isoFromMillis(millis))}.")
                                } catch (e: ApiError) {
                                    snackbar.showSnackbar("Could not reschedule: the server returned error ${e.status}. Try again.")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    snackbar.showSnackbar("Could not reschedule: ${e.message ?: "network error"}. Try again.")
                                }
                            }
                        },
                        onCancel = {
                            scope.launch {
                                try {
                                    ScheduledRepository.cancel(api, session.token, item.id)
                                    load()
                                    snackbar.showSnackbar("Scheduled post canceled.")
                                } catch (e: ApiError) {
                                    snackbar.showSnackbar("Could not cancel: the server returned error ${e.status}. Try again.")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    snackbar.showSnackbar("Could not cancel: ${e.message ?: "network error"}. Try again.")
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private sealed interface ScheduledState {
    data object Loading : ScheduledState
    data class Error(val message: String) : ScheduledState
    data object Empty : ScheduledState
    data class Content(val items: List<ScheduledStatus>) : ScheduledState
}

@Composable
private fun ScheduledRow(
    item: ScheduledStatus,
    onReschedule: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var rescheduleOpen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.params.text?.lineSequence()?.firstOrNull().orEmpty().ifBlank { "(no text)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = scheduledAtLabel(item.scheduled_at),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            val extras = buildList {
                if (item.media_attachments.isNotEmpty()) add("${item.media_attachments.size} attachment(s)")
                if (item.params.poll != null) add("Poll")
                item.params.visibility?.let { add(it) }
            }
            if (extras.isNotEmpty()) {
                Text(
                    text = extras.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.semantics { contentDescription = "Actions for this scheduled post" },
            ) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Reschedule") },
                    onClick = {
                        menuOpen = false
                        rescheduleOpen = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Cancel post") },
                    onClick = {
                        menuOpen = false
                        confirmCancel = true
                    },
                )
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel this scheduled post?") },
            text = { Text("It will not be published, and its text and media cannot be recovered here.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCancel = false
                        onCancel()
                    },
                ) { Text("Cancel post", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text("Keep it") }
            },
        )
    }

    if (rescheduleOpen) {
        RescheduleDialog(
            currentIso = item.scheduled_at,
            onDismiss = { rescheduleOpen = false },
            onConfirm = { millis ->
                rescheduleOpen = false
                onReschedule(millis)
            },
        )
    }
}

/** Date picker then time picker; validates the result is at least 5 minutes ahead. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RescheduleDialog(currentIso: String, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val currentMs = runCatching { OffsetDateTime.parse(currentIso).toInstant().toEpochMilli() }.getOrElse { System.currentTimeMillis() }
    var step by remember { mutableStateOf(Step.Date) }
    var dateMs by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    when (step) {
        Step.Date -> {
            val dateState = rememberDatePickerState(initialSelectedDateMillis = currentMs)
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(
                        onClick = {
                            val picked = dateState.selectedDateMillis
                            if (picked != null) {
                                dateMs = picked
                                error = null
                                step = Step.Time
                            }
                        },
                    ) { Text("Next") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            ) {
                DatePicker(state = dateState)
            }
        }

        Step.Time -> {
            val current = runCatching {
                val zoned = OffsetDateTime.parse(currentIso).atZoneSameInstant(ZoneId.systemDefault())
                LocalTime.of(zoned.hour, zoned.minute)
            }.getOrElse { LocalTime.NOON }
            val timeState = rememberTimePickerState(current.hour, current.minute, is24Hour = false)
            TimePickerDialog(
                onDismissRequest = onDismiss,
                title = { Text("Pick a time") },
                properties = DialogProperties(usePlatformDefaultWidth = false),
                confirmButton = {
                    TextButton(
                        onClick = {
                            val date = dateMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate() }
                            if (date == null) {
                                step = Step.Date
                                return@TextButton
                            }
                            val candidate = date.atTime(timeState.hour, timeState.minute)
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                            if (candidate >= System.currentTimeMillis() + 5 * 60_000L) {
                                onConfirm(candidate)
                            } else {
                                error = "Scheduled posts must be at least 5 minutes in the future. Pick a later time."
                            }
                        },
                    ) { Text("Reschedule") }
                },
                dismissButton = { TextButton(onClick = { step = Step.Date }) { Text("Back") } },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(state = timeState)
                    error?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** ISO 8601 offset stamp for a chosen epoch millis, in the system zone. */
internal fun isoFromMillis(millis: Long): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

private enum class Step { Date, Time }
