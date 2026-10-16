package net.snakepit.sidewinder.core.catchup

import java.time.Instant
import java.time.OffsetDateTime
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.timeline.TimelineRepository
import net.snakepit.sidewinder.core.timeline.TimelineSource

/**
 * Backward walk over the home timeline for the catch-up digest. Port of
 * web `src/views/CatchUp.tsx`: the app has no timeline read-mark, so the
 * digest cuts by `created_at` instead. Pages backwards through
 * [TimelineRepository.fetch] until a post is older than the cutoff,
 * bounded by [MAX_POSTS] and [MAX_PAGES].
 */
object CatchUpRepository {

    /** Stop collecting after this many posts, even if the range continues. */
    const val MAX_POSTS = 800

    /** Hard page cap so a slow range cannot request forever. */
    const val MAX_PAGES = 20

    /** [cutoffFor] of the digest range, N hours before [now]. */
    fun cutoffFor(hours: Int, now: Instant = Instant.now()): Instant =
        now.minusSeconds(hours * 3600L)

    /**
     * Home posts newer than [cutoff], oldest first (the API pages
     * newest first; collected pages are reversed at the end like the
     * web view does). [onProgress] reports the running collected count
     * so the screen can show "Loading N posts…".
     */
    suspend fun collect(
        api: Api,
        cutoff: Instant,
        onProgress: suspend (Int) -> Unit = {},
    ): List<Status> {
        val collected = mutableListOf<Status>()
        var maxId: String? = null
        repeat(MAX_PAGES) {
            val page = TimelineRepository.fetch(api, TimelineSource.Home, maxId)
            if (page.items.isEmpty()) return collected.toList().asReversed()
            var reachedCutoff = false
            for (status in page.items) {
                if (createdAt(status) < cutoff) {
                    reachedCutoff = true
                    break
                }
                collected.add(status)
            }
            onProgress(collected.size)
            if (reachedCutoff || collected.size >= MAX_POSTS) return collected.toList().asReversed()
            maxId = page.nextMaxId ?: return collected.toList().asReversed()
        }
        return collected.toList().asReversed()
    }

    /** ISO `created_at` parse; unparseable stamps count as very old. */
    fun createdAt(status: Status): Instant =
        runCatching { OffsetDateTime.parse(status.created_at).toInstant() }.getOrDefault(Instant.EPOCH)
}

/** Digest row filter, port of the web view's All / Originals / Boosts / Replies tabs. */
enum class DigestFilter(val label: String) {
    All("All"),
    Originals("Originals"),
    Boosts("Boosts"),
    Replies("Replies"),
}

/** Digest row order; both sort by status id (snowflake ids order by time). */
enum class DigestSort(val label: String) {
    Newest("Newest first"),
    Oldest("Oldest first"),
}

/**
 * One digest row: a status, or several boosts of the same original
 * collapsed onto it. Port of the web DigestEntry.
 */
data class DigestEntry(
    /** The newest wrapper seen (boost) or the status itself. */
    val outer: Status,
    /** The underlying status (`outer.reblog ?: outer`). */
    val status: Status,
    /** Booster accounts, newest first; empty for non-boosts. */
    val boosters: List<Account>,
)

/**
 * Collapse boost wrappers of the same original into one [DigestEntry].
 * Input is chronological (as [CatchUpRepository.collect] returns);
 * later wrappers are newer, so their booster goes to the front of the
 * list and replaces [DigestEntry.outer].
 */
fun collapseBoosts(statuses: List<Status>): List<DigestEntry> {
    val byOriginal = LinkedHashMap<String, DigestEntry>()
    for (outer in statuses) {
        val inner = outer.reblog
        val originalId = inner?.id ?: outer.id
        val existing = byOriginal[originalId]
        when {
            existing != null && inner != null ->
                byOriginal[originalId] =
                    existing.copy(outer = outer, boosters = listOf(outer.account) + existing.boosters)
            existing == null ->
                byOriginal[originalId] = DigestEntry(
                    outer = outer,
                    status = inner ?: outer,
                    boosters = if (inner != null) listOf(outer.account) else emptyList(),
                )
        }
    }
    return byOriginal.values.toList()
}

/**
 * Filter and sort digest rows. A reply is a post answering someone else
 * (port of the web check `in_reply_to_account_id !== me`); sort compares
 * the wrapper id so collapsed boosts group with their newest wrapper.
 */
fun applyDigestFilter(
    entries: List<DigestEntry>,
    filter: DigestFilter,
    sort: DigestSort,
    selfAccountId: String,
): List<DigestEntry> {
    fun isReply(entry: DigestEntry): Boolean =
        entry.status.in_reply_to_id != null && entry.status.in_reply_to_account_id != selfAccountId

    val filtered = entries.filter { entry ->
        val boosted = entry.boosters.isNotEmpty()
        when (filter) {
            DigestFilter.All -> true
            DigestFilter.Originals -> !boosted && !isReply(entry)
            DigestFilter.Boosts -> boosted
            DigestFilter.Replies -> !boosted && isReply(entry)
        }
    }
    return when (sort) {
        DigestSort.Newest -> filtered.sortedByDescending { it.outer.id }
        DigestSort.Oldest -> filtered.sortedBy { it.outer.id }
    }
}
