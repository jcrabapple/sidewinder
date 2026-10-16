package net.snakepit.sidewinder.core.lists

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.Page

/** `/api/v1/lists` payload subset. */
@Serializable
data class ListRecord(
    val id: String,
    val title: String,
    /** followed, list, or none. */
    val replies_policy: String = "list",
)

/** Who a list's replies come from, for the create/edit dropdown. */
enum class RepliesPolicy(val apiValue: String, val label: String) {
    List("list", "Members of this list"),
    Followed("followed", "Accounts you follow"),
    None("none", "Nobody"),
    ;

    companion object {
        fun fromApi(value: String): RepliesPolicy =
            entries.firstOrNull { it.apiValue == value } ?: List
    }
}

/**
 * List management. Port of the web client's `/api/v1/lists` usage:
 * CRUD on lists, paged membership, and add/remove by account id. GTS
 * returns an empty body for membership mutations, so those calls decode
 * into a throwaway [JsonElement].
 */
object ListsRepository {

    /** All of the signed-in user's lists. */
    suspend fun lists(api: Api): List<ListRecord> =
        api.request(
            path = "/api/v1/lists",
            serializer = ListSerializer(ListRecord.serializer()),
        )

    /** Create a list with [title] and [policy]. */
    suspend fun create(api: Api, title: String, policy: RepliesPolicy): ListRecord =
        api.request(
            path = "/api/v1/lists",
            serializer = ListRecord.serializer(),
            method = "POST",
            body = buildJsonObject {
                put("title", title)
                put("replies_policy", policy.apiValue)
            },
        )

    /** Rename a list and/or change its replies policy. */
    suspend fun update(api: Api, id: String, title: String, policy: RepliesPolicy): ListRecord =
        api.request(
            path = "/api/v1/lists/${Uri.encode(id)}",
            serializer = ListRecord.serializer(),
            method = "PUT",
            body = buildJsonObject {
                put("title", title)
                put("replies_policy", policy.apiValue)
            },
        )

    /** Delete a list; the response body is unused. */
    suspend fun delete(api: Api, id: String) {
        api.request(
            path = "/api/v1/lists/${Uri.encode(id)}",
            serializer = JsonElement.serializer(),
            method = "DELETE",
        )
    }

    /** One page of a list's members, Link-header max_id paging. */
    suspend fun members(api: Api, listId: String, maxId: String? = null): Page<Account> =
        api.page(
            path = "/api/v1/lists/${Uri.encode(listId)}/accounts",
            serializer = ListSerializer(Account.serializer()),
            maxId = maxId,
        )

    /** Add [accountIds] to a list; `account_ids[]` as repeated query values. */
    suspend fun addMembers(api: Api, listId: String, accountIds: List<String>) {
        api.request(
            path = "/api/v1/lists/${Uri.encode(listId)}/accounts",
            serializer = JsonElement.serializer(),
            method = "POST",
            query = mapOf("account_ids[]" to accountIds),
        )
    }

    /** Remove [accountIds] from a list; `account_ids[]` as repeated query values. */
    suspend fun removeMembers(api: Api, listId: String, accountIds: List<String>) {
        api.request(
            path = "/api/v1/lists/${Uri.encode(listId)}/accounts",
            serializer = JsonElement.serializer(),
            method = "DELETE",
            query = mapOf("account_ids[]" to accountIds),
        )
    }

    /** The lists that contain [accountId] (for pre-checking membership elsewhere). */
    suspend fun listsContaining(api: Api, accountId: String): List<ListRecord> =
        api.request(
            path = "/api/v1/accounts/${Uri.encode(accountId)}/lists",
            serializer = ListSerializer(ListRecord.serializer()),
        )
}
