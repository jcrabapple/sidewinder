package net.snakepit.sidewinder.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.model.StatusEdit
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.thread.ThreadRepository
import net.snakepit.sidewinder.ui.text.EmojiText

private val historyStamp = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun editStamp(iso: String): String =
    runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull()
        ?.format(historyStamp) ?: iso

/**
 * Edit-history dialog: every revision of an edited post, oldest first,
 * straight from the server (GET /api/v1/statuses/{id}/history).
 */
@Composable
fun HistoryDialog(statusId: String, api: Api, baseUrl: String, onDismiss: () -> Unit) {
    var state by remember(statusId) {
        mutableStateOf<HistoryState>(HistoryState.Loading)
    }
    androidx.compose.runtime.LaunchedEffect(statusId) {
        state = try {
            HistoryState.Content(ThreadRepository.history(api, statusId))
        } catch (e: ApiError) {
            HistoryState.Error("Could not load the edit history: the server returned error ${e.status}.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HistoryState.Error("Could not load the edit history: ${e.message ?: "network error"}.")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit history") },
        text = {
            when (val s = state) {
                HistoryState.Loading -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics { contentDescription = "Loading edit history" },
                    )
                }

                is HistoryState.Error -> Text(s.message, style = MaterialTheme.typography.bodyMedium)

                is HistoryState.Content -> Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    s.edits.forEachIndexed { index, edit ->
                        if (index > 0) HorizontalDivider()
                        Text(
                            text = editStamp(edit.created_at) + if (index == 0) " (original)" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (edit.spoiler_text.isNotBlank()) {
                            Text(
                                "CW: ${edit.spoiler_text}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        net.snakepit.sidewinder.core.text.HtmlText(
                            html = edit.content,
                            baseUrl = baseUrl,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private sealed interface HistoryState {
    data object Loading : HistoryState
    data class Error(val message: String) : HistoryState
    data class Content(val edits: List<StatusEdit>) : HistoryState
}

/**
 * Thread unroll (Trunks-style): the whole conversation rendered as one
 * continuous article — ancestor chain, the focused post, then replies.
 */
@Composable
fun UnrollDialog(
    ancestors: List<Status>,
    focus: Status,
    descendants: List<Status>,
    baseUrl: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(MaterialTheme.shapes.large)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Thread as article",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { contentDescription = "Thread unrolled as an article" },
            )
            Text(
                text = "${ancestors.size + 1 + descendants.size} posts",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            (ancestors + focus + descendants).forEach { status ->
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                ArticlePost(status = status, baseUrl = baseUrl)
            }
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp)
                    .align(Alignment.CenterHorizontally)
                    .semantics { contentDescription = "Close the article view" },
            ) { Text("Close") }
        }
    }
}

@Composable
private fun ArticlePost(status: Status, baseUrl: String) {
    val author = status.account
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = author.avatar_static?.ifEmpty { author.avatar } ?: author.avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .heightIn(min = 32.dp)
                .width(32.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            EmojiText(
                text = author.display_name?.takeIf { it.isNotBlank() } ?: author.username,
                emojis = author.emojis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "@${author.acct}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = editStamp(status.created_at),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (status.spoiler_text.isNotBlank()) {
        Text(
            "CW: ${status.spoiler_text}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    net.snakepit.sidewinder.core.text.HtmlText(
        html = status.content,
        baseUrl = baseUrl,
        modifier = Modifier.padding(top = 8.dp),
    )
}
