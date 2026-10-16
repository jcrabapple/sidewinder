package net.snakepit.sidewinder.ui.text

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import net.snakepit.sidewinder.core.model.Emoji
import net.snakepit.sidewinder.core.text.EmojiResolver

/**
 * Plain text with `:shortcode:` custom emoji rendered inline (payload
 * emojis only — status/account/poll emojis arrive with the post, so this
 * needs no extra endpoint). Unresolvable shortcodes stay literal.
 */
@Composable
fun EmojiText(
    text: String,
    emojis: List<Emoji>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val annotated = remember(text, emojis) {
        val byShortcode = EmojiResolver.shortcodeMap(emojis)
        buildAnnotatedString {
            for (segment in EmojiResolver.segments(text, byShortcode)) {
                when (segment) {
                    is EmojiResolver.Segment.Text -> append(segment.value)
                    is EmojiResolver.Segment.Emoji -> appendInlineContent(segment.url, ":${segment.shortcode}:")
                }
            }
        }
    }
    val boxSize: TextUnit = if (style.fontSize.isSpecified) style.fontSize * 1.25f else 18.sp
    Text(
        text = annotated,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
        inlineContent = emojis.associate { emoji ->
            emoji.url to InlineTextContent(
                Placeholder(boxSize, boxSize, PlaceholderVerticalAlign.TextCenter),
            ) {
                AsyncImage(
                    model = emoji.url,
                    contentDescription = ":${emoji.shortcode}:",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
    )
}