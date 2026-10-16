package net.snakepit.sidewinder.core.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.snakepit.sidewinder.core.model.Notification
import net.snakepit.sidewinder.core.model.Status

/**
 * One server-sent event from the GTS streaming endpoint. Port of the
 * Mastodon streaming event envelope: a JSON object with an `event` type
 * and a string `payload`.
 */
sealed interface StreamEvent {
    /** `update`: payload is a Status JSON string. */
    data class StatusUpdate(val status: Status) : StreamEvent

    /** `delete`: payload is a plain status id string. */
    data class StatusDeleted(val statusId: String) : StreamEvent

    /** `notification`: payload is a Notification JSON string. */
    data class NotificationArrived(val notification: Notification) : StreamEvent

    /** `filters_changed`: no payload. */
    data object FiltersChanged : StreamEvent
}

/**
 * Pure parser for streaming event frames. JVM-testable: no Android or
 * OkHttp imports. Never throws — malformed input yields null.
 */
object StreamEventParser {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Parses one frame of the form `{"event": "...", "payload": "..."}`.
     * Unknown event types and malformed JSON return null.
     */
    fun parse(text: String): StreamEvent? = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        when (obj["event"]?.jsonPrimitive?.contentOrNull) {
            "update" -> obj["payload"]?.jsonPrimitive?.contentOrNull
                ?.let { payload -> json.decodeFromString(Status.serializer(), payload) }
                ?.let { StreamEvent.StatusUpdate(it) }

            "notification" -> obj["payload"]?.jsonPrimitive?.contentOrNull
                ?.let { payload -> json.decodeFromString(Notification.serializer(), payload) }
                ?.let { StreamEvent.NotificationArrived(it) }

            "delete" -> obj["payload"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { StreamEvent.StatusDeleted(it) }

            "filters_changed" -> StreamEvent.FiltersChanged

            else -> null
        }
    }.getOrNull()
}
