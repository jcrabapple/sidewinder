package net.snakepit.sidewinder.core.thread

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.model.StatusContext
import net.snakepit.sidewinder.core.model.StatusEdit
import net.snakepit.sidewinder.core.network.Api

/** Thread data access: port of the context fetches behind web `src/views/Thread.tsx`. */
object ThreadRepository {

    /** GET /api/v1/statuses/{id} - the focused status itself (context does not include it). */
    suspend fun status(api: Api, id: String): Status =
        api.request("/api/v1/statuses/$id", Status.serializer())

    /** GET /api/v1/statuses/{id}/context - ancestor chain and descendant replies. */
    suspend fun context(api: Api, id: String): StatusContext =
        api.request("/api/v1/statuses/$id/context", StatusContext.serializer())

    /** DELETE /api/v1/statuses/{id} - removes own status; the response body is unused. */
    suspend fun delete(api: Api, id: String) {
        api.request("/api/v1/statuses/$id", JsonElement.serializer(), method = "DELETE")
    }

    /** Edit revisions of a status, oldest first (GET /api/v1/statuses/{id}/history). */
    suspend fun history(api: Api, id: String): List<StatusEdit> =
        api.request(
            path = "/api/v1/statuses/$id/history",
            serializer = ListSerializer(StatusEdit.serializer()),
        )

    /** GET /api/v1/statuses/{id}/source - the editable source text of an own status. */
    suspend fun source(api: Api, id: String): StatusSource =
        api.request("/api/v1/statuses/$id/source", StatusSource.serializer())

    /** PUT /api/v1/statuses/{id} - edit an own status; returns the updated status. */
    suspend fun edit(api: Api, id: String, body: JsonObject): Status =
        api.request("/api/v1/statuses/$id", Status.serializer(), method = "PUT", body = body)
}

/** `/api/v1/statuses/{id}/source` payload: the plain source behind rendered HTML. */
@Serializable
data class StatusSource(
    val id: String,
    val text: String = "",
    val spoiler_text: String = "",
)
