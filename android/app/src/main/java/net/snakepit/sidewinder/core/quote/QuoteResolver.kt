package net.snakepit.sidewinder.core.quote

import java.util.Optional
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import net.snakepit.sidewinder.core.model.Mention
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.model.Tag
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.search.SearchRepository
import net.snakepit.sidewinder.core.text.StatusText
import org.jsoup.Jsoup

/**
 * Link-quote detection over sanitized status HTML. GTS 0.22.2 has no
 * native quote posts, so a quote is a status whose text ends with a line
 * `RE: <url of the quoted post>` (the fediverse fallback convention).
 * Detection has two forms:
 *
 * 1. Primary (explicit): the plain text ends with `RE: <url>` and that
 *    url is the href of a link in the content. The trailing paragraph is
 *    then stripped from the rendered body ([stripReLine]).
 * 2. Secondary (implicit): the LAST link in the content is a status
 *    permalink and not a mention or hashtag link. The link stays visible.
 */
object QuoteLinks {

    /** A detected quote: the quoted post's URL and how it was found. */
    data class QuoteRef(val url: String, val explicitRe: Boolean)

    /** Plain-text tail `RE: <url>` (case-insensitive RE, optional whitespace). */
    private val RE_TAIL = Regex("(?is)\\bRE:\\s*(\\S+)\\s*$")

    /** Status permalink shapes: GTS, Mastodon, GTS/users, Pleroma/Akkoma, Misskey. */
    private val PERMALINKS = listOf(
        Regex("^https?://[^/]+/@[^/]+/statuses/[^/?#]+$"),
        Regex("^https?://[^/]+/@[^/]+/[^/?#]+$"),
        Regex("^https?://[^/]+/users/[^/]+/statuses/[^/?#]+$"),
        Regex("^https?://[^/]+/notice/[^/?#]+$"),
        Regex("^https?://[^/]+/notes/[^/?#]+$"),
    )

    /** True when [url] has one of the known status-permalink shapes. */
    fun isStatusPermalink(url: String): Boolean = PERMALINKS.any { it.containsMatchIn(url) }

    /**
     * Detect a quote in a status. Returns null when the status is not a
     * quote post: mention and hashtag links never count, and a post that
     * merely links an article is not a quote.
     */
    /** Detection results per content HTML (runs from composition on every card). */
    private val detectCache = object : LinkedHashMap<String, Optional<QuoteRef>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Optional<QuoteRef>>?) = size > 1000
    }

    fun detect(html: String, mentions: List<Mention>, tags: List<Tag>): QuoteRef? {
        // Fast path: most posts have no links at all, so skip the parse.
        if (!html.contains("<a", ignoreCase = true)) return null
        synchronized(detectCache) { detectCache[html] }?.let { return it.orElse(null) }
        val result = detectUncached(html, mentions, tags)
        synchronized(detectCache) { detectCache[html] = Optional.ofNullable(result) }
        return result
    }

    private fun detectUncached(html: String, mentions: List<Mention>, tags: List<Tag>): QuoteRef? {
        val anchors = Jsoup.parse(html).select("a[href]")
        if (anchors.isEmpty()) return null
        val routed = mentions.mapTo(ArrayList()) { it.url } + tags.map { it.url }
        fun routed(href: String) = routed.any { it.equals(href, ignoreCase = true) }

        // Primary: plain text ends with RE: <url> and that url is linked.
        RE_TAIL.find(StatusText.plain(html))?.let { match ->
            val url = match.groupValues[1]
            if (!routed(url) && anchors.any { it.attr("href").equals(url, ignoreCase = true) }) {
                return QuoteRef(url = url, explicitRe = true)
            }
        }

        // Secondary: the LAST link is a permalink, not a mention/hashtag.
        val lastHref = anchors.lastOrNull()?.attr("href")
        if (lastHref != null && !routed(lastHref) && isStatusPermalink(lastHref)) {
            return QuoteRef(url = lastHref, explicitRe = false)
        }
        return null
    }

    /**
     * Remove the trailing paragraph / line that is exactly
     * `RE: <a href=url>...</a>` from the HTML, including a `<br>`-led RE
     * line inside the last paragraph. Returns the HTML unchanged when the
     * RE line for [url] is not present at the tail.
     */
    fun stripReLine(html: String, url: String): String {
        val escaped = Regex.escape(url)
        val line = "(?is)RE:\\s*<a\\s[^>]*href=\"$escaped\"[^>]*>.*?</a>"
        // A whole trailing paragraph holding only the RE line.
        val asParagraph = Regex("<p[^>]*>\\s*$line\\s*</p>\\s*$")
        asParagraph.find(html)?.let { return html.replaceRange(it.range, "") }
        // A trailing <br> + RE line inside the last paragraph.
        val afterBr = Regex("(?is)<br\\s*/?>\\s*$line(\\s*</p>\\s*)$")
        afterBr.find(html)?.let { match ->
            return html.replaceRange(match.range, match.groupValues[1])
        }
        // The whole content is just the RE line (no paragraph wrapper).
        val bare = Regex("(?is)^\\s*$line\\s*$")
        if (bare.containsMatchIn(html)) return ""
        return html
    }

    /**
     * When [url] points at the session's own server ([base]) with path
     * `/@user/statuses/ID` or `/users/user/statuses/ID`, return ID; the
     * quoted post can then be fetched directly instead of via search.
     */
    fun localStatusId(url: String, base: String): String? {
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        val baseHost = runCatching {
            URI(if (base.contains("://")) base else "https://$base").host
        }.getOrNull() ?: return null
        if (!host.equals(baseHost, ignoreCase = true)) return null
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        return Regex("^/(?:@[^/]+|users/[^/]+)/statuses/([^/]+)$").find(path)?.groupValues?.get(1)
    }
}

/**
 * Resolves a quoted-post URL to a [Status] without blocking the parent
 * card: local urls fetch by id, remote urls go through /api/v2/search
 * with resolve=true (which can take many seconds on GTS). A process-wide
 * LRU cache plus a 5-minute negative cache keep scrolling from
 * re-searching dead links; concurrent requests for one url share a
 * single in-flight request.
 */
object QuoteResolver {

    /** Remote resolve can hang; give up after this long. */
    private const val TIMEOUT_MS = 20_000L

    /** A failed url is not retried for this long (pure decision in [shouldRetry]). */
    const val NEGATIVE_RETRY_MS = 5 * 60_000L

    private const val CACHE_SIZE = 200

    private val lock = Any()
    private val positive = object : LinkedHashMap<String, Status>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Status>): Boolean =
            size > CACHE_SIZE
    }
    private val negative = HashMap<String, Long>()
    private val inFlight = HashMap<String, Deferred<Status?>>()

    /**
     * Fetches run here, not in the caller's scope: a card that scrolls away
     * (cancelling its effect) must not cancel a lookup other cards share.
     */
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Pure negative-cache policy: a failure older than 5 minutes may retry. */
    fun shouldRetry(failedAtMs: Long, nowMs: Long): Boolean =
        nowMs - failedAtMs >= NEGATIVE_RETRY_MS

    /** Cache verdict: a hit, a still-fresh failure, or null (go fetch). */
    internal sealed interface Cached {
        data class Hit(val status: Status) : Cached
        data object Miss : Cached
    }

    suspend fun resolve(api: Api, base: String, url: String): Status? {
        when (val cached = readCache(url)) {
            is Cached.Hit -> return cached.status
            Cached.Miss -> return null
            null -> Unit
        }
        val deferred = synchronized(lock) {
            inFlight[url] ?: fetchScope.async {
                try {
                    fetchCapped(api, base, url)
                } finally {
                    synchronized(lock) { inFlight.remove(url) }
                }
            }.also { inFlight[url] = it }
        }
        // Cancellation of THIS caller propagates; the shared fetch continues.
        return deferred.await()
    }

    private fun readCache(url: String): Cached? = synchronized(lock) {
        positive[url]?.let { return Cached.Hit(it) }
        val failedAt = negative[url] ?: return null
        if (shouldRetry(failedAt, System.currentTimeMillis())) null else Cached.Miss
    }

    /** One fetch attempt with the 20s cap; failures land in the negative cache. */
    private suspend fun fetchCapped(api: Api, base: String, url: String): Status? =
        try {
            withTimeout(TIMEOUT_MS) { fetch(api, base, url) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            recordFailure(url)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(url)
            null
        }

    private suspend fun fetch(api: Api, base: String, url: String): Status? {
        QuoteLinks.localStatusId(url, base)?.let { id ->
            val local = api.request("/api/v1/statuses/$id", Status.serializer())
            return local.also { recordSuccess(url, it) }
        }
        val found = SearchRepository.search(api, query = url, type = "statuses", limit = 1, resolve = true)
            .statuses.firstOrNull()
        return found?.also { recordSuccess(url, it) }
    }

    private fun recordSuccess(url: String, status: Status) = synchronized(lock) {
        positive[url] = status
        negative.remove(url)
    }

    private fun recordFailure(url: String) = synchronized(lock) {
        negative[url] = System.currentTimeMillis()
    }
}
