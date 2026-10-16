package net.snakepit.sidewinder.ui.theme.palettes

import androidx.compose.ui.graphics.Color
import net.snakepit.sidewinder.ui.theme.AppTheme
import net.snakepit.sidewinder.ui.theme.ThemePalette
import net.snakepit.sidewinder.ui.theme.mixTowardBackground

// Source: https://rosepinetheme.com/palette/ (palette.json in rose-pine/palette)
// All hex values copied verbatim from that palette;
// comments carry each color's own palette name.

// Contrast-driven substitutions (official colors only): Main fgMuted uses Rose because Muted (#6e6a86) is about 3.2 on the grounds; Dawn fgMuted and accent use Pine (the documented Dawn fallback) because Iris cannot carry a 4.5 onAccent on Dawn; Dawn favourite is Love (Gold is about 2.1 on the grounds), sharing it with danger, and Dawn bookmark is Iris. accentContainer: Iris 30% into Main Base / Pine 20% into Dawn Base.
//
// Contrast picks for every role are guarded by ThemeContrastTest.

internal val RosePineTheme = AppTheme(
    id = "rosepine",
    name = "Rosé Pine",
    light = ThemePalette(
        bg = Color(0xFFFAF4ED), // Dawn "Base"
        surface = Color(0xFFFFFAF3), // Dawn "Surface"
        surface2 = Color(0xFFF2E9E1), // Dawn "Overlay"
        surface3 = Color(0xFFF2E9E1), // Dawn "Overlay"
        fg = Color(0xFF464261), // Dawn "Text"
        fgMuted = Color(0xFF286983), // Dawn "Pine"
        accent = Color(0xFF286983), // Dawn "Pine"
        onAccent = Color(0xFFFAF4ED), // Dawn "Base"
        accentContainer = mixTowardBackground(Color(0xFF286983), Color(0xFFFAF4ED), 0.2f), // Pine mixed into Dawn Base
        onAccentContainer = Color(0xFF464261), // Dawn "Text"
        secondary = Color(0xFF56949F), // Dawn "Foam"
        danger = Color(0xFFB4637A), // Dawn "Love"
        warn = Color(0xFFEA9D34), // Dawn "Gold"
        outline = Color(0xFFF2E9E1), // Dawn "Overlay"
        outlineVariant = Color(0xFFF2E9E1), // Dawn "Overlay"
        favourite = Color(0xFFB4637A), // Dawn "Love"
        bookmark = Color(0xFF907AA9), // Dawn "Iris"
    ),
    dark = ThemePalette(
        bg = Color(0xFF191724), // Main "Base"
        surface = Color(0xFF191724), // Main "Base"
        surface2 = Color(0xFF1F1D2E), // Main "Surface"
        surface3 = Color(0xFF26233A), // Main "Overlay"
        fg = Color(0xFFE0DEF4), // Main "Text"
        fgMuted = Color(0xFFEBBCBA), // Main "Rose"
        accent = Color(0xFFC4A7E7), // Main "Iris"
        onAccent = Color(0xFF191724), // Main "Base"
        accentContainer = mixTowardBackground(Color(0xFFC4A7E7), Color(0xFF191724), 0.3f), // Iris mixed into Main Base
        onAccentContainer = Color(0xFFE0DEF4), // Main "Text"
        secondary = Color(0xFF9CCFD8), // Main "Foam"
        danger = Color(0xFFEB6F92), // Main "Love"
        warn = Color(0xFFF6C177), // Main "Gold"
        outline = Color(0xFF26233A), // Main "Overlay"
        outlineVariant = Color(0xFF26233A), // Main "Overlay"
        favourite = Color(0xFFF6C177), // Main "Gold"
        bookmark = Color(0xFF31748F), // Main "Pine"
    ),
)
