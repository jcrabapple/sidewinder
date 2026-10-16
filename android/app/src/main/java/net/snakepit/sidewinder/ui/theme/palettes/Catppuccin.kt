package net.snakepit.sidewinder.ui.theme.palettes

import androidx.compose.ui.graphics.Color
import net.snakepit.sidewinder.ui.theme.AppTheme
import net.snakepit.sidewinder.ui.theme.ThemePalette
import net.snakepit.sidewinder.ui.theme.mixTowardBackground

// Source: https://github.com/catppuccin/palette (palette.json)
// All hex values copied verbatim from that palette;
// comments carry each color's own palette name.

// Contrast-driven substitutions (official colors only): Latte fgMuted uses Subtext1 (Subtext0 is 4.1 on Base, under the 4.5 floor); Latte favourite uses Teal and bookmark Maroon (Yellow 2.1 / Pink 2.3 on the grounds); Latte surface stays on Base so Text keeps its official 7:1 pair. accentContainer: Mauve 30% into Mocha Base (dark) / 20% into Latte Base (light).
//
// Contrast picks for every role are guarded by ThemeContrastTest.

internal val CatppuccinTheme = AppTheme(
    id = "catppuccin",
    name = "Catppuccin",
    light = ThemePalette(
        bg = Color(0xFFEFF1F5), // Latte "Base"
        surface = Color(0xFFEFF1F5), // Latte "Base"
        surface2 = Color(0xFFCCD0DA), // Latte "Surface0"
        surface3 = Color(0xFFBCC0CC), // Latte "Surface1"
        fg = Color(0xFF4C4F69), // Latte "Text"
        fgMuted = Color(0xFF5C5F77), // Latte "Subtext1"
        accent = Color(0xFF8839EF), // Latte "Mauve"
        onAccent = Color(0xFFEFF1F5), // Latte "Base"
        accentContainer = mixTowardBackground(Color(0xFF8839EF), Color(0xFFEFF1F5), 0.2f), // Mauve mixed into Latte Base
        onAccentContainer = Color(0xFF4C4F69), // Latte "Text"
        secondary = Color(0xFF7287FD), // Latte "Lavender"
        danger = Color(0xFFD20F39), // Latte "Red"
        warn = Color(0xFFFE640B), // Latte "Peach"
        outline = Color(0xFFACB0BE), // Latte "Surface2"
        outlineVariant = Color(0xFFBCC0CC), // Latte "Surface1"
        favourite = Color(0xFF179299), // Latte "Teal"
        bookmark = Color(0xFFE64553), // Latte "Maroon"
    ),
    dark = ThemePalette(
        bg = Color(0xFF1E1E2E), // Mocha "Base"
        surface = Color(0xFF181825), // Mocha "Mantle"
        surface2 = Color(0xFF313244), // Mocha "Surface0"
        surface3 = Color(0xFF45475A), // Mocha "Surface1"
        fg = Color(0xFFCDD6F4), // Mocha "Text"
        fgMuted = Color(0xFFA6ADC8), // Mocha "Subtext0"
        accent = Color(0xFFCBA6F7), // Mocha "Mauve"
        onAccent = Color(0xFF1E1E2E), // Mocha "Base"
        accentContainer = mixTowardBackground(Color(0xFFCBA6F7), Color(0xFF1E1E2E), 0.3f), // Mauve mixed into Mocha Base
        onAccentContainer = Color(0xFFCDD6F4), // Mocha "Text"
        secondary = Color(0xFFB4BEFE), // Mocha "Lavender"
        danger = Color(0xFFF38BA8), // Mocha "Red"
        warn = Color(0xFFFAB387), // Mocha "Peach"
        outline = Color(0xFF585B70), // Mocha "Surface2"
        outlineVariant = Color(0xFF45475A), // Mocha "Surface1"
        favourite = Color(0xFFF9E2AF), // Mocha "Yellow"
        bookmark = Color(0xFFF5C2E7), // Mocha "Pink"
    ),
)
