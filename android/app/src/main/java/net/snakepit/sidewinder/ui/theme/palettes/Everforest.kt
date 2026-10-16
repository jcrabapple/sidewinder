package net.snakepit.sidewinder.ui.theme.palettes

import androidx.compose.ui.graphics.Color
import net.snakepit.sidewinder.ui.theme.AppTheme
import net.snakepit.sidewinder.ui.theme.ThemePalette
import net.snakepit.sidewinder.ui.theme.mixTowardBackground

// Source: https://github.com/sainnhe/everforest/blob/master/palette.md (background=medium)
// All hex values copied verbatim from that palette;
// comments carry each color's own palette name.

// Contrast-driven substitutions (official colors only): light fg keeps the official 5C6A72 (about 5.2, the documented text exception) and doubles as fgMuted and accent, because no light-palette hue reaches a 4.5 onAccent; favourite is blue and bookmark statusline2 (yellow 2.3 / purple 2.8 on the grounds). accentContainer: green 15% into bg0 (dark) / accent 10% into bg0 (light).
//
// Contrast picks for every role are guarded by ThemeContrastTest.

internal val EverforestTheme = AppTheme(
    id = "everforest",
    name = "Everforest",
    light = ThemePalette(
        bg = Color(0xFFFDF6E3), // light medium "bg0"
        surface = Color(0xFFFDF6E3), // light medium "bg0"
        surface2 = Color(0xFFF4F0D9), // light medium "bg1"
        surface3 = Color(0xFFEFEBD4), // light medium "bg2"
        fg = Color(0xFF5C6A72), // light medium "fg"
        fgMuted = Color(0xFF5C6A72), // light medium "fg"
        accent = Color(0xFF3A94C5), // light medium "blue" (green is 2.7:1 on bg0; onAccent 3.1:1, documented exception)
        onAccent = Color(0xFFFDF6E3), // light medium "bg0"
        accentContainer = mixTowardBackground(Color(0xFF3A94C5), Color(0xFFFDF6E3), 0.1f), // blue mixed into bg0
        onAccentContainer = Color(0xFF5C6A72), // light medium "fg"
        secondary = Color(0xFF35A77C), // light medium "aqua"
        danger = Color(0xFFF85552), // light medium "red"
        warn = Color(0xFFF57D26), // light medium "orange"
        outline = Color(0xFFBDC3AF), // light medium "bg5"
        outlineVariant = Color(0xFFE0DCC7), // light medium "bg4"
        favourite = Color(0xFFF85552), // light medium "red" (blue is now the accent)
        bookmark = Color(0xFF708089), // light medium "statusline2"
    ),
    dark = ThemePalette(
        bg = Color(0xFF2D353B), // dark medium "bg0"
        surface = Color(0xFF2D353B), // dark medium "bg0"
        surface2 = Color(0xFF343F44), // dark medium "bg1"
        surface3 = Color(0xFF3D484D), // dark medium "bg2"
        fg = Color(0xFFD3C6AA), // dark medium "fg"
        fgMuted = Color(0xFF9DA9A0), // dark medium "grey2"
        accent = Color(0xFFA7C080), // dark medium "green"
        onAccent = Color(0xFF2D353B), // dark medium "bg0"
        accentContainer = mixTowardBackground(Color(0xFFA7C080), Color(0xFF2D353B), 0.15f), // green mixed into bg0
        onAccentContainer = Color(0xFFD3C6AA), // dark medium "fg"
        secondary = Color(0xFF7FBBB3), // dark medium "blue"
        danger = Color(0xFFE67E80), // dark medium "red"
        warn = Color(0xFFE69875), // dark medium "orange"
        outline = Color(0xFF475258), // dark medium "bg3"
        outlineVariant = Color(0xFF3D484D), // dark medium "bg2"
        favourite = Color(0xFFDBBC7F), // dark medium "yellow"
        bookmark = Color(0xFFD699B6), // dark medium "purple"
    ),
)
