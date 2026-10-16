package net.snakepit.sidewinder.core.filters

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Pure filter-matching logic, JVM-testable. Two entry points:
 *
 *  - [verdict]: the server already matched the status and embedded a
 *    `filtered` array; pick the most relevant entry for the viewing
 *    context.
 *  - [match]: client-side fallback when the server did not filter
 *    (GTS versions without server-side filtering), against the local
 *    filter list.
 *
 * Both use the same selection rule: among still-active filters that
 * apply to [context], pick the most severe action (hide > blur > warn),
 * ties going to the first filter encountered.
 */
object FilterEngine {

    /** Relative severity of a filter action; higher wins. */
    fun severity(action: String): Int = when (action) {
        "hide" -> 3
        "blur" -> 2
        "warn" -> 1
        else -> 0
    }

    /**
     * Pick the most relevant [FilteredStatus] for [context]. Entries for
     * filters that do not apply to [context], or whose filter has expired
     * at [nowMs], are ignored. Unparseable `expires_at` counts as still
     * active (never hide content on a date-parsing failure).
     */
    fun verdict(filtered: List<FilteredStatus>, context: String, nowMs: Long): FilterHit? {
        var best: FilterHit? = null
        var bestSeverity = Int.MIN_VALUE
        for (entry in filtered) {
            val filter = entry.filter ?: continue
            if (context !in filter.context) continue
            if (isExpired(filter.expires_at, nowMs)) continue
            val hit = FilterHit(
                filterId = filter.id,
                title = filter.title,
                action = filter.filter_action,
                matchedKeywords = entry.keyword_matches,
            )
            val severity = severity(hit.action)
            if (best == null || severity > bestSeverity) {
                best = hit
                bestSeverity = severity
            }
        }
        return best
    }

    /**
     * Client-side match of [filters] against every piece of plain text in
     * [text] (body, spoiler, poll options, media descriptions). Literal
     * case-insensitive contains, with [FilterKeyword.whole_word] requiring
     * non-word characters (or string edges) immediately around the match.
     */
    fun match(filters: List<FilterV2>, text: FilterableText, context: String, nowMs: Long): FilterHit? {
        var best: FilterHit? = null
        var bestSeverity = Int.MIN_VALUE
        for (filter in filters) {
            if (context !in filter.context) continue
            if (isExpired(filter.expires_at, nowMs)) continue

            val matched = LinkedHashSet<String>()
            for (keyword in filter.keywords) {
                if (keyword.keyword.isEmpty()) continue
                val found = searchFields(text) { field -> containsKeyword(field, keyword) }
                if (found) matched.add(keyword.keyword)
            }
            if (matched.isEmpty()) continue

            val hit = FilterHit(
                filterId = filter.id,
                title = filter.title,
                action = filter.filter_action,
                matchedKeywords = matched.toList(),
            )
            val severity = severity(hit.action)
            if (best == null || severity > bestSeverity) {
                best = hit
                bestSeverity = severity
            }
        }
        return best
    }

    /** True when [expiresAt] is a parseable instant at or before [nowMs]. */
    private fun isExpired(expiresAt: String?, nowMs: Long): Boolean {
        if (expiresAt.isNullOrBlank()) return false
        val expiry = try {
            Instant.parse(expiresAt).toEpochMilli()
        } catch (_: DateTimeParseException) {
            return false // unparseable dates never hide content
        } catch (_: java.time.DateTimeException) {
            return false
        }
        return expiry <= nowMs
    }

    /** Run [pred] against every text field; first true wins. */
    private inline fun searchFields(text: FilterableText, pred: (String) -> Boolean): Boolean {
        if (pred(text.text)) return true
        if (pred(text.spoiler)) return true
        for (option in text.pollOptions) if (pred(option)) return true
        for (description in text.mediaDescriptions) if (pred(description)) return true
        return false
    }

    /**
     * Case-insensitive contains, honouring the whole-word rule. Scans
     * [field] directly with [String.regionMatches]: lowercasing can change
     * string length (e.g. `İ`), so indices taken from a lowercased copy
     * must never be applied to the original.
     */
    private fun containsKeyword(field: String, keyword: FilterKeyword): Boolean {
        val needle = keyword.keyword.lowercase()
        if (needle.isEmpty()) return false
        val len = needle.length
        var from = 0
        while (from <= field.length - len) {
            if (field.regionMatches(from, needle, 0, len, ignoreCase = true)) {
                if (!keyword.whole_word ||
                    startsAtBoundary(field, from) && endsAtBoundary(field, from + len)
                ) {
                    return true
                }
            }
            from++
        }
        return false
    }

    /** True when [start] sits at the string edge or after a non-word char. */
    private fun startsAtBoundary(field: String, start: Int): Boolean =
        start == 0 || !isWordChar(field[start - 1])

    /** True when [end] sits at the string edge or before a non-word char. */
    private fun endsAtBoundary(field: String, end: Int): Boolean =
        end >= field.length || !isWordChar(field[end])

    private fun isWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
}
