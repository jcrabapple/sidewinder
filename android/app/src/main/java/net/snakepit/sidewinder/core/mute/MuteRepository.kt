package net.snakepit.sidewinder.core.mute

import android.net.Uri
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.Page

/**
 * Mute management. Port of the web client's mute endpoints:
 *
 * - `GET /api/v1/mutes` — Link-header paged list of muted [Account]s
 *   (GTS returns accounts only; there is no duration read-back).
 * - `POST /api/v1/accounts/{id}/mute` — form params `notifications` and
 *   `duration` (seconds; 0 means indefinite).
 * - `POST /api/v1/accounts/{id}/unmute`.
 * - `POST /api/v1/statuses/{id}/mute|unmute` — per-thread muting.
 *
 * Mutation responses are unused, so they decode into a throwaway
 * [JsonElement], matching how ListsRepository handles empty-bodied
 * mutations.
 */
object MuteRepository {

    /** One page of muted accounts, Link-header max_id paging. */
    suspend fun mutes(api: Api, token: String, maxId: String? = null): Page<Account> =
        api.page(
            path = "/api/v1/mutes",
            serializer = ListSerializer(Account.serializer()),
            maxId = maxId,
        )

    /**
     * Mute [accountId]. [notifications] also mutes notifications from the
     * account; [durationSeconds] is the mute length in seconds, where 0
     * means indefinite.
     */
    suspend fun mute(api: Api, token: String, accountId: String, notifications: Boolean = true, durationSeconds: Long = 0) {
        api.postForm(
            path = "/api/v1/accounts/${Uri.encode(accountId)}/mute",
            form = mapOf(
                "notifications" to notifications.toString(),
                "duration" to durationSeconds.toString(),
            ),
            serializer = JsonElement.serializer(),
        )
    }

    /** Unmute [accountId]. */
    suspend fun unmute(api: Api, token: String, accountId: String) {
        api.request(
            path = "/api/v1/accounts/${Uri.encode(accountId)}/unmute",
            serializer = JsonElement.serializer(),
            method = "POST",
        )
    }

    /** Mute or unmute a whole conversation thread by [statusId]. */
    suspend fun setThreadMuted(api: Api, token: String, statusId: String, muted: Boolean) {
        api.request(
            path = "/api/v1/statuses/${Uri.encode(statusId)}/${if (muted) "mute" else "unmute"}",
            serializer = JsonElement.serializer(),
            method = "POST",
        )
    }
}
