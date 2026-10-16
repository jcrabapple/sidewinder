package net.snakepit.sidewinder.ui.theme.palettes

import androidx.compose.ui.graphics.Color
import net.snakepit.sidewinder.ui.theme.AppTheme
import net.snakepit.sidewinder.ui.theme.ThemePalette
import net.snakepit.sidewinder.ui.theme.mixTowardBackground

// Source: https://github.com/morhetz/gruvbox (gruvbox_256palette.sh / README palette)
// All hex values copied verbatim from that palette;
// comments carry each color's own palette name.

// Contrast-driven substitutions (official colors only): the light accent is blue because aqua (427b58) tops out at 4.4 for onAccent and yellow at 3.2; light surface stays on bg0 so Yellow clears 3:1 for favourite. accentContainer: aqua 25% into bg0 (dark) / blue 20% into bg0 (light).
//
// Contrast picks for every role are guarded by ThemeContrastTest.

internal val GruvboxTheme = AppTheme(
    id = "gruvbox",
    name = "Gruvbox",
    light = ThemePalette(
        bg = Color(0xFFFBF1C7), // light "bg0"
        surface = Color(0xFFFBF1C7), // light "bg0"
        surface2 = Color(0xFFEBDBB2), // light "bg1"
        surface3 = Color(0xFFD5C4A1), // light "bg2"
        fg = Color(0xFF3C3836), // light "fg"
        fgMuted = Color(0xFF665C54), // light "fg3"
        accent = Color(0xFF076678), // light "blue"
        onAccent = Color(0xFFFBF1C7), // light "bg0"
        accentContainer = mixTowardBackground(Color(0xFF076678), Color(0xFFFBF1C7), 0.2f), // blue mixed into bg0
        onAccentContainer = Color(0xFF3C3836), // light "fg"
        secondary = Color(0xFF83A598), // light "blue"
        danger = Color(0xFF9D0006), // light "red"
        warn = Color(0xFFAF3A03), // light "orange"
        outline = Color(0xFFBDAE93), // light "bg3"
        outlineVariant = Color(0xFFD5C4A1), // light "bg2"
        favourite = Color(0xFFB57614), // light "yellow"
        bookmark = Color(0xFF8F3F71), // light "purple"
    ),
    dark = ThemePalette(
        bg = Color(0xFF282828), // dark "bg0"
        surface = Color(0xFF3C3836), // dark "bg1"
        surface2 = Color(0xFF504945), // dark "bg2"
        surface3 = Color(0xFF665C54), // dark "bg3"
        fg = Color(0xFFFBF1C7), // dark "fg"
        fgMuted = Color(0xFFBDAE93), // dark "fg3"
        accent = Color(0xFF8EC07C), // dark "aqua"
        onAccent = Color(0xFF282828), // dark "bg0"
        accentContainer = mixTowardBackground(Color(0xFF8EC07C), Color(0xFF282828), 0.25f), // aqua mixed into bg0
        onAccentContainer = Color(0xFFFBF1C7), // dark "fg"
        secondary = Color(0xFF83A598), // dark "blue"
        danger = Color(0xFFFB4934), // dark "red"
        warn = Color(0xFFFE8019), // dark "orange"
        outline = Color(0xFF665C54), // dark "bg3"
        outlineVariant = Color(0xFF504945), // dark "bg2"
        favourite = Color(0xFFFABD2F), // dark "yellow"
        bookmark = Color(0xFFD3869B), // dark "purple"
    ),
)
