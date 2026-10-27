package net.snakepit.sidewinder.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import net.snakepit.sidewinder.core.emoji.CustomEmoji
import net.snakepit.sidewinder.core.emoji.CustomEmojiRepository
import net.snakepit.sidewinder.core.emoji.categoryLabel
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.search.SearchHashtag

/**
 * When the user writes a post in a language other than their posting
 * default, this row is how they say so. Always visible so the submitted
 * language is never implicit; unknown codes still display truthfully.
 */
@Composable
internal fun LanguageSelector(
    language: String,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(min = 48.dp),
    ) {
        Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box {
            Surface(
                onClick = { open = true },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "Post language: ${languageLabel(language)}. Change language"
                    },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Text(languageLabel(language), style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                val options = if (LANGUAGE_OPTIONS.any { it.code == language }) {
                    LANGUAGE_OPTIONS
                } else {
                    // A post language outside the curated list still shows
                    // and stays selectable.
                    LANGUAGE_OPTIONS + LanguageOption(language, languageLabel(language))
                }
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        trailingIcon = {
                            if (option.code == language) Icon(Icons.Filled.Check, contentDescription = "Selected")
                        },
                        onClick = {
                            open = false
                            onLanguageChange(option.code)
                        },
                    )
                }
            }
        }
    }
}

// ---- @mention / #hashtag autocomplete -------------------------------------

/** Suggestions for the token being typed in the composer text field. */
internal sealed interface AutocompleteState {
    /** No @ / # token at the caret, or fewer than two characters typed. */
    data object Idle : AutocompleteState
    data object Loading : AutocompleteState
    data class Error(val message: String) : AutocompleteState
    data class Empty(val token: AutocompleteToken) : AutocompleteState
    data class Content(
        val token: AutocompleteToken,
        val accounts: List<Account>,
        val hashtags: List<SearchHashtag>,
    ) : AutocompleteState
}

/**
 * Inline suggestion list under the post field. Rows are 48dp targets with
 * explicit content descriptions; picking one replaces the typed token.
 */
@Composable
internal fun AutocompleteSuggestions(
    state: AutocompleteState,
    onPickAccount: (AutocompleteToken, Account) -> Unit,
    onPickHashtag: (AutocompleteToken, SearchHashtag) -> Unit,
) {
    if (state is AutocompleteState.Idle) return
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        when (state) {
            AutocompleteState.Idle -> Unit
            AutocompleteState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(18.dp)
                        .semantics { contentDescription = "Loading suggestions" },
                )
                Spacer(Modifier.width(10.dp))
                Text("Looking for matches…", style = MaterialTheme.typography.bodySmall)
            }

            is AutocompleteState.Error -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }

            is AutocompleteState.Empty -> Text(
                if (state.token.type == "hashtags") "No matching hashtags. Keep typing."
                else "No matching accounts. Keep typing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )

            is AutocompleteState.Content -> Column {
                state.accounts.forEachIndexed { index, account ->
                    val name = account.display_name?.takeIf { it.isNotBlank() } ?: account.username
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(
                                onClickLabel = "Insert mention of $name",
                            ) { onPickAccount(state.token, account) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics { contentDescription = "Mention $name, @${account.acct}" },
                    ) {
                        AsyncImage(
                            model = account.avatar_static?.ifEmpty { account.avatar } ?: account.avatar,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(28.dp).clip(CircleShape),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "@${account.acct}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (index < state.accounts.lastIndex || state.hashtags.isNotEmpty()) HorizontalDivider()
                }
                state.hashtags.forEachIndexed { index, hashtag ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(
                                onClickLabel = "Insert hashtag ${hashtag.name}",
                            ) { onPickHashtag(state.token, hashtag) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics { contentDescription = "Hashtag #${hashtag.name}" },
                    ) {
                        Text(
                            "#${hashtag.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (index < state.hashtags.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

// ---- Custom emoji picker --------------------------------------------------

private sealed interface EmojiPickerState {
    data object Loading : EmojiPickerState
    data class Error(val message: String) : EmojiPickerState
    data object Empty : EmojiPickerState
    data class Content(val groups: List<Pair<String, List<CustomEmoji>>>) : EmojiPickerState
}

/** Groups by category, "Other" last, categories alphabetical. Empty when [emojis] is empty. */
internal fun emojiGroups(emojis: List<CustomEmoji>): List<Pair<String, List<CustomEmoji>>> =
    emojis.groupBy { it.categoryLabel() }
        .toList()
        .sortedWith(compareBy({ it.first == "Other" }, { it.first }))

/**
 * Category-grouped custom emoji sheet. Loads through the per-session cache
 * and shows loading / error / empty / content, with an explicit retry that
 * bypasses the cache.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmojiPickerSheet(
    api: Api,
    session: Session,
    onDismiss: () -> Unit,
    onPick: (CustomEmoji) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<EmojiPickerState>(EmojiPickerState.Loading) }

    LaunchedEffect(api, session.id, attempt) {
        state = EmojiPickerState.Loading
        state = try {
            val emojis = CustomEmojiRepository.list(api, session.id, refresh = attempt > 0)
            if (emojis.isEmpty()) EmojiPickerState.Empty else EmojiPickerState.Content(emojiGroups(emojis))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            EmojiPickerState.Error("Could not load custom emoji: the server returned error ${e.status}.")
        } catch (e: Exception) {
            EmojiPickerState.Error("Could not load custom emoji: ${e.message ?: "no connection"}.")
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            ) {
                Text(
                    "Custom emoji",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Close custom emoji" },
                ) { Text("Close") }
            }
            when (val s = state) {
                EmojiPickerState.Loading -> Box(
                    Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics { contentDescription = "Loading custom emoji" },
                    )
                }

                is EmojiPickerState.Error -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                ) {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = { attempt += 1 },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Retry loading custom emoji" },
                    ) { Text("Retry") }
                }

                EmojiPickerState.Empty -> Text(
                    "This server has no custom emoji to pick from.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )

                is EmojiPickerState.Content -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                ) {
                    s.groups.forEach { (category, emojis) ->
                        item(key = "header-$category") {
                            Text(
                                category,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(emojis, key = { "emoji-${it.shortcode}" }) { emoji ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(onClickLabel = "Insert ${emoji.shortcode} emoji") { onPick(emoji) }
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .semantics { contentDescription = ":${emoji.shortcode}:" },
                            ) {
                                AsyncImage(
                                    model = emoji.displayUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    ":${emoji.shortcode}:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
