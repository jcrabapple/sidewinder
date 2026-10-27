package net.snakepit.sidewinder.core.emoji

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import net.snakepit.sidewinder.core.network.Api

/**
 * One custom emoji from `GET /api/v1/custom_emojis`. Only the fields the
 * picker renders are declared; unknown keys are ignored.
 */
@Serializable
data class CustomEmoji(
    val shortcode: String,
    val url: String,
    val static_url: String? = null,
    val category: String? = null,
    val visible_in_picker: Boolean = true,
) {
    /** Image shown in the picker: the static copy when the server offers one. */
    val displayUrl: String get() = static_url?.takeIf { it.isNotBlank() } ?: url
}

/**
 * Per-session custom-emoji cache. The picker is opened repeatedly while
 * composing, so the first load for a session fetches once and later opens
 * reuse it until the user asks for a refresh. A failed load is never
 * cached.
 */
object CustomEmojiRepository {

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, List<CustomEmoji>>()

    private const val PATH = "/api/v1/custom_emojis"

    /**
     * Pickable emoji for [sessionId]. Set [refresh] to bypass and replace
     * the cache. Cancellation propagates; failures are never cached.
     */
    suspend fun list(api: Api, sessionId: String, refresh: Boolean = false): List<CustomEmoji> {
        if (!refresh) {
            mutex.withLock { cache[sessionId] }?.let { return it }
        }
        val emojis = fetchEmojis(api)
        mutex.withLock { cache[sessionId] = emojis }
        return emojis
    }

    private suspend fun fetchEmojis(api: Api): List<CustomEmoji> = api.request(
        path = PATH,
        serializer = ListSerializer(CustomEmoji.serializer()),
    ).filter { it.visible_in_picker && it.shortcode.isNotBlank() && it.url.isNotBlank() }
}

/** Display name of an emoji's category; uncategorized emoji group under "Other". */
fun CustomEmoji.categoryLabel(): String = category?.takeIf { it.isNotBlank() } ?: "Other"
