package net.snakepit.sidewinder.core.text

import net.snakepit.sidewinder.core.model.Emoji

/**
 * Splits plain text into literal runs and resolvable `:shortcode:` emoji
 * references, so display names, content warnings, and poll options can
 * render custom emoji inline (Tusky/Moshidon pattern). Pure JVM logic;
 * unknown shortcodes stay literal text, matching server behavior.
 */
object EmojiResolver {

    sealed interface Segment {
        data class Text(val value: String) : Segment
        data class Emoji(val shortcode: String, val url: String) : Segment
    }

    private val SHORTCODE = Regex(":([a-zA-Z0-9_]+):")

    /** Ordered segments; shortcodes not present in [byShortcode] remain literal. */
    fun segments(text: String, byShortcode: Map<String, Emoji>): List<Segment> {
        if (text.isEmpty() || byShortcode.isEmpty()) return listOf(Segment.Text(text))
        val out = mutableListOf<Segment>()
        var last = 0
        for (match in SHORTCODE.findAll(text)) {
            val emoji = byShortcode[match.groupValues[1]] ?: continue
            if (match.range.first > last) out += Segment.Text(text.substring(last, match.range.first))
            out += Segment.Emoji(emoji.shortcode, emoji.url)
            last = match.range.last + 1
        }
        if (last < text.length) out += Segment.Text(text.substring(last))
        return out
    }

    fun shortcodeMap(emojis: List<Emoji>): Map<String, Emoji> =
        emojis.associateBy({ it.shortcode }, { it })
}
