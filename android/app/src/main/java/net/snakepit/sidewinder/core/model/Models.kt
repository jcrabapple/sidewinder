package net.snakepit.sidewinder.core.model

import kotlinx.serialization.Serializable
import net.snakepit.sidewinder.core.filters.FilteredStatus

/** OAuth token response. Port of TokenResponse in api/types.ts. */
@Serializable
data class TokenResponse(
    val access_token: String,
    val token_type: String = "Bearer",
    val scope: String = "",
    val created_at: Long = 0,
)

/** Account snapshot from /api/v1/accounts/verify_credentials (login-time copy). */
@Serializable
data class AccountCredentials(
    val id: String,
    val username: String,
    val acct: String,
    val display_name: String? = null,
    val avatar: String? = null,
    val avatar_static: String? = null,
)

/** Instance record from /api/v1/instance (subset the app shell needs). */
@Serializable
data class Instance(
    val uri: String,
    val title: String = "",
    val short_description: String? = null,
)

/** One signed-in account: token bound to its server base + account snapshot.
 *  Port of Session in state/auth.ts. */
data class Session(
    val id: String,
    val base: String,
    val token: String,
    val account: AccountCredentials,
    val scopes: String? = null,
)

// ---- Status payload (port of the used subset of api/types.ts) ----

@Serializable
data class Emoji(
    val shortcode: String,
    val url: String,
    val static_url: String? = null,
)

@Serializable
data class Mention(
    val id: String,
    val username: String,
    val acct: String,
    val url: String,
)

@Serializable
data class Tag(
    val name: String,
    val url: String,
)

@Serializable
data class Account(
    val id: String,
    val username: String,
    val acct: String,
    val display_name: String? = null,
    val note: String = "",
    val url: String = "",
    val avatar: String = "",
    val avatar_static: String? = null,
    val header: String? = null,
    val locked: Boolean = false,
    val bot: Boolean = false,
    val discoverable: Boolean? = null,
    val indexable: Boolean? = null,
    val created_at: String = "",
    val followers_count: Long = 0,
    val following_count: Long = 0,
    val statuses_count: Long = 0,
    val emojis: List<Emoji> = emptyList(),
    val fields: List<Field> = emptyList(),
)

@Serializable
data class Field(
    val name: String,
    val value: String,
    val verified_at: String? = null,
)

@Serializable
data class MediaAttachment(
    val id: String,
    val type: String,
    val url: String? = null,
    val preview_url: String? = null,
    val description: String? = null,
    val blurhash: String? = null,
    /** Original-server URL for remote media (the "open original" target). */
    val remote_url: String? = null,
)

/** Link preview card (status.card). Subset of the Mastodon PreviewCard. */
@Serializable
data class PreviewCard(
    val url: String,
    val title: String = "",
    val description: String = "",
    /** link, photo, video, rich */
    val type: String = "link",
    val image: String? = null,
    val provider_name: String = "",
)

@Serializable
data class PollOption(
    val title: String,
    val votes_count: Long? = null,
)

@Serializable
data class Poll(
    val id: String,
    val expires_at: String? = null,
    val expired: Boolean = false,
    val multiple: Boolean = false,
    val votes_count: Long = 0,
    val voters_count: Long? = null,
    val voted: Boolean? = null,
    val own_votes: List<Int> = emptyList(),
    val options: List<PollOption> = emptyList(),
    val emojis: List<Emoji> = emptyList(),
)

@Serializable
data class Status(
    val id: String,
    val uri: String,
    val url: String? = null,
    val created_at: String,
    val account: Account,
    /** Server-rendered HTML; MUST go through sanitize/HtmlText before display. */
    val content: String = "",
    val visibility: String = "public",
    val spoiler_text: String = "",
    val sensitive: Boolean = false,
    val media_attachments: List<MediaAttachment> = emptyList(),
    val mentions: List<Mention> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val emojis: List<Emoji> = emptyList(),
    val reblogs_count: Long = 0,
    val favourites_count: Long = 0,
    val replies_count: Long = 0,
    val favourited: Boolean = false,
    val reblogged: Boolean = false,
    val bookmarked: Boolean = false,
    val muted: Boolean = false,
    val poll: Poll? = null,
    /** The boosted status, when this row is a boost. */
    val reblog: Status? = null,
    val in_reply_to_id: String? = null,
    val in_reply_to_account_id: String? = null,
    val language: String? = null,
    /** Source markdown; present on own statuses only. */
    val text: String? = null,
    /** Link preview for the first link in the post, when the server built one. */
    val card: PreviewCard? = null,
    /** Set once a status has been edited (ISO timestamp of the latest edit). */
    val edited_at: String? = null,
    /** Source format of own statuses (text/plain or text/markdown) on GTS. */
    val content_type: String? = null,
    /** Server-side filter verdicts (v2 filter + matches), when the server populates them. */
    val filtered: List<FilteredStatus> = emptyList(),
)

@Serializable
data class Notification(
    val id: String,
    /** mention, status, reblog, follow, follow_request, favourite, poll, update, admin.sign_up, admin.report */
    val type: String,
    val created_at: String,
    val account: Account,
    /** Present for mention/status/reblog/favourite/poll/update. */
    val status: Status? = null,
)

@Serializable
data class StatusContext(
    val ancestors: List<Status> = emptyList(),
    val descendants: List<Status> = emptyList(),
)

/** One revision of an edited status (port of the statusEdit swagger model). */
@Serializable
data class StatusEdit(
    val content: String = "",
    val spoiler_text: String = "",
    val sensitive: Boolean = false,
    val created_at: String = "",
    val media_attachments: List<MediaAttachment> = emptyList(),
    val emojis: List<Emoji> = emptyList(),
    val account: Account? = null,
)

/** One direct-message conversation (port of the conversation swagger model). */
@Serializable
data class Conversation(
    val id: String,
    val accounts: List<Account> = emptyList(),
    val last_status: Status? = null,
    val unread: Boolean = false,
)

@Serializable
data class Relationship(
    val id: String,
    val following: Boolean = false,
    val followed_by: Boolean = false,
    val requested: Boolean = false,
    val blocking: Boolean = false,
    val blocked_by: Boolean = false,
    val muting: Boolean = false,
    val showing_reblogs: Boolean = true,
    val notifying: Boolean = false,
    val note: String = "",
)
