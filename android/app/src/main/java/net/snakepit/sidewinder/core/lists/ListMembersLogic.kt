package net.snakepit.sidewinder.core.lists

import net.snakepit.sidewinder.core.model.Account

/** Pure helpers for duplicate suppression in list-member search results. */
object ListMembersLogic {

    /** Every id currently on the list (optimistic adds/removals included). */
    fun memberIds(members: List<Account>): Set<String> = members.mapTo(HashSet()) { it.id }

    /** Search hits minus anyone already on the list. */
    fun hideExistingMembers(results: List<Account>, memberIds: Set<String>): List<Account> =
        results.filterNot { it.id in memberIds }
}
