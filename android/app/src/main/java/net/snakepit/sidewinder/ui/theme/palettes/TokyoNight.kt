package net.snakepit.sidewinder.ui.theme.palettes

import androidx.compose.ui.graphics.Color
import net.snakepit.sidewinder.ui.theme.AppTheme
import net.snakepit.sidewinder.ui.theme.ThemePalette
import net.snakepit.sidewinder.ui.theme.mixTowardBackground

// Source: https://github.com/folke/tokyonight.nvim (lua/tokyonight/colors/night.lua; Day values from extras/lua/tokyonight_day.lua)
// All hex values copied verbatim from that palette;
// comments carry each color's own palette name.

// Contrast-driven substitutions (official colors only): Day fg keeps the official 3760bf on bg (about 4.5, the documented text exception); Day accent is blue5 because blue/blue0 cannot carry a 4.5 onAccent; Day accentContainer is the Day bg itself (no tinted mix reaches 4.5 with a dark on-container); Day fgMuted is purple, favourite stays yellow, danger is red1, bookmark magenta2.
//
// Contrast picks for every role are guarded by ThemeContrastTest.

internal val TokyoNightTheme = AppTheme(
    id = "tokyonight",
    name = "Tokyo Night",
    light = ThemePalette(
        bg = Color(0xFFE1E2E7), // Day "bg"
        surface = Color(0xFFE1E2E7), // Day "bg"
        surface2 = Color(0xFFD0D5E3), // Day "bg_dark"
        surface3 = Color(0xFFC4C8DA), // Day "bg_highlight"
        fg = Color(0xFF3760BF), // Day "fg"
        fgMuted = Color(0xFF7847BD), // Day "purple"
        accent = Color(0xFF006A83), // Day "blue5"
        onAccent = Color(0xFFE1E2E7), // Day "bg"
        accentContainer = Color(0xFFE1E2E7), // Day "bg"
        onAccentContainer = Color(0xFF006A83), // Day "blue5"
        secondary = Color(0xFF2E7DE9), // Day "blue"
        danger = Color(0xFFC64343), // Day "red1"
        warn = Color(0xFFB15C00), // Day "orange"
        outline = Color(0xFFC4C8DA), // Day "bg_highlight"
        outlineVariant = Color(0xFFD0D5E3), // Day "bg_dark"
        favourite = Color(0xFF8C6C3E), // Day "yellow"
        bookmark = Color(0xFFD20065), // Day "magenta2"
    ),
    dark = ThemePalette(
        bg = Color(0xFF1A1B26), // Night "bg"
        surface = Color(0xFF1A1B26), // Night "bg"
        surface2 = Color(0xFF292E42), // Night "bg_highlight"
        surface3 = Color(0xFF292E42), // Night "bg_highlight"
        fg = Color(0xFFC0CAF5), // Night "fg"
        fgMuted = Color(0xFFA9B1D6), // Night "fg_dark"
        accent = Color(0xFF7AA2F7), // Night "blue"
        onAccent = Color(0xFF1A1B26), // Night "bg"
        accentContainer = mixTowardBackground(Color(0xFF7AA2F7), Color(0xFF1A1B26), 0.3f), // blue mixed into Night bg
        onAccentContainer = Color(0xFFC0CAF5), // Night "fg"
        secondary = Color(0xFF9D7CD8), // Night "purple"
        danger = Color(0xFFF7768E), // Night "red"
        warn = Color(0xFFFF9E64), // Night "orange"
        outline = Color(0xFF292E42), // Night "bg_highlight"
        outlineVariant = Color(0xFF292E42), // Night "bg_highlight"
        favourite = Color(0xFFE0AF68), // Night "yellow"
        bookmark = Color(0xFFBB9AF7), // Night "magenta"
    ),
)
