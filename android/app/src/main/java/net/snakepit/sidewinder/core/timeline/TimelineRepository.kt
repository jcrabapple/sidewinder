package net.snakepit.sidewinder.core.timeline

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import net.snakepit.sidewinder.core.model.Status
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.Page

/**
 * Read access to the home timeline. Port of the web client's
 * `/api/v1/timelines/home` usage; paging uses the Link-header max_id
 * handled inside `Api.page`.
 */
object TimelineRepository {

    /** One page of the authenticated home timeline, oldest-to-newest paging via [maxId]. */
    suspend fun home(api: Api, maxId: String? = null): Page<Status> =
        api.page(
            path = "/api/v1/timelines/home",
            serializer = ListSerializer(Status.serializer()),
            maxId = maxId,
        )

    /** Bookmark a status, returning the fresh status payload. */
    suspend fun bookmark(api: Api, id: String): Status =
        api.postForm(
            path = "/api/v1/statuses/$id/bookmark",
            form = emptyMap(),
            serializer = Status.serializer(),
        ).let { it.reblog ?: it }

    /** Remove a bookmark, returning the fresh status payload. */
    suspend fun unbookmark(api: Api, id: String): Status =
        api.postForm(
            path = "/api/v1/statuses/$id/unbookmark",
            form = emptyMap(),
            serializer = Status.serializer(),
        ).let { it.reblog ?: it }

    /** One page of the public hashtag timeline for [tag], oldest-to-newest paging via [maxId]. */
    suspend fun tag(api: Api, tag: String, maxId: String? = null): Page<Status> =
        api.page(
            path = "/api/v1/timelines/tag/${Uri.encode(tag)}",
            serializer = ListSerializer(Status.serializer()),
            maxId = maxId,
        )

    /** One page of any [TimelineSource]; every source pages via the Link-header max_id. */
    suspend fun fetch(api: Api, source: TimelineSource, maxId: String? = null): Page<Status> {
        val (path, query) = when (source) {
            TimelineSource.Home -> "/api/v1/timelines/home" to emptyMap()
            TimelineSource.Local -> "/api/v1/timelines/public" to mapOf("local" to listOf("true"))
            TimelineSource.Federated -> "/api/v1/timelines/public" to emptyMap()
            is TimelineSource.Hashtag -> "/api/v1/timelines/tag/${Uri.encode(source.name)}" to emptyMap()
            TimelineSource.Bookmarks -> "/api/v1/bookmarks" to emptyMap()
            TimelineSource.Favourites -> "/api/v1/favourites" to emptyMap()
            is TimelineSource.ListTimeline -> "/api/v1/timelines/list/${source.id}" to emptyMap()
        }
        return api.page(path = path, serializer = ListSerializer(Status.serializer()), query = query, maxId = maxId)
    }

    /** Hashtag record; [TagInfo.following] says whether the user follows it. */
    suspend fun tagInfo(api: Api, name: String): TagInfo =
        api.request("/api/v1/tags/${Uri.encode(name)}", TagInfo.serializer())

    /** Follow ([follow] = true) or unfollow a hashtag; returns the fresh record. */
    suspend fun setTagFollowed(api: Api, name: String, follow: Boolean): TagInfo =
        api.postForm(
            path = "/api/v1/tags/${Uri.encode(name)}/${if (follow) "follow" else "unfollow"}",
            form = emptyMap(),
            serializer = TagInfo.serializer(),
        )

    /**
     * Saves the home read position server-side, so returning on another
     * device (or after cache loss) can jump past what was already read.
     * GTS supports markers for home and notifications only.
     */
    suspend fun saveMarker(api: Api, lastReadId: String) {
        api.postForm(
            path = "/api/v1/markers",
            form = mapOf("home[last_read_id]" to lastReadId),
            serializer = JsonElement.serializer(),
        )
    }

    /** Last saved home read position, or null when none exists. */
    suspend fun readMarker(api: Api): String? =
        api.request(
            path = "/api/v1/markers",
            serializer = MarkerResponse.serializer(),
            query = mapOf("timeline[]" to listOf("home")),
        ).home?.last_read_id?.takeIf { it.isNotBlank() }
}

/** `/api/v1/markers` payload subset (home only). */
@Serializable
data class MarkerResponse(
    val home: MarkerData? = null,
)

@Serializable
data class MarkerData(
    val last_read_id: String = "",
)

/** `/api/v1/tags/{name}` payload subset. */
@Serializable
data class TagInfo(
    val name: String,
    val following: Boolean = false,
)

/**
 * Where a timeline's posts come from. Hashtag timelines read the signed-in
 * instance's own tag timeline: every post with that tag that has federated
 * to this server, not the tag page of the post's origin server.
 */
sealed interface TimelineSource {
    data object Home : TimelineSource
    data object Local : TimelineSource
    data object Federated : TimelineSource
    data class Hashtag(val name: String) : TimelineSource
    data object Bookmarks : TimelineSource
    data object Favourites : TimelineSource

    /** Posts from one of the user's lists; [title] only labels the source. */
    data class ListTimeline(val id: String, val title: String) : TimelineSource
}

/** Lowercase noun phrase for messages ("home timeline", "#art timeline"). */
val TimelineSource.label: String
    get() = when (this) {
        TimelineSource.Home -> "home timeline"
        TimelineSource.Local -> "local timeline"
        TimelineSource.Federated -> "federated timeline"
        is TimelineSource.Hashtag -> "#$name timeline"
        TimelineSource.Bookmarks -> "bookmarks"
        TimelineSource.Favourites -> "favourites"
        is TimelineSource.ListTimeline -> "$title list timeline"
    }
