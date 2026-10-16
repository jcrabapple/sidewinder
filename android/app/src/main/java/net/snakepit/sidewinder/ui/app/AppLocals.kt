package net.snakepit.sidewinder.ui.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import java.net.URI
import java.net.URLDecoder
import net.snakepit.sidewinder.core.model.Mention
import net.snakepit.sidewinder.core.model.Tag
import net.snakepit.sidewinder.core.settings.AppSettings

/**
 * App-wide navigation actions, provided once by the shell (MainActivity)
 * so deep components (StatusCard, HtmlText, the media viewer) can route
 * in-app without every screen threading callbacks through its signature.
 * Null outside the signed-in shell (login screen, previews): callers then
 * fall back to the system URI handler.
 */
class AppActions(
    val openThread: (statusId: String) -> Unit,
    val openProfile: (accountId: String) -> Unit,
    val openTag: (name: String) -> Unit,
    val openCompose: (replyToId: String?) -> Unit,
    /** Resolve a remote account URL/acct on our instance, then open it; falls back to the browser. */
    val openRemoteAccount: (url: String, acct: String?) -> Unit,
    val openUrl: (url: String) -> Unit,
    /** Signed-in account id, so cards can offer own-post actions (edit). */
    val selfAccountId: String = "",
    /** Open the composer in edit mode for an own status. */
    val openEdit: (statusId: String) -> Unit = {},
    /** Open the profile editor for the signed-in account. */
    val openEditProfile: () -> Unit = {},
    /** Open the composer in quote mode for a status id. */
    val openQuote: (statusId: String) -> Unit = {},
)

val LocalAppActions = staticCompositionLocalOf<AppActions?> { null }

/** Current persisted settings, so display code honors them without plumbing. */
val LocalAppSettings = compositionLocalOf { AppSettings() }

/**
 * Decide what a tapped content link means, Tusky/Moshidon style:
 * hashtags open the instance's own tag timeline (posts federated to us),
 * mentions open the profile in-app, everything else opens a Custom Tab.
 *
 * The sanitizer strips `class` attributes, so the authoritative signals are
 * the status's own [tags]/[mentions] lists (matched by URL), then the link
 * text (`#tag`, `@user`) for HTML outside a status (profile notes, fields).
 */
fun routeContentLink(
    url: String,
    text: String,
    tags: List<Tag>,
    mentions: List<Mention>,
    actions: AppActions,
) {
    val trimmedText = text.trim()

    // 1. Hashtags first: servers may mark a hashtag link with BOTH the
    //    mention and hashtag classes, so a mention-first check misroutes.
    val tagMatch = tags.firstOrNull { it.url.equals(url, ignoreCase = true) }
    val tagName = tagMatch?.name
        ?: trimmedText.takeIf { it.startsWith("#") && it.length > 1 }?.removePrefix("#")
        ?: tagNameFromPath(url)?.takeIf { trimmedText.startsWith("#") }
    if (tagName != null) {
        actions.openTag(tagName)
        return
    }

    // 2. Mentions: the mention id is already a local account id.
    val mentionMatch = mentions.firstOrNull { it.url.equals(url, ignoreCase = true) }
    if (mentionMatch != null) {
        actions.openProfile(mentionMatch.id)
        return
    }
    if (trimmedText.startsWith("@") && trimmedText.length > 1) {
        actions.openRemoteAccount(url, trimmedText.removePrefix("@"))
        return
    }

    // 3. Everything else is a web link.
    actions.openUrl(url)
}

/** `/tags/<name>` or `/tag/<name>` path tail, the common hashtag URL shapes. */
internal fun tagNameFromPath(url: String): String? {
    // java.net.URI (not android.net.Uri) keeps this unit-testable on the JVM.
    val path = runCatching { URI(url).rawPath }.getOrNull() ?: return null
    val match = Regex("/tags?/([^/]+)/?$").find(path) ?: return null
    val decoded = runCatching { URLDecoder.decode(match.groupValues[1], "UTF-8") }.getOrNull()
    return decoded?.takeIf { it.isNotBlank() }
}

/** Open a web URL in a Custom Tab, falling back to any browser. */
fun openInBrowser(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No browser at all; nothing sensible left to do.
        }
    }
}

/** System share sheet for a plain-text payload (post links, media URLs). */
fun shareText(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
