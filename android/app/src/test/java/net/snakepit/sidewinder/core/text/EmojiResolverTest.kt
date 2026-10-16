package net.snakepit.sidewinder.core.text

import net.snakepit.sidewinder.core.model.Emoji
import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiResolverTest {

    private fun emoji(shortcode: String) = Emoji(shortcode = shortcode, url = "https://x/$shortcode.png")

    @Test
    fun `plain text without emojis stays one literal segment`() {
        val result = EmojiResolver.segments("hello world", EmojiResolver.shortcodeMap(listOf(emoji("smile"))))
        assertEquals(listOf<EmojiResolver.Segment>(EmojiResolver.Segment.Text("hello world")), result)
    }

    @Test
    fun `empty text yields one empty literal segment`() {
        val result = EmojiResolver.segments("", EmojiResolver.shortcodeMap(listOf(emoji("smile"))))
        assertEquals(listOf<EmojiResolver.Segment>(EmojiResolver.Segment.Text("")), result)
    }

    @Test
    fun `known shortcode becomes an emoji segment`() {
        val result = EmojiResolver.segments("hi :smile:!", EmojiResolver.shortcodeMap(listOf(emoji("smile"))))
        assertEquals(
            listOf(
                EmojiResolver.Segment.Text("hi "),
                EmojiResolver.Segment.Emoji("smile", "https://x/smile.png"),
                EmojiResolver.Segment.Text("!"),
            ),
            result,
        )
    }

    @Test
    fun `unknown shortcode stays literal`() {
        val result = EmojiResolver.segments(":nope:", EmojiResolver.shortcodeMap(listOf(emoji("smile"))))
        assertEquals(listOf<EmojiResolver.Segment>(EmojiResolver.Segment.Text(":nope:")), result)
    }

    @Test
    fun `adjacent shortcodes both resolve`() {
        val result = EmojiResolver.segments(":a::b:", EmojiResolver.shortcodeMap(listOf(emoji("a"), emoji("b"))))
        assertEquals(
            listOf(
                EmojiResolver.Segment.Emoji("a", "https://x/a.png"),
                EmojiResolver.Segment.Emoji("b", "https://x/b.png"),
            ),
            result,
        )
    }

    @Test
    fun `underscores and digits are part of shortcodes`() {
        val result = EmojiResolver.segments(":blob_cat_2:", EmojiResolver.shortcodeMap(listOf(emoji("blob_cat_2"))))
        assertEquals(EmojiResolver.Segment.Emoji("blob_cat_2", "https://x/blob_cat_2.png"), result.single())
    }

    @Test
    fun `shortcode matching is case-sensitive`() {
        val result = EmojiResolver.segments(":Smile:", EmojiResolver.shortcodeMap(listOf(emoji("smile"))))
        assertEquals(listOf<EmojiResolver.Segment>(EmojiResolver.Segment.Text(":Smile:")), result)
    }

    @Test
    fun `trailing literal text after the last emoji is kept`() {
        val result = EmojiResolver.segments(":a: tail", EmojiResolver.shortcodeMap(listOf(emoji("a"))))
        assertEquals(
            listOf(
                EmojiResolver.Segment.Emoji("a", "https://x/a.png"),
                EmojiResolver.Segment.Text(" tail"),
            ),
            result,
        )
    }

    @Test
    fun `empty emoji map leaves everything literal`() {
        val result = EmojiResolver.segments(":a: plain", emptyMap())
        assertEquals(listOf<EmojiResolver.Segment>(EmojiResolver.Segment.Text(":a: plain")), result)
    }
}
