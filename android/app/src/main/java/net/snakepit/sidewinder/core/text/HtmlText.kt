package net.snakepit.sidewinder.core.text

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import java.net.URI
import net.snakepit.sidewinder.core.model.Mention
import net.snakepit.sidewinder.core.model.Tag
import net.snakepit.sidewinder.ui.app.LocalAppActions
import net.snakepit.sidewinder.ui.app.routeContentLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

/**
 * Sanitizer and renderer for server-provided status HTML.
 *
 * Port of web `src/util/sanitize.ts` (DOMPurify) to Jsoup: the input is
 * cleaned with a [Safelist] allowlist (a, p, br, span, b/strong, i/em,
 * u, s, del, code, pre, blockquote, ul/ol/li; `href` the only attribute,
 * http/https/mailto protocols only, rel="nofollow" enforced), then the
 * cleaned DOM is walked to produce an AnnotatedString. Links become
 * [LinkAnnotation.Url] ranges so Compose opens them via the local
 * UriHandler -- the rel="nofollow noopener" equivalent, since the app
 * never renders raw markup. Bold/italic/underline/strikethrough/code
 * map to SpanStyle; block elements become paragraph breaks; br becomes
 * a newline; headings and anything else outside the allowlist are
 * stripped (their text content kept). No raw HTML is ever rendered.
 */
private val SAFELIST: Safelist = Safelist()
    .addTags(
        "a", "p", "br", "span",
        "b", "strong", "i", "em", "u", "s", "del",
        "code", "pre", "blockquote", "ul", "ol", "li",
    )
    .addAttributes("a", "href")
    .addProtocols("a", "href", "http", "https", "mailto")
    .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer")

private val FORMAT_TAGS: Map<String, SpanStyle> = mapOf(
    "b" to SpanStyle(fontWeight = FontWeight.Bold),
    "strong" to SpanStyle(fontWeight = FontWeight.Bold),
    "i" to SpanStyle(fontStyle = FontStyle.Italic),
    "em" to SpanStyle(fontStyle = FontStyle.Italic),
    "u" to SpanStyle(textDecoration = TextDecoration.Underline),
    "s" to SpanStyle(textDecoration = TextDecoration.LineThrough),
    "del" to SpanStyle(textDecoration = TextDecoration.LineThrough),
    "code" to SpanStyle(fontFamily = FontFamily.Monospace),
    "pre" to SpanStyle(fontFamily = FontFamily.Monospace),
    "small" to SpanStyle(fontSize = 12.sp),
    "span" to SpanStyle(),
    "blockquote" to SpanStyle(),
)

/** Block-level tags that force a paragraph break around their content. */
private val BLOCK_TAGS = setOf("p", "blockquote", "ul", "ol", "li", "pre")

/** Matches an allowed absolute URL: Kotlin's [Regex.matches] requires the
 *  WHOLE string to match, so this must cover the full URL, not just the scheme. */
private const val ALLOWED_HREF_SCHEMES = "^(https?|mailto):\\S.*"

/**
 * Converts status HTML to an [AnnotatedString] with every disallowed
 * tag, attribute, and script/style body removed. [linkStyle] is applied
 * to link ranges so they read as tappable. Text is the only output; the
 * caller renders it with a plain `Text`.
 */
fun sanitizeHtmlToAnnotatedString(
    html: String,
    baseUrl: String,
    linkStyle: SpanStyle,
    /** When set, link taps call this (url, link text) instead of the system URI handler. */
    onLink: ((url: String, text: String) -> Unit)? = null,
): AnnotatedString {
    if (html.isBlank()) return AnnotatedString("")

    // Clean first (Jsoup validates href protocols against the Safelist
    // and strips everything else), then walk the surviving DOM.
    val cleanedHtml = Jsoup.clean(html, baseUrl, SAFELIST)
    val root = Jsoup.parseBodyFragment(cleanedHtml, baseUrl).body()

    val text = StringBuilder()

    data class Span(val start: Int, val end: Int, val style: SpanStyle, val url: String?, val linkText: String = "")

    val spans = ArrayList<Span>()

    fun breakParagraph() {
        if (text.isNotEmpty() && !text.endsWith("\n")) text.append('\n')
    }

    fun appendText(decoded: String, style: SpanStyle, url: String?, linkText: String) {
        if (decoded.isEmpty()) return
        val start = text.length
        text.append(decoded)
        val end = text.length
        if (url != null) {
            spans.add(Span(start, end, style, url, linkText))
        } else if (style != SpanStyle()) {
            spans.add(Span(start, end, style, null))
        }
    }

    fun walk(node: org.jsoup.nodes.Node, style: SpanStyle, url: String?, linkText: String) {
        when (node) {
            is TextNode -> appendText(node.getWholeText(), style, url, linkText)
            is Element -> {
                val name = node.tagName().lowercase()
                when {
                    name == "br" -> {
                        if (text.isNotEmpty() && !text.endsWith("\n")) text.append('\n')
                    }
                    name == "a" -> {
                        val href = node.attr("href").trim()
                        val abs = node.absUrl("href").ifEmpty { resolveHref(href, baseUrl) ?: "" }
                        val link = if (abs.matches(Regex(ALLOWED_HREF_SCHEMES))) abs else null
                        // Whole anchor text ("#tag", "@user@host") drives in-app routing.
                        val anchorText = node.text()
                        for (child in node.childNodes()) walk(child, style, link, anchorText)
                    }
                    name in FORMAT_TAGS -> {
                        val inner = style.merge(FORMAT_TAGS.getValue(name))
                        for (child in node.childNodes()) walk(child, inner, url, linkText)
                    }
                    name in BLOCK_TAGS -> {
                        breakParagraph()
                        for (child in node.childNodes()) walk(child, style, url, linkText)
                        breakParagraph()
                    }
                    else -> {
                        // Tag stripped by the Safelist (e.g. headings):
                        // keep its text content, drop the semantics.
                        for (child in node.childNodes()) walk(child, style, url, linkText)
                    }
                }
            }
        }
    }

    for (child in root.childNodes()) walk(child, SpanStyle(), null, "")

    return buildAnnotatedString {
        append(text.toString())
        for (span in spans) {
            if (span.end <= span.start) continue
            val url = span.url
            if (url != null) {
                val styles = TextLinkStyles(style = span.style.merge(linkStyle))
                val annotation = if (onLink != null) {
                    val linkText = span.linkText
                    LinkAnnotation.Url(url, styles) { onLink(url, linkText) }
                } else {
                    LinkAnnotation.Url(url, styles)
                }
                addLink(annotation, span.start, span.end)
            } else {
                addStyle(span.style, span.start, span.end)
            }
        }
    }
}

/** True when sanitized HTML would render nothing visible (port of `htmlIsEmpty`). */
fun htmlIsEmpty(html: String): Boolean {
    if (html.isBlank()) return true
    val body = Jsoup.parseBodyFragment(html).body()
    if (body.select("img, br, hr, ul, ol, pre, blockquote").isNotEmpty()) return false
    return body.text().isBlank()
}

/** Resolves [href] against [baseUrl]; returns null when it cannot become an absolute URI. */
private fun resolveHref(href: String, baseUrl: String): String? = try {
    if (href.isEmpty()) {
        null
    } else if (baseUrl.isEmpty()) {
        href
    } else {
        URI(baseUrl).resolve(href).toString()
    }
} catch (_: Exception) {
    null
}

/**
 * Renders sanitized status HTML as tappable text. Inside the signed-in
 * shell, link taps route in-app via [routeContentLink] (hashtags to the
 * instance tag timeline, mentions to profiles, other links to a Custom
 * Tab); pass the status's [tags]/[mentions] so routing can match by URL.
 * Outside the shell, links fall back to the system URI handler.
 */
@Composable
fun HtmlText(
    html: String,
    baseUrl: String,
    modifier: Modifier = Modifier,
    tags: List<Tag> = emptyList(),
    mentions: List<Mention> = emptyList(),
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
    val actions = LocalAppActions.current
    // The annotated string is cached per html; the tap handler reads the
    // latest tags/mentions/actions through updated state, not a stale capture.
    val latestTags by rememberUpdatedState(tags)
    val latestMentions by rememberUpdatedState(mentions)
    val latestActions by rememberUpdatedState(actions)
    val hasActions = actions != null
    val text = remember(html, baseUrl, linkStyle, hasActions) {
        val onLink: ((String, String) -> Unit)? = if (hasActions) {
            { url, linkText ->
                latestActions?.let { routeContentLink(url, linkText, latestTags, latestMentions, it) }
            }
        } else {
            null
        }
        sanitizeHtmlToAnnotatedString(html, baseUrl, linkStyle, onLink)
    }
    Text(text = text, modifier = modifier, maxLines = maxLines, overflow = overflow)
}
