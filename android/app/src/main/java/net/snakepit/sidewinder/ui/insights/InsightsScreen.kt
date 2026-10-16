package net.snakepit.sidewinder.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import net.snakepit.sidewinder.core.insights.InsightsCache
import net.snakepit.sidewinder.core.insights.InsightsRepository
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError

/** Four mandatory states for the insights load. */
private sealed interface InsightsState {
    /** Loading with a running fetched count for the progress copy. */
    data class Loading(val fetched: Int) : InsightsState

    data class Error(val message: String) : InsightsState

    /** The account has no posts to analyse. */
    data object Empty : InsightsState

    data class Content(val insights: InsightsRepository.Insights) : InsightsState
}

/**
 * Client-side stats over the signed-in account, port of web
 * `state/insights.ts` + `components/Insights.tsx`: the account's own
 * statuses are paged once (12 pages of 40 at most) and counted on
 * device. The copy says so, because nothing is sent anywhere.
 */
@Composable
fun InsightsScreen(api: Api, session: Session) {
    var refreshKey by remember(session.id) { mutableStateOf(0) }
    var state by remember(session.id) { mutableStateOf<InsightsState>(InsightsState.Loading(0)) }

    LaunchedEffect(api, session.id, refreshKey) {
        // Rotation re-runs the load effect; the cache (keyed by session)
        // serves it instead of re-fetching up to 12 pages. refreshKey > 0
        // means the explicit refresh button: bypass and drop the cache.
        if (refreshKey > 0) InsightsCache.invalidate(session.id)
        val cached = if (refreshKey == 0) InsightsCache.get(session.id) else null
        if (cached != null) {
            state = if (cached.fetched == 0) InsightsState.Empty else InsightsState.Content(cached)
            return@LaunchedEffect
        }
        state = InsightsState.Loading(0)
        state = try {
            val insights = InsightsRepository.load(api, session.account.id) { fetched ->
                state = InsightsState.Loading(fetched)
            }
            InsightsCache.put(session.id, insights)
            if (insights.fetched == 0) InsightsState.Empty else InsightsState.Content(insights)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            InsightsState.Error(
                "Could not load your stats: the server returned error ${e.status}. Check your connection, then retry.",
            )
        } catch (e: Exception) {
            InsightsState.Error(
                "Could not load your stats: ${e.message ?: "network error"}. Check your connection, then retry.",
            )
        }
    }

    fun reload() {
        refreshKey++
    }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            ) {
                Text(
                    text = "Insights",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { reload() },
                    enabled = state !is InsightsState.Loading,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Refresh insights" },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
            }

            when (val s = state) {
                is InsightsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics { contentDescription = "Loading your insights" },
                        )
                        Text(
                            text = if (s.fetched > 0) "Reading ${s.fetched} posts" else "Reading your posts",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is InsightsState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { reload() },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "Retry loading your insights" },
                        ) {
                            Text("Retry")
                        }
                    }
                }

                InsightsState.Empty -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("No posts yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Post something and your stats will build up here.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is InsightsState.Content -> InsightsBody(s.insights)
            }
        }
    }
}

@Composable
private fun InsightsBody(insights: InsightsRepository.Insights) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "privacy") {
            Text(
                text = "Computed on this device from your own posts. Nothing is sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item(key = "grid") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                StatRow(
                    left = Stat("Posts", insights.posts.toLong(), "${insights.posts} posts"),
                    right = Stat(
                        "Boosts given",
                        insights.boostsGiven.toLong(),
                        "${insights.boostsGiven} boosts given",
                    ),
                )
                StatRow(
                    left = Stat(
                        "Favourites received",
                        insights.favouritesReceived,
                        "${insights.favouritesReceived} favourites received",
                    ),
                    right = Stat(
                        "Replies made",
                        insights.repliesMade.toLong(),
                        "${insights.repliesMade} replies made",
                    ),
                )
                StatRow(
                    left = Stat(
                        "Median engagement",
                        insights.medianEngagement,
                        "Median engagement score ${insights.medianEngagement}",
                    ),
                    right = Stat(
                        "Posts analysed",
                        insights.fetched.toLong(),
                        "${insights.fetched} posts analysed",
                    ),
                )
            }
        }
        if (insights.topHashtags.isNotEmpty()) {
            item(key = "hashtags-header") {
                Text(
                    text = "Most used hashtags",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "hashtags") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    insights.topHashtags.forEachIndexed { index, tag ->
                        Text(
                            text = "#${tag.name}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .semantics {
                                    contentDescription =
                                        "#${tag.name}, used in ${tag.count} " + if (tag.count == 1) "post" else "posts"
                                },
                        )
                        if (index < insights.topHashtags.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
        item(key = "summary") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text(
                    text = "In short",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = summary(insights),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Text(
                    text = "Engagement counts favourites, twice for boosts, plus replies.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** One grid row: two stat cells side by side. */
@Composable
private fun StatRow(left: Stat, right: Stat) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        StatCell(left, Modifier.weight(1f))
        StatCell(right, Modifier.weight(1f))
    }
}

private data class Stat(val label: String, val value: Long, val description: String)

@Composable
private fun StatCell(stat: Stat, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = stat.description },
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = formatCount(stat.value),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stat.label,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Compact counts: 999, then 1.2k, 3.4m (port of web formatCount). */
private fun formatCount(n: Long): String = when {
    n < 1000 -> n.toString()
    n < 1_000_000 -> trim(n / 1000.0) + "k"
    else -> trim(n / 1_000_000.0) + "m"
}

private fun trim(value: Double): String {
    val rounded = (value * 10).toLong() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}

/**
 * Plain-language summary. Engagement follows the web formula
 * (favourites + 2×boosts + replies), so "engagement score" names a
 * local metric, not a server one.
 */
private fun summary(insights: InsightsRepository.Insights): String {
    val parts = mutableListOf<String>()
    parts.add(
        if (insights.posts == 1) "You have made 1 post" else "You have made ${insights.posts} posts",
    )
    if (insights.boostsGiven > 0) {
        parts.add(
            if (insights.boostsGiven == 1) "boosted once" else "boosted ${insights.boostsGiven} times",
        )
    }
    parts.add(
        if (insights.favouritesReceived == 1L) "your posts earned 1 favourite"
        else "your posts earned ${insights.favouritesReceived} favourites",
    )
    if (insights.repliesMade > 0) {
        parts.add(
            if (insights.repliesMade == 1) "and you replied once" else "and you replied ${insights.repliesMade} times",
        )
    }
    val sentence = parts.joinToString(", ")
        .replace(", and", " and")
    val hashtagLine = insights.topHashtags.firstOrNull()?.let { top ->
        " #${top.name} is your most used hashtag."
    } ?: ""
    return "$sentence.$hashtagLine A typical post scores ${insights.medianEngagement} engagement."
}
