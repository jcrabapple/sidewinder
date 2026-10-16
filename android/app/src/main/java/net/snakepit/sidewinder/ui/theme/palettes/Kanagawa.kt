package net.snakepit.sidewinder.ui.theme.palettes

import androidx.compose.ui.graphics.Color
import net.snakepit.sidewinder.ui.theme.AppTheme
import net.snakepit.sidewinder.ui.theme.ThemePalette
import net.snakepit.sidewinder.ui.theme.mixTowardBackground

// Source: https://github.com/rebelot/kanagawa.nvim (lua/kanagawa/colors.lua)
// All hex values copied verbatim from that palette;
// comments carry each color's own palette name.

// Contrast-driven substitutions (official colors only): dark fgMuted is oldWhite (fujiGray is about 3.3 on the sumi inks); Lotus favourite is lotusRed (lotusYellow3 is 1.8 on the grounds) and bookmark lotusViolet4. accentContainer: crystalBlue 30% into sumiInk3 / lotusBlue4 20% into lotusWhite3.
//
// Contrast picks for every role are guarded by ThemeContrastTest.

internal val KanagawaTheme = AppTheme(
    id = "kanagawa",
    name = "Kanagawa",
    light = ThemePalette(
        bg = Color(0xFFF2ECBC), // Lotus "lotusWhite3"
        surface = Color(0xFFF2ECBC), // Lotus "lotusWhite3"
        surface2 = Color(0xFFE5DDB0), // Lotus "lotusWhite2"
        surface3 = Color(0xFFDCD5AC), // Lotus "lotusWhite1"
        fg = Color(0xFF43436C), // Lotus "lotusInk2"
        fgMuted = Color(0xFF545464), // Lotus "lotusInk1"
        accent = Color(0xFF4D699B), // Lotus "lotusBlue4"
        onAccent = Color(0xFFF2ECBC), // Lotus "lotusWhite3"
        accentContainer = mixTowardBackground(Color(0xFF4D699B), Color(0xFFF2ECBC), 0.2f), // lotusBlue4 mixed into lotusWhite3
        onAccentContainer = Color(0xFF43436C), // Lotus "lotusInk2"
        secondary = Color(0xFF9FB5C9), // Lotus "lotusBlue3"
        danger = Color(0xFFD7474B), // Lotus "lotusRed2"
        warn = Color(0xFFCC6D00), // Lotus "lotusOrange"
        outline = Color(0xFF716E61), // Lotus "lotusGray2"
        outlineVariant = Color(0xFF8A8980), // Lotus "lotusGray3"
        favourite = Color(0xFFC84053), // Lotus "lotusRed"
        bookmark = Color(0xFF624C83), // Lotus "lotusViolet4"
    ),
    dark = ThemePalette(
        bg = Color(0xFF1F1F28), // Wave "sumiInk3"
        surface = Color(0xFF1F1F28), // Wave "sumiInk3"
        surface2 = Color(0xFF2A2A37), // Wave "sumiInk4"
        surface3 = Color(0xFF363646), // Wave "sumiInk5"
        fg = Color(0xFFDCD7BA), // Wave "fujiWhite"
        fgMuted = Color(0xFFC8C093), // Wave "oldWhite"
        accent = Color(0xFF7E9CD8), // Wave "crystalBlue"
        onAccent = Color(0xFF181820), // Wave "sumiInk1"
        accentContainer = mixTowardBackground(Color(0xFF7E9CD8), Color(0xFF1F1F28), 0.3f), // crystalBlue mixed into sumiInk3
        onAccentContainer = Color(0xFFDCD7BA), // Wave "fujiWhite"
        secondary = Color(0xFF7FB4CA), // Wave "springBlue"
        danger = Color(0xFFE82424), // Wave "samuraiRed"
        warn = Color(0xFFFF9E3B), // Wave "roninYellow"
        outline = Color(0xFF363646), // Wave "sumiInk5"
        outlineVariant = Color(0xFF2A2A37), // Wave "sumiInk4"
        favourite = Color(0xFFE6C384), // Wave "carpYellow"
        bookmark = Color(0xFF957FB8), // Wave "oniViolet"
    ),
)
