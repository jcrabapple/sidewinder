package net.snakepit.sidewinder.core.profile

import kotlinx.serialization.builtins.ListSerializer
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Relationship
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.Page

/**
 * Profile and relationship data access. Port of the account fetches
 * behind web `src/views/Account.tsx` and the account list views; paging
 * uses the Link-header max_id handled inside `Api.page`.
 */
object ProfileRepository {

    /** GET /api/v1/accounts/{id} - the account itself. */
    suspend fun account(api: Api, id: String): Account =
        api.request("/api/v1/accounts/$id", Account.serializer())

    /**
     * GET /api/v1/accounts/relationships?id[]={id} - relationship of the
     * signed-in account to the given accounts.
     */
    suspend fun relationships(api: Api, ids: List<String>): List<Relationship> =
        api.request(
            path = "/api/v1/accounts/relationships",
            serializer = ListSerializer(Relationship.serializer()),
            query = mapOf("id[]" to ids),
        )

    suspend fun relationship(api: Api, id: String): Relationship? =
        relationships(api, listOf(id)).firstOrNull()

    /** Which slice of an account's statuses to fetch (web Profile tabs). */
    enum class StatusesTab {
        /** Own posts only (replies excluded) - the default. */
        Posts,

        /** Own posts plus replies. */
        PostsAndReplies,

        /** Posts with media attachments only. */
        Media,
    }

    /**
     * One page of an account's statuses for a profile tab. [StatusesTab.Posts]
     * excludes replies (matching the web client's default tab); boosts are
     * kept because they are part of the account's output.
     */
    suspend fun statuses(
        api: Api,
        id: String,
        maxId: String? = null,
        tab: StatusesTab = StatusesTab.Posts,
    ): Page<Status> = api.page(
        path = "/api/v1/accounts/$id/statuses",
        serializer = ListSerializer(Status.serializer()),
        query = when (tab) {
            StatusesTab.Posts -> mapOf("exclude_replies" to listOf("true"))
            StatusesTab.PostsAndReplies -> emptyMap()
            StatusesTab.Media -> mapOf("only_media" to listOf("true"))
        },
        maxId = maxId,
    )

    /**
     * One page of an account's followers or the accounts it follows.
     * [kind] is "followers" or "following" (validated by the caller).
     */
    suspend fun accountList(api: Api, id: String, kind: String, maxId: String? = null): Page<Account> =
        api.page(
            path = "/api/v1/accounts/$id/$kind",
            serializer = ListSerializer(Account.serializer()),
            maxId = maxId,
        )

    /** POST /api/v1/accounts/{id}/follow - follow, or request to follow a locked account. */
    suspend fun follow(api: Api, id: String): Relationship =
        api.postForm("/api/v1/accounts/$id/follow", emptyMap(), Relationship.serializer())

    /** POST /api/v1/accounts/{id}/unfollow - unfollow or cancel a pending
     *  request. GTS 405s the DELETE /follow variant. */
    suspend fun unfollow(api: Api, id: String): Relationship =
        api.postForm("/api/v1/accounts/$id/unfollow", emptyMap(), Relationship.serializer())

    /**
     * PATCH /api/v1/accounts/update_credentials — profile fields as form
     * params (the endpoint is formData, JSON is not bound). Image changes
     * need file uploads, which go through a separate multipart call by the
     * caller; this handles the text/flag fields only.
     */
    suspend fun updateCredentials(
        api: Api,
        displayName: String? = null,
        note: String? = null,
        locked: Boolean? = null,
        discoverable: Boolean? = null,
        indexable: Boolean? = null,
    ): Account {
        val form = buildMap {
            displayName?.let { put("display_name", it) }
            note?.let { put("note", it) }
            locked?.let { put("locked", it.toString()) }
            discoverable?.let { put("discoverable", it.toString()) }
            indexable?.let { put("indexable", it.toString()) }
        }
        return api.patchForm(
            path = "/api/v1/accounts/update_credentials",
            form = form,
            serializer = Account.serializer(),
        )
    }
}
