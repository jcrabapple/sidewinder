package net.snakepit.sidewinder.core.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Relationship
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.Page

/** The editable source returned only by verify_credentials, never rendered profile HTML. */
@Serializable
data class VerifiedProfile(
    val source: Source = Source(),
    val avatar_description: String? = null,
    val header_description: String? = null,
) {
    @Serializable
    data class Source(val note: String = "")
}

/**
 * Profile and relationship data access. Paging uses the Link-header max_id
 * handled inside `Api.page`.
 */
object ProfileRepository {

    /** GET /api/v1/accounts/verify_credentials - plain source fields for the editor. */
    suspend fun verifiedProfile(api: Api): VerifiedProfile =
        api.request("/api/v1/accounts/verify_credentials", VerifiedProfile.serializer())

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

    /** Pinned posts are fetched separately, then merged ahead of the first Posts page. */
    suspend fun pinnedStatuses(api: Api, id: String): List<Status> = api.request(
        path = "/api/v1/accounts/$id/statuses",
        serializer = ListSerializer(Status.serializer()),
        query = mapOf("pinned" to listOf("true")),
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

    /** POST /api/v1/accounts/{id}/block - block this account. */
    suspend fun block(api: Api, id: String): Relationship =
        api.postForm("/api/v1/accounts/$id/block", emptyMap(), Relationship.serializer())

    /** POST /api/v1/accounts/{id}/unblock - remove this account's block. */
    suspend fun unblock(api: Api, id: String): Relationship =
        api.postForm("/api/v1/accounts/$id/unblock", emptyMap(), Relationship.serializer())

    /**
     * PATCH /api/v1/accounts/update_credentials — profile fields as form
     * params (the endpoint is formData, JSON is not bound). Image changes
     * need file uploads, which go through a separate multipart call by the
     * caller; this handles text, flags, and description-only image edits.
     */
    suspend fun updateCredentials(
        api: Api,
        displayName: String? = null,
        note: String? = null,
        locked: Boolean? = null,
        discoverable: Boolean? = null,
        indexable: Boolean? = null,
        avatarDescription: String? = null,
        headerDescription: String? = null,
    ): Account {
        val form = buildMap {
            displayName?.let { put("display_name", it) }
            note?.let { put("note", it) }
            locked?.let { put("locked", it.toString()) }
            discoverable?.let { put("discoverable", it.toString()) }
            indexable?.let { put("indexable", it.toString()) }
            avatarDescription?.let { put("avatar_description", it) }
            headerDescription?.let { put("header_description", it) }
        }
        return api.patchForm(
            path = "/api/v1/accounts/update_credentials",
            form = form,
            serializer = Account.serializer(),
        )
    }
}
