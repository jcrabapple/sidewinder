package net.snakepit.sidewinder.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.quote.QuoteResolver
import net.snakepit.sidewinder.core.text.StatusText
import net.snakepit.sidewinder.ui.app.LocalAppActions

/** Resolution states of a quoted-post embed. */
private sealed interface QuoteState {
    data object Loading : QuoteState
    data class Resolved(val status: Status) : QuoteState
    data object Unavailable : QuoteState
}

/**
 * An embedded quote card below a link-quote post's body. The parent card
 * never waits on resolution: a 56dp placeholder shows while the quoted
 * post is being resolved, the card itself once it lands, and a compact
 * "unavailable" row when the link could not be resolved (remote resolve
 * can take many seconds or fail outright). Tapping the card opens the
 * quoted thread.
 */
@Composable
fun QuoteBlock(
    url: String,
    api: Api,
    baseUrl: String,
    showMediaPreviews: Boolean,
    modifier: Modifier = Modifier,
) {
    var state by remember(url) { mutableStateOf<QuoteState>(QuoteState.Loading) }
    LaunchedEffect(url) {
        state = QuoteResolver.resolve(api, baseUrl, url)
            ?.let { QuoteState.Resolved(it) }
            ?: QuoteState.Unavailable
    }
    when (val current = state) {
        QuoteState.Loading -> Box(
            modifier = modifier
                .fillMaxWidth()
                .height(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        is QuoteState.Resolved -> QuoteCard(
            quoted = current.status,
            fallbackUrl = url,
            showMediaPreviews = showMediaPreviews,
            modifier = modifier,
        )
        QuoteState.Unavailable -> UnavailableRow(url = url, modifier = modifier)
    }
}

@Composable
private fun QuoteCard(
    quoted: Status,
    fallbackUrl: String,
    showMediaPreviews: Boolean,
    modifier: Modifier = Modifier,
) {
    val actions = LocalAppActions.current
    val uriHandler = LocalUriHandler.current
    val name = quoted.account.display_name?.takeIf { it.isNotBlank() } ?: quoted.account.username
    // A quoted card never renders its own quote (no nesting): the excerpt
    // is plain text only, capped at four lines.
    val excerpt = remember(quoted.id) {
        quoted.spoiler_text.takeIf { it.isNotBlank() }?.let { "CW: $it" }
            ?: StatusText.plain(quoted.content)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Open quoted post") {
                if (actions != null) actions.openThread(quoted.id) else uriHandler.openUri(fallbackUrl)
            }
            .semantics(mergeDescendants = true) {
                contentDescription = "Quoted post by $name: $excerpt"
            }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = quoted.account.avatar_static?.ifEmpty { quoted.account.avatar } ?: quoted.account.avatar,
                contentDescription = null, // the merged card semantics carry it
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "@${quoted.account.acct}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = excerpt,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (showMediaPreviews) {
            val image = quoted.media_attachments.firstOrNull { it.type == "image" }
            if (quoted.sensitive && image != null) {
                // Do not even create an image request for sensitive quoted
                // media. The containing card still opens the original post.
                Text(
                    text = "Sensitive quoted media is hidden. Open the quoted post to view it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (image != null) {
                val preview = image.preview_url ?: image.url
                if (preview != null) {
                    AsyncImage(
                        model = preview,
                        contentDescription = null, // the merged card semantics carry it
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun UnavailableRow(url: String, modifier: Modifier = Modifier) {
    val actions = LocalAppActions.current
    val uriHandler = LocalUriHandler.current
    val open: () -> Unit = {
        if (actions != null) actions.openUrl(url) else uriHandler.openUri(url)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(
            text = "Quoted post unavailable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = open, modifier = Modifier.height(48.dp)) {
            Text("Open link")
        }
    }
}
