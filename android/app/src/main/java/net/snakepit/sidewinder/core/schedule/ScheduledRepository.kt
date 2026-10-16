package net.snakepit.sidewinder.core.schedule

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.snakepit.sidewinder.core.model.MediaAttachment
import net.snakepit.sidewinder.core.network.Api

/** Pending poll parameters of a scheduled status (swagger ScheduledStatusParamsPoll). */
@Serializable
data class ScheduledPoll(
    val expires_in: Long = 0,
    val multiple: Boolean = false,
    val options: List<String> = emptyList(),
)

/** Original compose parameters of a scheduled status (swagger ScheduledStatusParams). */
@Serializable
data class ScheduledParams(
    val text: String? = null,
    val spoiler_text: String? = null,
    val visibility: String? = null,
    val sensitive: Boolean? = null,
    val language: String? = null,
    val in_reply_to_id: String? = null,
    val content_type: String? = null,
    val media_ids: List<String> = emptyList(),
    val poll: ScheduledPoll? = null,
)

/** A status queued to publish at a future time (swagger scheduledStatus). */
@Serializable
data class ScheduledStatus(
    val id: String,
    val scheduled_at: String,
    val params: ScheduledParams = ScheduledParams(),
    val media_attachments: List<MediaAttachment> = emptyList(),
)

/**
 * Scheduled posts (GTS 0.22.1: `scheduled_at` on POST /api/v1/statuses,
 * plus list / PUT-reschedule / DELETE on /api/v1/scheduled_statuses —
 * there is no content edit route; rescheduling is the only change).
 */
object ScheduledRepository {

    suspend fun list(api: Api, token: String): List<ScheduledStatus> =
        api.request(
            path = "/api/v1/scheduled_statuses",
            serializer = ListSerializer(ScheduledStatus.serializer()),
        )

    /** Moves the publish time. [scheduledAtIso] must be ISO 8601 with offset. */
    suspend fun reschedule(api: Api, token: String, id: String, scheduledAtIso: String): ScheduledStatus =
        api.request(
            path = "/api/v1/scheduled_statuses/${Uri.encode(id)}",
            serializer = ScheduledStatus.serializer(),
            method = "PUT",
            body = buildJsonObject { put("scheduled_at", scheduledAtIso) },
        )

    /** Cancels the scheduled post. */
    suspend fun cancel(api: Api, token: String, id: String) {
        api.request(
            path = "/api/v1/scheduled_statuses/${Uri.encode(id)}",
            serializer = JsonElement.serializer(),
            method = "DELETE",
        )
    }
}
