package net.snakepit.sidewinder.ui.trends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.trends.TrendingLink
import net.snakepit.sidewinder.core.trends.TrendingStats
import net.snakepit.sidewinder.core.trends.TrendingTag
import net.snakepit.sidewinder.core.trends.TrendsRepository
import net.snakepit.sidewinder.ui.timeline.TagFollowButton

private sealed interface TrendsState {
    /** First load still running. */
    data object Loading : TrendsState

    /** First load failed; [section] names which part failed. */
    data class Error(val section: String, val message: String) : TrendsState

    /** Both lists loaded and both empty. */
    data object Empty : TrendsState

    /** At least one list has content. */
    data class Content(val tags: List<TrendingTag>, val links: List<TrendingLink>) : TrendsState
}

/**
 * Trends tab content: trending hashtags and trending links. Embedded by
 * the shell inside SearchScreen, so this composable owns NO Scaffold and
 * NO top bar — the container supplies insets and chrome.
 */
@Composable
fun TrendsColumn(
    api: Api,
    session: Session,
    onOpenTag: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var state by remember { mutableStateOf<TrendsState>(TrendsState.Loading) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        state = TrendsState.Loading
        val tags: List<TrendingTag>
        try {
            tags = TrendsRepository.trendingTags(api, session.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = TrendsState.Error(
                "trending hashtags",
                "${describe(e)}. Check your connection, then retry.",
            )
            return
        }
        val links: List<TrendingLink> = try {
            TrendsRepository.trendingLinks(api, session.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tags still stand; links degrade to an empty section.
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Could not load trending links: ${describe(e)}. Pull back into this tab to retry.",
                )
            }
            emptyList()
        }
        state = if (tags.isEmpty() && links.isEmpty()) {
            TrendsState.Empty
        } else {
            TrendsState.Content(tags, links)
        }
    }

    LaunchedEffect(api, session.id) { load() }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            TrendsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    Modifier.semantics { contentDescription = "Loading trends" },
                )
            }

            is TrendsState.Error -> ErrorState(s, onRetry = { scope.launch { load() } })

            TrendsState.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing is trending right now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is TrendsState.Content -> TrendsContent(
                tags = s.tags,
                links = s.links,
                api = api,
                onOpenTag = onOpenTag,
                onOpenUrl = onOpenUrl,
                onNotice = { scope.launch { snackbarHostState.showSnackbar(it) } },
            )
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

private fun describe(e: Exception): String =
    (e as? ApiError)?.let { "the server returned error ${it.status}" }
        ?: (e.message ?: "network error")

/** Error state for a failed first load, naming the failed section. */
@Composable
private fun ErrorState(error: TrendsState.Error, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Could not load ${error.section}: ${error.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier
                .padding(top = 16.dp)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Retry loading trends" },
        ) {
            Text("Retry")
        }
    }
}

/** The two sections, scrolled together in one LazyColumn. */
@Composable
private fun TrendsContent(
    tags: List<TrendingTag>,
    links: List<TrendingLink>,
    api: Api,
    onOpenTag: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onNotice: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (tags.isNotEmpty()) {
            item(key = "tags-header") {
                SectionHeader("Trending hashtags")
            }
            items(tags, key = { "tag-" + it.name }) { tag ->
                TagRow(tag, api, onOpenTag, onNotice)
                HorizontalDivider()
            }
        }
        if (links.isNotEmpty()) {
            item(key = "links-header") {
                SectionHeader("Trending links")
            }
            items(links, key = { "link-" + it.url }) { link ->
                LinkRow(link, onOpenUrl)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/**
 * One trending hashtag: name, this-week usage summary, and a follow
 * toggle from the timeline package.
 */
@Composable
private fun TagRow(
    tag: TrendingTag,
    api: Api,
    onOpenTag: (String) -> Unit,
    onNotice: (String) -> Unit,
) {
    val uses = TrendingStats.totalUses(tag)
    val accounts = TrendingStats.totalAccounts(tag)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                onClick = { onOpenTag(tag.name) },
                onClickLabel = "Open hashtag ${tag.name}",
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("#${tag.name}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "$uses posts · $accounts people this week",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TagFollowButton(api = api, name = tag.name, onNotice = onNotice)
    }
}

/** One trending link: thumbnail (when the server provides one), title, provider and domain. */
@Composable
private fun LinkRow(link: TrendingLink, onOpenUrl: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(
                onClick = { onOpenUrl(link.url) },
                onClickLabel = "Open ${link.provider_name ?: "link"}",
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        link.image?.takeIf { it.isNotBlank() }?.let { image ->
            AsyncImage(
                model = image,
                contentDescription = null, // decorative; the row text carries the identity
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                link.title.ifBlank { link.url },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val host = link.url.substringAfter("://").substringBefore('/')
            val provider = listOfNotNull(
                link.provider_name?.takeIf { it.isNotBlank() },
                host.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (provider.isNotBlank()) {
                Text(
                    provider,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
