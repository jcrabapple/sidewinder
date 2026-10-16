package net.snakepit.sidewinder.core.dm

import android.net.Uri
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import net.snakepit.sidewinder.core.model.Conversation
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.Page

/**
 * Direct-message conversations (GTS 0.22.1 supports the Mastodon shape:
 * list with Link-header paging, mark-read, delete). Opening a conversation
 * is just opening its last status's thread, which the app already renders.
 */
object ConversationsRepository {

    /** One page of conversations, Link-header max_id paging. */
    suspend fun list(api: Api, token: String, maxId: String? = null): Page<Conversation> =
        api.page(
            path = "/api/v1/conversations",
            serializer = ListSerializer(Conversation.serializer()),
            maxId = maxId,
        )

    /** Marks the conversation read (clears its unread flag server-side). */
    suspend fun markRead(api: Api, token: String, id: String) {
        api.request(
            path = "/api/v1/conversations/${Uri.encode(id)}/mark_read",
            serializer = Conversation.serializer(),
            method = "POST",
        )
    }

    /** Removes the conversation from the list (messages themselves stay). */
    suspend fun delete(api: Api, token: String, id: String) {
        api.request(
            path = "/api/v1/conversations/${Uri.encode(id)}",
            serializer = JsonElement.serializer(),
            method = "DELETE",
        )
    }
}
