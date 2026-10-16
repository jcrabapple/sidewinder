package net.snakepit.sidewinder.ui.timeline

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import net.snakepit.sidewinder.core.filters.FilterCache
import net.snakepit.sidewinder.core.filters.FilterEngine
import net.snakepit.sidewinder.core.filters.FilterHit
import net.snakepit.sidewinder.core.model.MediaAttachment
import net.snakepit.sidewinder.core.model.Poll
import net.snakepit.sidewinder.core.model.PreviewCard
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.quote.QuoteLinks
import net.snakepit.sidewinder.core.text.HtmlText
import net.snakepit.sidewinder.core.text.StatusText
import net.snakepit.sidewinder.ui.app.LocalAppActions
import net.snakepit.sidewinder.ui.app.LocalAppSettings
import net.snakepit.sidewinder.ui.app.shareText
import net.snakepit.sidewinder.ui.media.MediaViewer
import net.snakepit.sidewinder.ui.text.EmojiText
import net.snakepit.sidewinder.ui.theme.LocalActionColors

/** Posts whose plain text exceeds this many characters collapse in feeds. */
private const val COLLAPSE_THRESHOLD = 480

/** Lines of a collapsed long post shown before the "Show more" button. */
private const val COLLAPSED_LINES = 10

/**
 * The atom of the timeline. Port of web `src/components/StatusCard.tsx`
 * (timeline variant only): boost wrapper, header, relative timestamp,
 * CW, sanitized content, media grid (sensitive-media reveal), polls with
 * voting, action toggles.
 *
 * NOTE: custom emoji shortcodes (in display names, CW text, and poll
 * options) render as literal `:shortcode:` text. Replacing them with
 * images requires an inline-content AnnotatedString pass over plain
 * strings, deliberately left out of this read-only slice.
 *
 * Tapping the card body or author opens the displayed status's thread
 * via [onOpenThread] (boost wrappers unwrap to the inner status). The
 * reply icon hands the reply-to id to [onCompose].
 */
@Composable
fun StatusCard(
    status: Status,
    api: Api,
    baseUrl: String,
    onStatusUpdated: (Status) -> Unit,
    onNotice: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onCompose: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Filter context ("home"/"notifications"/"public"/"thread"); null skips filtering. */
    filterContext: String? = null,
    /** True when the card is a timeline row (wellbeing media-hiding applies). */
    inTimeline: Boolean = false,
) {
    // Boost wrapper: render the booster's name in a header line and the
    // inner status as the payload (the only sanctioned non-null use).
    val boosted = status.reblog != null
    val inner = status.reblog ?: status
    val actions = LocalAppActions.current
    val settings = LocalAppSettings.current

    // Filter verdict: server-provided `filtered` first, client-side match
    // over the peeked v2 filters as fallback — both synchronously, so a
    // hide/warn/blur-filtered post never renders its content first
    // (K6). Only when the cache has never been populated does the
    // suspend FilterCache.current run; until it resolves the card shows
    // a neutral placeholder instead of the content. "Show anyway"
    // overrides the verdict for the rest of this card's composition.
    val token = api.token ?: ""
    var filterHit: FilterHit? by remember(inner.id, filterContext) {
        mutableStateOf(
            filterContext?.let { context ->
                StatusCardLogic.initialFilterHit(
                    filtered = inner.filtered,
                    contentHtml = inner.content,
                    spoiler = inner.spoiler_text,
                    pollOptions = inner.poll?.options?.map { it.title }.orEmpty(),
                    mediaDescriptions = inner.media_attachments.mapNotNull { it.description?.takeIf(String::isNotBlank) },
                    peekedFilters = FilterCache.peek(token),
                    context = context,
                    nowMs = System.currentTimeMillis(),
                )
            },
        )
    }
    // Pending = context applies, the server verdict did not settle it,
    // and there is no peeked cache to match synchronously.
    var filtersPending by remember(inner.id, filterContext) {
        mutableStateOf(filterContext != null && filterHit == null && FilterCache.peek(token) == null)
    }
    LaunchedEffect(inner.id, filterContext, filtersPending) {
        if (!filtersPending) return@LaunchedEffect
        val context = filterContext ?: return@LaunchedEffect
        try {
            val filters = FilterCache.current(api, token)
            val now = System.currentTimeMillis()
            filterHit = withContext(Dispatchers.Default) {
                FilterEngine.verdict(inner.filtered, context, now)
                    ?: StatusCardLogic.clientMatch(
                        filters,
                        inner.content,
                        inner.spoiler_text,
                        inner.poll?.options?.map { it.title }.orEmpty(),
                        inner.media_attachments.mapNotNull { it.description?.takeIf(String::isNotBlank) },
                        context,
                        now,
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed filter fetch must not hide the post; leave it unfiltered.
        }
        filtersPending = false
    }
    var filterRevealed by remember(inner.id) { mutableStateOf(false) }
    val activeFilter = filterHit?.takeIf { !filterRevealed }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "Open thread",
            ) {
                // Body tap opens the displayed status, not the wrapper.
                onOpenThread(inner.id)
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (filtersPending) {
            // Verdict not resolvable yet (no peeked cache, fetch in
            // flight): a neutral row instead of the content, so media
            // and text of a possibly filtered post never load.
            FilterPendingRow()
            return@Column
        }
        if (activeFilter?.action == "hide") {
            FilteredRow(hit = activeFilter, onShow = { filterRevealed = true })
            return@Column
        }
        if (boosted) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.then(
                    if (actions != null) {
                        Modifier.clickable(onClickLabel = "Open the booster's profile") {
                            actions.openProfile(status.account.id)
                        }
                    } else {
                        Modifier
                    },
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Repeat,
                    contentDescription = null, // decorative; the line text carries the meaning
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = boosterLine(status),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        HeaderRow(status = inner, onOpenProfile = actions?.openProfile)

        // Poll votes must fold back into the card's current shape (the
        // boost wrapper when there is one), like the action toggles do.
        val onPollUpdated: (Poll) -> Unit = { updated ->
            onStatusUpdated(mergeUpdated(status, inner.copy(poll = updated)))
        }

        // Media previews hide in timelines when wellbeing asks; thread and
        // profile views keep them. Long posts collapse in feeds only.
        val showMediaPreviews = !inTimeline || !settings.hideMediaPreviews
        val collapsible = inTimeline && settings.collapseLongPosts
        val content: @Composable () -> Unit = {
            if (inner.spoiler_text.isNotEmpty()) {
                SpoilerSection(
                    status = inner,
                    baseUrl = baseUrl,
                    api = api,
                    onPollUpdated = onPollUpdated,
                    onNotice = onNotice,
                    showMediaPreviews = showMediaPreviews,
                    collapsible = collapsible,
                )
            } else {
                StatusContent(
                    status = inner,
                    baseUrl = baseUrl,
                    api = api,
                    onPollUpdated = onPollUpdated,
                    onNotice = onNotice,
                    showMediaPreviews = showMediaPreviews,
                    collapsible = collapsible,
                )
            }
        }

        when (activeFilter?.action) {
            // Below API 31 Modifier.blur is a no-op, so the content is
            // not composed at all until revealed; on 31+ it composes
            // blurred (and is hidden from accessibility either way).
            "blur" -> FilterBlurredBlock(
                hit = activeFilter,
                onShow = { filterRevealed = true },
                composeContent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                content = content,
            )
            // Warn keeps the content hidden until "Show anyway" (Mastodon
            // semantics; FilterBanner's KDoc says the same).
            "warn" -> FilterBanner(hit = activeFilter, onShow = { filterRevealed = true })
            else -> content()
        }

        CountsRow(
            status = inner,
            canBoost = inner.visibility == "public" || inner.visibility == "unlisted" || inner.visibility == "local",
            api = api,
            currentStatus = status,
            onStatusUpdated = onStatusUpdated,
            onNotice = onNotice,
            onReply = onCompose,
            hideCounts = settings.hideEngagementCounts,
            confirmBoostSetting = settings.confirmBeforeBoost,
        )
    }
}

/** "Name boosted" line for a boost wrapper, falling back to @acct. */
private fun boosterLine(outer: Status): String {
    val name = outer.account.display_name
    val who = if (name.isNullOrBlank()) "@${outer.account.acct}" else name
    return "$who boosted"
}

@Composable
private fun HeaderRow(status: Status, onOpenProfile: ((String) -> Unit)?) {
    val authorName = status.account.display_name?.takeIf { it.isNotBlank() } ?: status.account.username
    val relative = LocalAppSettings.current.relativeTimes
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Avatar + names open the author's profile (Tusky/Moshidon pattern);
        // the rest of the card still opens the thread.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onOpenProfile != null) {
                        Modifier.clickable(onClickLabel = "Open $authorName's profile") {
                            onOpenProfile(status.account.id)
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            AsyncImage(
                model = status.account.avatar_static?.ifEmpty { status.account.avatar } ?: status.account.avatar,
                contentDescription = "Avatar of $authorName",
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                EmojiText(
                    text = authorName,
                    emojis = status.account.emojis,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "@${status.account.acct}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val stamp = remember(status.created_at, status.edited_at, relative) {
            (if (relative) relativeTimestamp(status.created_at) else absoluteTimestamp(status.created_at)) +
                if (status.edited_at != null) " \u00B7 edited" else ""
        }
        Text(
            text = stamp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpoilerSection(
    status: Status,
    baseUrl: String,
    api: Api,
    onPollUpdated: (Poll) -> Unit,
    onNotice: (String) -> Unit,
    showMediaPreviews: Boolean = true,
    collapsible: Boolean = false,
) {
    var revealed by remember(status.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        EmojiText(
            text = status.spoiler_text,
            emojis = status.emojis,
            style = MaterialTheme.typography.bodyMedium,
        )
        FilledTonalButton(
            onClick = { revealed = !revealed },
            modifier = Modifier
                .padding(top = 4.dp)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = if (revealed) "Collapse content behind the content warning" else "Expand content behind the content warning" },
        ) {
            Text(if (revealed) "Show less" else "Show more")
        }
        if (revealed) {
            StatusContent(status = status, baseUrl = baseUrl, api = api, onPollUpdated = onPollUpdated, onNotice = onNotice, showMediaPreviews = showMediaPreviews, collapsible = collapsible)
        }
    }
}

@Composable
private fun StatusContent(
    status: Status,
    baseUrl: String,
    api: Api,
    onPollUpdated: (Poll) -> Unit,
    onNotice: (String) -> Unit,
    showMediaPreviews: Boolean = true,
    collapsible: Boolean = false,
) {
    val hasBodySource = status.content.isNotBlank() && !StatusText.isEmptyHtml(status.content)
    // Link-quote detection: a status ending in `RE: <url>` renders the
    // quoted post as an embedded card instead of the trailing link line.
    // Pure and remembered, so Jsoup runs once per content string.
    val quote = remember(status.id, status.content) {
        if (hasBodySource) QuoteLinks.detect(status.content, status.mentions, status.tags) else null
    }
    val bodyHtml = remember(status.content, quote) {
        if (quote?.explicitRe == true) QuoteLinks.stripReLine(status.content, quote.url) else status.content
    }
    val hasBody = bodyHtml.isNotBlank() && !StatusText.isEmptyHtml(bodyHtml)
    // Long-post collapse (feeds only): plain-text length decides, expand
    // state persists in TimelineStore across scrolling and reloads.
    val isLong = collapsible && hasBody &&
        remember(bodyHtml) { StatusText.plain(bodyHtml).length > COLLAPSE_THRESHOLD }
    var expanded by remember(status.id) { mutableStateOf(!isLong || TimelineStore.isExpanded(status.id)) }
    if (hasBody) {
        HtmlText(
            html = bodyHtml,
            baseUrl = baseUrl,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            tags = status.tags,
            mentions = status.mentions,
            maxLines = if (isLong && !expanded) COLLAPSED_LINES else Int.MAX_VALUE,
            overflow = if (isLong && !expanded) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
        if (isLong) {
            TextButton(
                onClick = {
                    expanded = !expanded
                    TimelineStore.setExpanded(status.id, expanded)
                },
                modifier = Modifier
                    .padding(top = 2.dp)
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = if (expanded) "Show less of this post" else "Show more of this post"
                    },
            ) { Text(if (expanded) "Show less" else "Show more") }
        }
    }
    val media = status.media_attachments
    if (media.isNotEmpty()) {
        if (showMediaPreviews) {
            Spacer(Modifier.height(8.dp))
            // Sensitive media starts hidden behind a reveal overlay (web parity).
            var mediaRevealed by remember(status.id) { mutableStateOf(!status.sensitive) }
            var viewerIndex by remember(status.id) { mutableStateOf<Int?>(null) }
            MediaGrid(
                media = media,
                blurred = !mediaRevealed,
                onReveal = { mediaRevealed = true },
                onOpen = { index -> viewerIndex = index },
            )
            viewerIndex?.let { index ->
                MediaViewer(media = media, startIndex = index, onDismiss = { viewerIndex = null })
            }
        }
    } else {
        // Servers attach a preview card to link posts; media wins when both exist.
        status.card?.let { card ->
            Spacer(Modifier.height(8.dp))
            LinkCard(card)
        }
    }
    status.poll?.let { poll ->
        Spacer(Modifier.height(8.dp))
        PollRows(poll = poll, api = api, onPollUpdated = onPollUpdated, onNotice = onNotice)
    }
    // The embedded quoted post, below the body. Resolution is async and
    // never blocks this card; a bare trailing link (secondary form) stays
    // visible in the text.
    quote?.let { ref ->
        Spacer(Modifier.height(8.dp))
        QuoteBlock(
            url = ref.url,
            api = api,
            baseUrl = baseUrl,
            showMediaPreviews = showMediaPreviews,
        )
    }
}

/**
 * Up to four tiles; a fifth-plus item shows as "+N" on the last tile and
 * is still reachable by swiping in the viewer. Tapping a tile opens the
 * viewer at that index.
 */
@Composable
private fun MediaGrid(
    media: List<MediaAttachment>,
    blurred: Boolean,
    onReveal: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    val shown = media.take(4)
    val overflow = media.size - shown.size

    @Composable
    fun tile(index: Int, modifier: Modifier) {
        MediaTile(
            attachment = shown[index],
            extraCount = if (index == shown.lastIndex) overflow else 0,
            onClick = { onOpen(index) },
            modifier = modifier,
        )
    }

    Box {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = if (blurred) Modifier.blur(14.dp) else Modifier,
        ) {
            when (shown.size) {
                1 -> tile(0, Modifier.fillMaxWidth().aspectRatio(1.8f))
                2 -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tile(0, Modifier.weight(1f).height(180.dp))
                    tile(1, Modifier.weight(1f).height(180.dp))
                }
                3 -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(220.dp)) {
                    tile(0, Modifier.weight(1.4f).fillMaxWidth())
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tile(1, Modifier.weight(1f).fillMaxWidth())
                        tile(2, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(160.dp)) {
                        tile(0, Modifier.weight(1f))
                        tile(1, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(160.dp)) {
                        tile(2, Modifier.weight(1f))
                        tile(3, Modifier.weight(1f))
                    }
                }
            }
        }
        if (blurred) {
            // Privacy cover over sensitive media. The blur is a no-op
            // below Android 12, so the scrim carries the hiding alone.
            // It sits above the tiles, so the first tap only reveals.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .clickable(onClick = onReveal)
                    .semantics { contentDescription = "Sensitive media hidden. Show it." },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Sensitive content \u2014 tap to show",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun MediaTile(
    attachment: MediaAttachment,
    extraCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = when (attachment.type) {
        "video" -> "Video"
        "gifv" -> "Animated GIF"
        "audio" -> "Audio"
        "image" -> "Image"
        else -> "Attachment"
    }
    val alt = attachment.description?.takeIf { it.isNotBlank() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = buildString {
                    append(kind)
                    append(": ")
                    append(alt ?: "no description")
                    if (extraCount > 0) append(". $extraCount more")
                    append(". Opens the media viewer")
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val preview = attachment.preview_url ?: attachment.url.takeIf { attachment.type == "image" }
        if (preview != null) {
            AsyncImage(
                model = preview,
                contentDescription = null, // the tile's semantics carry it
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        when (attachment.type) {
            "video", "gifv", "audio" -> Icon(
                imageVector = if (attachment.type == "audio") Icons.Filled.Audiotrack else Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(4.dp),
            )
        }
        if (attachment.type == "gifv") {
            Text(
                "GIF",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (alt != null) {
            Text(
                "ALT",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (extraCount > 0) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("+$extraCount", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

/** Link preview: optional image, title, domain; tap opens the link in-app. */
@Composable
private fun LinkCard(card: PreviewCard) {
    val actions = LocalAppActions.current
    val uriHandler = LocalUriHandler.current
    val domain = remember(card.url) {
        runCatching { java.net.URI(card.url).host?.removePrefix("www.") }.getOrNull().orEmpty()
    }
    val title = card.title.ifBlank { card.url }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Open link") {
                if (actions != null) actions.openUrl(card.url) else uriHandler.openUri(card.url)
            }
            .semantics { contentDescription = "Link preview: $title, $domain" },
    ) {
        card.image?.takeIf { it.isNotBlank() }?.let { image ->
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.91f),
            )
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (domain.isNotEmpty()) {
                Text(
                    domain,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (card.description.isNotBlank()) {
                Text(
                    card.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Poll block, mirroring web `Poll.tsx`. While the poll is open and not
 * yet voted: selectable options (radio for single-choice, checkboxes
 * for multiple) and a Vote button; no results are shown before voting.
 * After voting or expiry: read-only result rows with percentages.
 */
@Composable
private fun PollRows(
    poll: Poll,
    api: Api,
    onPollUpdated: (Poll) -> Unit,
    onNotice: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selected by remember(poll.id) { mutableStateOf(emptyList<Int>()) }
    var busy by remember(poll.id) { mutableStateOf(false) }
    val total = poll.votes_count.coerceAtLeast(0)
    val settled = poll.voted == true || poll.expired

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (settled) {
            poll.options.forEachIndexed { index, option ->
                val votes = option.votes_count ?: 0
                val percent = if (total > 0) ((votes * 100.0) / total).roundToInt() else 0
                val own = index in poll.own_votes
                val optionColor = if (own) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = if (own) "\u2713 " else "", style = MaterialTheme.typography.bodyMedium, color = optionColor)
                    EmojiText(text = option.title, emojis = poll.emojis, style = MaterialTheme.typography.bodyMedium, color = optionColor)
                    Text(
                        text = " \u2014 $percent% ($votes votes)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = optionColor,
                    )
                }
            }
        } else {
            poll.options.forEachIndexed { index, option ->
                val chosen = index in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = chosen,
                            role = if (poll.multiple) Role.Checkbox else Role.RadioButton,
                            onValueChange = {
                                selected = if (poll.multiple) {
                                    if (chosen) selected - index else selected + index
                                } else {
                                    listOf(index)
                                }
                            },
                        )
                        .semantics {
                            contentDescription =
                                "Poll option ${index + 1}: ${option.title}${if (chosen) ", selected" else ""}"
                        },
                ) {
                    if (poll.multiple) {
                        Checkbox(
                            checked = chosen,
                            onCheckedChange = null,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    } else {
                        RadioButton(
                            selected = chosen,
                            onClick = null,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    EmojiText(text = option.title, emojis = poll.emojis, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(
                onClick = {
                    if (busy || selected.isEmpty()) return@Button
                    busy = true
                    scope.launch {
                        try {
                            // JSON body {"choices":[ints]} — the exact shape
                            // the web client's votePoll() sends to GTS.
                            val fresh = api.request(
                                path = "/api/v1/polls/${poll.id}/votes",
                                serializer = Poll.serializer(),
                                method = "POST",
                                body = buildJsonObject {
                                    putJsonArray("choices") {
                                        selected.sorted().forEach { add(JsonPrimitive(it)) }
                                    }
                                },
                            )
                            onPollUpdated(fresh)
                        } catch (e: ApiError) {
                            onNotice("Could not vote: the server returned error ${e.status}. Try again.")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onNotice(
                                "Could not vote: ${e.message ?: "network error"}. " +
                                    "Check your connection and try again.",
                            )
                        }
                        busy = false
                    }
                },
                enabled = selected.isNotEmpty() && !busy,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Vote on this poll" },
            ) {
                Text(if (busy) "Voting\u2026" else "Vote")
            }
        }
        val state = when {
            poll.expired -> " \u00B7 closed"
            poll.voted == true -> " \u00B7 you voted"
            else -> ""
        }
        Text(
            text = "$total votes$state",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CountsRow(
    status: Status,
    canBoost: Boolean,
    api: Api,
    currentStatus: Status,
    onStatusUpdated: (Status) -> Unit,
    onNotice: (String) -> Unit,
    onReply: (String) -> Unit,
    hideCounts: Boolean = false,
    confirmBoostSetting: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val actions = LocalAppActions.current
    // The newest status at any moment: a revert must restore only the
    // concurrent edits that landed while the request was in flight (K9).
    val latestInner by rememberUpdatedState(status.reblog ?: status)
    val latestOuter by rememberUpdatedState(currentStatus)
    // Action currently in flight, so rapid taps cannot double-fire and a
    // second toggle cannot start before the first one settles.
    var pending by remember { mutableStateOf<String?>(null) }
    var confirmBoost by remember { mutableStateOf(false) }
    // Long-press menu anchored to the boost button: Boost/Undo boost (the
    // confirm-before-boost setting still applies) and Quote.
    var boostMenu by remember { mutableStateOf(false) }
    // Press sources shared between the buttons and the bouncing modifier.
    val replySource = remember { MutableInteractionSource() }
    val boostSource = remember { MutableInteractionSource() }
    val favouriteSource = remember { MutableInteractionSource() }
    val bookmarkSource = remember { MutableInteractionSource() }

    fun toggle(action: String, label: String, active: Boolean) {
        if (pending != null) return
        pending = action
        val optimistic = (status.reblog ?: status).let { s ->
            when (action) {
                "favourite" -> s.copy(
                    favourited = !active,
                    favourites_count = (s.favourites_count + if (active) -1 else 1).coerceAtLeast(0),
                )
                "bookmark" -> s.copy(bookmarked = !active)
                else -> s.copy(
                    reblogged = !active,
                    reblogs_count = (s.reblogs_count + if (active) -1 else 1).coerceAtLeast(0),
                )
            }
        }
        onStatusUpdated(mergeUpdated(currentStatus, optimistic))

        val endpoint = if (active) "un$action" else action
        scope.launch {
            try {
                val fresh = api.postForm(
                    path = "/api/v1/statuses/${status.id}/$endpoint",
                    form = emptyMap(),
                    serializer = Status.serializer(),
                ).let { it.reblog ?: it }
                onStatusUpdated(mergeUpdated(latestOuter, fresh))
            } catch (e: ApiError) {
                // Revert only the toggled field on the latest status.
                onStatusUpdated(mergeUpdated(latestOuter, StatusCardLogic.revertToggle(latestInner, action, active)))
                onNotice("Could not $label: the server returned error ${e.status}. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onStatusUpdated(mergeUpdated(latestOuter, StatusCardLogic.revertToggle(latestInner, action, active)))
                val reason = e.message ?: "network error"
                onNotice("Could not $label: $reason. Check your connection and try again.")
            } finally {
                pending = null
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onReply(status.id) },
                interactionSource = replySource,
                modifier = Modifier.semantics {
                    contentDescription =
                        if (hideCounts) "Reply" else "Reply, ${formatCount(status.replies_count)} replies"
                },
            ) {
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.bouncing(replySource),
                )
            }
            if (!hideCounts && status.replies_count > 0) {
                Text(
                    text = formatCount(status.replies_count),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!hideCounts) {
                Text(
                    text = formatCount(status.reblogs_count),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            // One target for tap + long-press (IconButton has no
            // onLongClick): Box carries both, 48dp preserved. The same
            // interaction source feeds the press animation. Long-press
            // opens the Boost/Quote menu on any boostable post, boosted
            // or not (tap unboosts; the menu still offers Quote).
            Box {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .combinedClickable(
                            interactionSource = boostSource,
                            indication = androidx.compose.material3.ripple(bounded = false),
                            enabled = canBoost && pending == null,
                            role = Role.Button,
                            onClick = {
                                if (confirmBoostSetting && !status.reblogged) confirmBoost = true else toggle("reblog", "boost", status.reblogged)
                            },
                            onLongClick = { boostMenu = true },
                        )
                        .semantics {
                            contentDescription =
                                if (hideCounts) {
                                    if (status.reblogged) "Undo boost" else "Boost"
                                } else {
                                    if (status.reblogged) "Undo boost, ${formatCount(status.reblogs_count)} boosts" else "Boost, ${formatCount(status.reblogs_count)} boosts"
                                }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Repeat,
                        contentDescription = null,
                        tint = if (status.reblogged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.bouncing(boostSource).pulseOn(status.reblogged),
                    )
                }
                DropdownMenu(expanded = boostMenu, onDismissRequest = { boostMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (status.reblogged) "Undo boost" else "Boost") },
                        leadingIcon = { Icon(Icons.Filled.Repeat, contentDescription = null) },
                        onClick = {
                            boostMenu = false
                            if (confirmBoostSetting && !status.reblogged) confirmBoost = true else toggle("reblog", "boost", status.reblogged)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Quote") },
                        leadingIcon = { Icon(Icons.Filled.FormatQuote, contentDescription = null) },
                        onClick = {
                            boostMenu = false
                            actions?.openQuote(status.id)
                        },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!hideCounts) {
                Text(
                    text = formatCount(status.favourites_count),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(
                onClick = { toggle("favourite", "favourite", status.favourited) },
                enabled = pending == null,
                interactionSource = favouriteSource,
                modifier = Modifier.semantics {
                    contentDescription =
                        if (hideCounts) {
                            if (status.favourited) "Remove favourite" else "Favourite"
                        } else {
                            if (status.favourited) "Remove favourite, ${formatCount(status.favourites_count)} favourites" else "Favourite, ${formatCount(status.favourites_count)} favourites"
                        }
                },
            ) {
                Icon(
                    if (status.favourited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = null,
                    tint = if (status.favourited) LocalActionColors.current.favourite else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.bouncing(favouriteSource).pulseOn(status.favourited),
                )
            }
        }
        IconButton(
            onClick = { toggle("bookmark", "bookmark", status.bookmarked) },
            enabled = pending == null,
            interactionSource = bookmarkSource,
            modifier = Modifier.semantics {
                contentDescription = if (status.bookmarked) "Remove bookmark" else "Bookmark"
            },
        ) {
            Icon(
                if (status.bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = null,
                tint = if (status.bookmarked) LocalActionColors.current.bookmark else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.bouncing(bookmarkSource).pulseOn(status.bookmarked),
            )
        }
        StatusMenu(status = status, onNotice = onNotice)
    }

    if (confirmBoost) {
        AlertDialog(
            onDismissRequest = { confirmBoost = false },
            title = { Text("Boost this post?") },
            text = { Text("It will be shared with your followers.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBoost = false
                    toggle("reblog", "boost", status.reblogged)
                }) { Text("Boost") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBoost = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Overflow actions every Mastodon client carries: copy link, share,
 * open in browser, open the author's profile. Destructive actions stay
 * in the thread view, where the post is focused.
 */
@Composable
private fun StatusMenu(status: Status, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val actions = LocalAppActions.current
    val uriHandler = LocalUriHandler.current
    var open by remember { mutableStateOf(false) }
    val link = status.url ?: status.uri
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = "More actions for this post" },
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Copy link") },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = {
                    open = false
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Post link", link))
                    // Android 13+ shows its own clipboard confirmation.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onNotice("Link copied.")
                },
            )
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = {
                    open = false
                    shareText(context, link, "Share post")
                },
            )
            DropdownMenuItem(
                text = { Text("Open in browser") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                onClick = {
                    open = false
                    if (actions != null) actions.openUrl(link) else uriHandler.openUri(link)
                },
            )
            if (actions != null) {
                DropdownMenuItem(
                    text = { Text("Open @${status.account.acct}") },
                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    onClick = {
                        open = false
                        actions.openProfile(status.account.id)
                    },
                )
                if (status.account.id == actions.selfAccountId) {
                    // Poll posts are not editable here: re-sending a poll on
                    // edit can reset its votes, and omitting it can drop it.
                    val editable = status.poll == null
                    DropdownMenuItem(
                        text = { Text(if (editable) "Edit" else "Edit (not available for polls)") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        enabled = editable,
                        onClick = {
                            open = false
                            actions.openEdit(status.id)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Mutation responses may be boost wrappers (booster account, empty
 * content); unwrap and fold the fresh payload back into whatever shape
 * the card is currently showing.
 */
private fun mergeUpdated(outer: Status, fresh: Status): Status =
    if (outer.reblog != null) outer.copy(reblog = fresh) else fresh

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format(Locale.ROOT, "%.1fk", count / 1_000.0)
    else -> count.toString()
}

/** Absolute local date ("yyyy-MM-dd") for week-old-plus posts. */
private val ABSOLUTE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** Absolute local date + time ("Oct 16, 2026, 7:54 AM"), for the relative-times-off setting. */
private val ABSOLUTE_DATE_TIME_FORMAT =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/**
 * Compact relative timestamp: "now", "3m", "5h", "4d"; anything older
 * than a week falls back to an absolute date, matching the web client.
 */
internal fun relativeTimestamp(isoTimestamp: String): String {
    val parsed: Instant? = runCatching { OffsetDateTime.parse(isoTimestamp).toInstant() }
        .getOrElse { runCatching { Instant.parse(isoTimestamp) }.getOrNull() }
    if (parsed == null) return isoTimestamp
    val elapsed = Duration.between(parsed, Instant.now())
    if (elapsed.isNegative) return "now"
    return when {
        elapsed.toMinutes() < 1 -> "now"
        elapsed.toHours() < 1 -> "${elapsed.toMinutes()}m"
        elapsed.toDays() < 1 -> "${elapsed.toHours()}h"
        elapsed.toDays() < 7 -> "${elapsed.toDays()}d"
        else -> parsed.atZone(ZoneId.systemDefault()).format(ABSOLUTE_DATE_FORMAT)
    }
}

/** Absolute local date + time ("Oct 16, 2026, 7:54 AM"), for the relative-times-off setting. */
internal fun absoluteTimestamp(isoTimestamp: String): String {
    val parsed: Instant = runCatching { OffsetDateTime.parse(isoTimestamp).toInstant() }
        .getOrElse { runCatching { Instant.parse(isoTimestamp) }.getOrNull() }
        ?: return isoTimestamp
    return parsed.atZone(ZoneId.systemDefault()).format(ABSOLUTE_DATE_TIME_FORMAT)
}

/** Minimal row shown in place of a fully hidden (filter_action "hide") post. */
@Composable
private fun FilteredRow(hit: FilterHit, onShow: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.FilterAlt,
            contentDescription = null, // the text beside it carries the meaning
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Filtered: ${hit.title}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = "Post hidden by filter ${hit.title}"
            },
        )
        TextButton(onClick = onShow, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Show anyway")
        }
    }
}

/** Banner above a warn-filtered post's content; content stays hidden until shown. */
@Composable
private fun FilterBanner(hit: FilterHit, onShow: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Text(
            text = "Filtered: ${hit.title}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = "Post filtered by ${hit.title}"
            },
        )
        TextButton(onClick = onShow, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Show anyway")
        }
    }
}

/** Blur-scrim wrapper for blur-filtered posts; first tap reveals. */
@Composable
private fun FilterBlurredBlock(
    hit: FilterHit,
    onShow: () -> Unit,
    composeContent: Boolean,
    content: @Composable () -> Unit,
) {
    if (!composeContent) {
        // Below API 31 Modifier.blur is a no-op, so the content is not
        // composed at all. A lone matchParentSize overlay would measure to
        // zero height (the post would vanish), so show the banner instead.
        FilterBanner(hit = hit, onShow = onShow)
        return
    }
    Box {
        if (composeContent) {
            Column(
                // The blurred content is unreadable by design; keep it out
                // of the accessibility tree so TalkBack never reads it.
                Modifier.blur(14.dp).clearAndSetSemantics { },
            ) { content() }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                .clickable(onClick = onShow)
                .semantics { contentDescription = "Post filtered by ${hit.title}. Show it." },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Filtered: ${hit.title} \u2014 tap to show",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Neutral row while the filter verdict is still being resolved. */
@Composable
private fun FilterPendingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.FilterAlt,
            contentDescription = null, // the text beside it carries the meaning
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Checking filters\u2026",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = "Checking this post against your filters"
            },
        )
    }
}
