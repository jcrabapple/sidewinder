package net.snakepit.sidewinder.core.notifications

import kotlinx.serialization.builtins.ListSerializer
import net.snakepit.sidewinder.core.model.Notification
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.Page

/**
 * Read access to the authenticated account's notifications. Port of the
 * web client's `/api/v1/notifications` usage; paging uses the
 * Link-header max_id handled inside `Api.page`.
 */
object NotificationsRepository {

    /**
     * One page of notifications, oldest-to-newest paging via [maxId].
     * When [types] is non-null it is sent as the repeated `types[]`
     * query parameter to filter by notification type (e.g. "mention").
     */
    suspend fun notifications(
        api: Api,
        maxId: String? = null,
        types: List<String>? = null,
    ): Page<Notification> =
        api.page(
            path = "/api/v1/notifications",
            serializer = ListSerializer(Notification.serializer()),
            query = types?.let { mapOf("types[]" to it) } ?: emptyMap(),
            maxId = maxId,
        )
}
