package net.snakepit.sidewinder.core.lists

import net.snakepit.sidewinder.core.model.Account

/**
 * Pure helpers for the list-members screen: duplicate suppression for
 * search results, and the backoff that keeps a failing infinite-scroll
 * page from retrying in a tight loop.
 */
object ListMembersLogic {

    /** Every id currently on the list (optimistic adds/removals included). */
    fun memberIds(members: List<Account>): Set<String> = members.mapTo(HashSet()) { it.id }

    /** Search hits minus anyone already on the list. */
    fun hideExistingMembers(results: List<Account>, memberIds: Set<String>): List<Account> =
        results.filterNot { it.id in memberIds }

    /**
     * Failed load-more backoff: 1s doubling per consecutive failure,
     * capped at 30s. Zero failures retry immediately.
     */
    object PageRetry {
        const val BASE_MS = 1_000L
        const val MAX_MS = 30_000L

        fun delayMs(consecutiveFailures: Int): Long {
            if (consecutiveFailures <= 0) return 0
            val shift = (consecutiveFailures - 1).coerceAtMost(30)
            return minOf(MAX_MS, BASE_MS shl shift)
        }
    }
}
