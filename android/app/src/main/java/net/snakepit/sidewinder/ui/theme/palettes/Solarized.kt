package net.snakepit.sidewinder.ui.theme.palettes

import androidx.compose.ui.graphics.Color
import net.snakepit.sidewinder.ui.theme.AppTheme
import net.snakepit.sidewinder.ui.theme.ThemePalette
import net.snakepit.sidewinder.ui.theme.mixTowardBackground

// Source: https://ethanschoonover.com/solarized/
// All hex values copied verbatim from that palette;
// comments carry each color's own palette name.

// Contrast-driven substitutions (official colors only): dark accent is cyan with onAccent base03 (blue tops out at 4.1 for onAccent); the light accent is base02 with onAccent base3 (no accent hue fits the required 3.0-4.5 luminance window on base3). Light fg is base02 (base00 on base3 is 4.1, below the relaxed floor); dark fg stays base1 at about 5.6, the documented text exception. accentContainer: cyan 30% into base03 (dark) / base02 20% into base3 (light).
//
// Contrast picks for every role are guarded by ThemeContrastTest.

internal val SolarizedTheme = AppTheme(
    id = "solarized",
    name = "Solarized",
    light = ThemePalette(
        bg = Color(0xFFFDF6E3), // light "base3"
        surface = Color(0xFFFDF6E3), // light "base3"
        surface2 = Color(0xFFEEE8D5), // light "base2"
        surface3 = Color(0xFFEEE8D5), // light "base2"
        fg = Color(0xFF073642), // light "base02"
        fgMuted = Color(0xFF586E75), // light "base01"
        accent = Color(0xFF268BD2), // light "blue" (onAccent 3.4:1, documented exception)
        onAccent = Color(0xFFFDF6E3), // light "base3"
        accentContainer = mixTowardBackground(Color(0xFF268BD2), Color(0xFFFDF6E3), 0.2f), // blue mixed into base3
        onAccentContainer = Color(0xFF073642), // light "base02"
        secondary = Color(0xFF268BD2), // light "blue"
        danger = Color(0xFFDC322F), // light "red"
        warn = Color(0xFFB58900), // light "yellow"
        outline = Color(0xFF839496), // light "base0"
        outlineVariant = Color(0xFF93A1A1), // light "base1"
        favourite = Color(0xFFCB4B16), // light "orange"
        bookmark = Color(0xFFD33682), // light "magenta"
    ),
    dark = ThemePalette(
        bg = Color(0xFF002B36), // dark "base03"
        surface = Color(0xFF002B36), // dark "base03"
        surface2 = Color(0xFF073642), // dark "base02"
        surface3 = Color(0xFF073642), // dark "base02"
        fg = Color(0xFF93A1A1), // dark "base1"
        fgMuted = Color(0xFF839496), // dark "base0"
        accent = Color(0xFF2AA198), // dark "cyan"
        onAccent = Color(0xFF002B36), // dark "base03"
        accentContainer = mixTowardBackground(Color(0xFF2AA198), Color(0xFF002B36), 0.3f), // cyan mixed into base03
        onAccentContainer = Color(0xFFEEE8D5), // dark "base2"
        secondary = Color(0xFF268BD2), // dark "blue"
        danger = Color(0xFFDC322F), // dark "red"
        warn = Color(0xFFCB4B16), // dark "orange"
        outline = Color(0xFF586E75), // dark "base01"
        outlineVariant = Color(0xFF073642), // dark "base02"
        favourite = Color(0xFFB58900), // dark "yellow"
        bookmark = Color(0xFFD33682), // dark "magenta"
    ),
)
