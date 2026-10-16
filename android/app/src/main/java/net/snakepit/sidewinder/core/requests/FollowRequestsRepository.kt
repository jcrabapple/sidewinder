package net.snakepit.sidewinder.core.requests

import kotlinx.serialization.builtins.ListSerializer
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Relationship
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.Page

/**
 * Pending follow requests on the authenticated account. Port of the web
 * client's `/api/v1/follow_requests` usage. Note that authorize and
 * reject take the ACCOUNT id as the path segment, not a request id.
 */
object FollowRequestsRepository {

    /** One page of accounts that have requested to follow you. */
    suspend fun requests(api: Api, maxId: String? = null): Page<Account> =
        api.page(
            path = "/api/v1/follow_requests",
            serializer = ListSerializer(Account.serializer()),
            maxId = maxId,
        )

    /** Approve a pending follow request; the account becomes a follower.
     *  GTS rejects bodyless POSTs, hence the empty form body. */
    suspend fun authorize(api: Api, accountId: String): Relationship =
        api.postForm(
            path = "/api/v1/follow_requests/$accountId/authorize",
            form = emptyMap(),
            serializer = Relationship.serializer(),
        )

    /** Decline a pending follow request. */
    suspend fun reject(api: Api, accountId: String): Relationship =
        api.postForm(
            path = "/api/v1/follow_requests/$accountId/reject",
            form = emptyMap(),
            serializer = Relationship.serializer(),
        )
}
