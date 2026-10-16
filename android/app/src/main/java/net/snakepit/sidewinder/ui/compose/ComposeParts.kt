package net.snakepit.sidewinder.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.text.StatusText
import org.jsoup.Jsoup

/** Capitalize sentences everywhere the user writes prose (posts, CW, alt text, polls). */
internal val ProseKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

internal data class VisibilityOption(
    val value: String,
    val label: String,
    val description: String,
    val icon: ImageVector,
)

internal val VISIBILITY_OPTIONS = listOf(
    VisibilityOption("public", "Public", "Anyone, shown on public timelines", Icons.Outlined.Public),
    VisibilityOption("unlisted", "Unlisted", "Anyone, kept off public timelines", Icons.Outlined.LockOpen),
    VisibilityOption("private", "Followers only", "Only your followers", Icons.Outlined.Lock),
    VisibilityOption("direct", "Direct", "Only the people you mention", Icons.Outlined.AlternateEmail),
)

internal fun visibilityOption(value: String): VisibilityOption =
    VISIBILITY_OPTIONS.firstOrNull { it.value == value } ?: VISIBILITY_OPTIONS.first()

/** Visibility reach rank; a reply may not be more public than its parent.
 *  Unknown or server-specific values (local, mutuals_only) rank 0, which
 *  leaves every option available and lets the server arbitrate. */
internal fun visibilityRank(value: String): Int = when (value) {
    "public" -> 0
    "unlisted" -> 1
    "private" -> 2
    "direct" -> 3
    else -> 0
}

/** The post being replied to, shown above the composer. */
@Composable
internal fun ReplyContext(parent: Status) {
    val name = parent.account.display_name?.takeIf { it.isNotBlank() } ?: parent.account.username
    val excerpt = remember(parent.id) {
        parent.spoiler_text.takeIf { it.isNotBlank() }?.let { "CW: $it" }
            ?: Jsoup.parse(parent.content).text()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AsyncImage(
            model = parent.account.avatar_static?.ifEmpty { parent.account.avatar } ?: parent.account.avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                "Replying to $name",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                excerpt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The post being quoted, shown above the composer (reply-context styling). */
@Composable
internal fun QuoteContext(quoted: Status) {
    val name = quoted.account.display_name?.takeIf { it.isNotBlank() } ?: quoted.account.username
    val excerpt = remember(quoted.id) {
        quoted.spoiler_text.takeIf { it.isNotBlank() }?.let { "CW: $it" }
            ?: StatusText.plain(quoted.content)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AsyncImage(
            model = quoted.account.avatar_static?.ifEmpty { quoted.account.avatar } ?: quoted.account.avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                "Quoting $name",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                excerpt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * "Posting as" row: avatar, names, and the visibility picker. Visibility
 * is locked while editing (servers keep a post's original audience).
 */
@Composable
internal fun AuthorRow(
    session: Session,
    visibility: String,
    locked: Boolean,
    onVisibilityChange: (String) -> Unit,
    /** Parent post's visibility while replying; the picker demotes only. */
    parentVisibility: String? = null,
) {
    val account = session.account
    val host = remember(session.base) { session.base.substringAfter("://").substringBefore('/') }
    var menuOpen by remember { mutableStateOf(false) }
    val current = visibilityOption(visibility)
    val maxRank = parentVisibility?.let { visibilityRank(it) }
    val options = if (maxRank == null) {
        VISIBILITY_OPTIONS
    } else {
        VISIBILITY_OPTIONS.filter { visibilityRank(it.value) >= maxRank }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = account.avatar_static?.ifEmpty { account.avatar } ?: account.avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                account.display_name?.takeIf { it.isNotBlank() } ?: account.username,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "@${account.acct}@$host",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            Surface(
                onClick = { if (!locked) menuOpen = true },
                enabled = !locked,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.semantics {
                    contentDescription = if (locked) {
                        "Visibility: ${current.label}. Visibility cannot be changed when editing."
                    } else {
                        "Visibility: ${current.label}. Change visibility"
                    }
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Icon(current.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(current.label, style = MaterialTheme.typography.labelLarge)
                    if (!locked) Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        leadingIcon = { Icon(option.icon, contentDescription = null) },
                        trailingIcon = {
                            if (option.value == visibility) Icon(Icons.Filled.Check, contentDescription = "Selected")
                        },
                        text = {
                            Column {
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            menuOpen = false
                            onVisibilityChange(option.value)
                        },
                    )
                }
            }
        }
    }
}

/** Horizontal strip of attachment thumbnails with upload state, ALT, and remove. */
@Composable
internal fun MediaStrip(
    media: List<DraftMedia>,
    onRemove: (DraftMedia) -> Unit,
    onRetry: (DraftMedia) -> Unit,
    onEditAlt: (DraftMedia) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        media.forEachIndexed { index, item -> MediaThumb(index, item, onRemove, onRetry, onEditAlt) }
    }
}

@Composable
private fun MediaThumb(
    index: Int,
    item: DraftMedia,
    onRemove: (DraftMedia) -> Unit,
    onRetry: (DraftMedia) -> Unit,
    onEditAlt: (DraftMedia) -> Unit,
) {
    val attachment = item.attachment
    val described = !attachment?.description.isNullOrBlank()
    Box(
        modifier = Modifier
            .size(116.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // The local file previews instantly; the server preview replaces it once uploaded.
        AsyncImage(
            model = attachment?.preview_url ?: item.uri,
            contentDescription = attachment?.description?.takeIf { it.isNotBlank() }
                ?: "Attachment ${index + 1}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        when {
            item.uploading -> Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier
                        .size(32.dp)
                        .semantics { contentDescription = "Uploading attachment ${index + 1}" },
                )
            }
            attachment == null -> Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(onClickLabel = "Retry upload") { onRetry(item) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Color.White)
                    Text("Tap to retry", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (attachment != null) {
            Surface(
                onClick = { onEditAlt(item) },
                shape = RoundedCornerShape(6.dp),
                color = if (described) Color.Black.copy(alpha = 0.65f) else MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = if (described) Color.White else MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .semantics {
                        contentDescription = if (described) {
                            "Edit description of attachment ${index + 1}"
                        } else {
                            "Add a description to attachment ${index + 1}"
                        }
                    },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    if (described) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(12.dp))
                    Text(
                        if (described) "ALT" else "+ ALT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        IconButton(
            onClick = { onRemove(item) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .semantics { contentDescription = "Remove attachment ${index + 1}" },
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Poll builder: 2-6 options, single/multiple choice, duration. */
@Composable
internal fun PollEditor(
    options: List<String>,
    onOptionChange: (Int, String) -> Unit,
    onAddOption: () -> Unit,
    onRemoveOption: (Int) -> Unit,
    multiple: Boolean,
    onMultipleChange: (Boolean) -> Unit,
    expiry: Long,
    onExpiryChange: (Long) -> Unit,
) {
    var durationOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Poll", style = MaterialTheme.typography.titleSmall)
        options.forEachIndexed { index, option ->
            OutlinedTextField(
                value = option,
                onValueChange = { value -> if (value.length <= POLL_OPTION_LIMIT) onOptionChange(index, value) },
                placeholder = { Text("Option ${index + 1}") },
                singleLine = true,
                keyboardOptions = ProseKeyboard,
                trailingIcon = if (options.size > 2) {
                    {
                        IconButton(
                            onClick = { onRemoveOption(index) },
                            modifier = Modifier.semantics { contentDescription = "Remove option ${index + 1}" },
                        ) { Icon(Icons.Filled.Close, contentDescription = null) }
                    }
                } else {
                    null
                },
                supportingText = if (option.length > POLL_OPTION_LIMIT - 10) {
                    { Text("${option.length} / $POLL_OPTION_LIMIT") }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Poll option ${index + 1}" },
            )
        }
        if (options.size < 6) {
            TextButton(onClick = onAddOption, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add option") }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = "Toggle multiple choice") { onMultipleChange(!multiple) },
        ) {
            Text("Allow multiple choices", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = multiple,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        Box {
            TextButton(
                onClick = { durationOpen = true },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Poll duration: ${expiryLabel(expiry)}. Change duration" },
            ) {
                Text("Ends after ${expiryLabel(expiry)}")
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = durationOpen, onDismissRequest = { durationOpen = false }) {
                EXPIRY_OPTIONS.forEach { (seconds, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = {
                            if (seconds == expiry) Icon(Icons.Filled.Check, contentDescription = "Selected")
                        },
                        onClick = {
                            durationOpen = false
                            onExpiryChange(seconds)
                        },
                    )
                }
            }
        }
    }
}

internal fun expiryLabel(seconds: Long): String =
    EXPIRY_OPTIONS.firstOrNull { it.first == seconds }?.second ?: "${seconds / 3600} hours"

/**
 * Bottom toolbar (Tusky/Moshidon layout): attach, poll, content warning,
 * sensitive-media toggle, and the remaining-characters ring.
 */
@Composable
internal fun ComposeToolbar(
    canAddMedia: Boolean,
    onAddMedia: () -> Unit,
    pollOn: Boolean,
    pollEnabled: Boolean,
    onTogglePoll: () -> Unit,
    cwOn: Boolean,
    onToggleCw: () -> Unit,
    scheduleOn: Boolean = false,
    onToggleSchedule: () -> Unit = {},
    scheduleEnabled: Boolean = true,
    hasMedia: Boolean,
    sensitive: Boolean,
    onToggleSensitive: () -> Unit,
    used: Int,
    limit: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onAddMedia,
                enabled = canAddMedia,
                modifier = Modifier.semantics { contentDescription = "Add images" },
            ) { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null) }
            IconToggleButton(
                checked = pollOn,
                onCheckedChange = { onTogglePoll() },
                enabled = pollEnabled,
                modifier = Modifier.semantics { contentDescription = if (pollOn) "Remove poll" else "Add a poll" },
            ) { Icon(Icons.Outlined.Poll, contentDescription = null, tint = toggleTint(pollOn, pollEnabled)) }
            IconToggleButton(
                checked = cwOn,
                onCheckedChange = { onToggleCw() },
                modifier = Modifier.semantics {
                    contentDescription = if (cwOn) "Remove content warning" else "Add a content warning"
                },
            ) { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = toggleTint(cwOn, true)) }
            IconToggleButton(
                checked = scheduleOn,
                onCheckedChange = { onToggleSchedule() },
                enabled = scheduleEnabled,
                modifier = Modifier.semantics {
                    contentDescription = if (scheduleOn) "Remove the schedule" else "Schedule this post"
                },
            ) { Icon(Icons.Outlined.Schedule, contentDescription = null, tint = toggleTint(scheduleOn, scheduleEnabled)) }
            if (hasMedia) {
                IconToggleButton(
                    checked = sensitive,
                    onCheckedChange = { onToggleSensitive() },
                    modifier = Modifier.semantics {
                        contentDescription = if (sensitive) "Media marked sensitive. Unmark" else "Mark media as sensitive"
                    },
                ) {
                    Icon(
                        if (sensitive) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = null,
                        tint = toggleTint(sensitive, true),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            CharacterCounter(used = used, limit = limit)
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun toggleTint(on: Boolean, enabled: Boolean): Color = when {
    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    on -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun CharacterCounter(used: Int, limit: Int) {
    val remaining = limit - used
    val color = when {
        remaining < 0 -> MaterialTheme.colorScheme.error
        remaining <= 100 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = if (remaining >= 0) {
                "$remaining characters remaining"
            } else {
                "${-remaining} characters over the limit"
            }
        },
    ) {
        CircularProgressIndicator(
            progress = { (used.toFloat() / limit).coerceIn(0f, 1f) },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(remaining.toString(), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Alt-text editor with a preview of the image being described. */
@Composable
internal fun AltTextDialog(
    previewModel: Any?,
    initial: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Describe this image") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (previewModel != null) {
                    AsyncImage(
                        model = previewModel,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= ALT_TEXT_LIMIT) value = it },
                    placeholder = { Text("What's in the image, for people who can't see it") },
                    keyboardOptions = ProseKeyboard,
                    supportingText = { Text("${value.length} / $ALT_TEXT_LIMIT") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Image description" },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
