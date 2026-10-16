package net.snakepit.sidewinder.ui.theme.palettes

import androidx.compose.ui.graphics.Color
import net.snakepit.sidewinder.ui.theme.AppTheme
import net.snakepit.sidewinder.ui.theme.ThemePalette
import net.snakepit.sidewinder.ui.theme.mixTowardBackground

// Source: https://draculatheme.com/spec (Dracula + Alucard)
// All hex values copied verbatim from that palette;
// comments carry each color's own palette name.

// Contrast-driven substitutions (official colors only): dark fgMuted uses Cyan because Comment (#6272a4) is about 2.5-3.0 on every Dracula ground; Comment serves as secondary. accentContainer: Purple 30% into Background (dark) / 20% into Alucard Background (light).
//
// Contrast picks for every role are guarded by ThemeContrastTest.

internal val DraculaTheme = AppTheme(
    id = "dracula",
    name = "Dracula",
    light = ThemePalette(
        bg = Color(0xFFFFFBEB), // Alucard "Background"
        surface = Color(0xFFFFFBEB), // Alucard "Background"
        surface2 = Color(0xFFECE9DF), // Alucard "Background light"
        surface3 = Color(0xFFDEDCCF), // Alucard "Background lighter"
        fg = Color(0xFF1F1F1F), // Alucard "Foreground"
        fgMuted = Color(0xFF6C664B), // Alucard "Comment"
        accent = Color(0xFF644AC9), // Alucard "Purple"
        onAccent = Color(0xFFFFFBEB), // Alucard "Background"
        accentContainer = mixTowardBackground(Color(0xFF644AC9), Color(0xFFFFFBEB), 0.2f), // Purple mixed into Background
        onAccentContainer = Color(0xFF1F1F1F), // Alucard "Foreground"
        secondary = Color(0xFF6C664B), // Alucard "Comment"
        danger = Color(0xFFCB3A2A), // Alucard "Red"
        warn = Color(0xFFA34D14), // Alucard "Orange"
        outline = Color(0xFFCECCC0), // Alucard "Background dark"
        outlineVariant = Color(0xFFECE9DF), // Alucard "Background light"
        favourite = Color(0xFF846E15), // Alucard "Yellow"
        bookmark = Color(0xFFA3144D), // Alucard "Pink"
    ),
    dark = ThemePalette(
        bg = Color(0xFF282A36), // Dracula "Background"
        surface = Color(0xFF343746), // Dracula "Background light"
        surface2 = Color(0xFF424450), // Dracula "Background lighter"
        surface3 = Color(0xFF424450), // Dracula "Background lighter"
        fg = Color(0xFFF8F8F2), // Dracula "Foreground"
        fgMuted = Color(0xFF8BE9FD), // Dracula "Cyan"
        accent = Color(0xFFBD93F9), // Dracula "Purple"
        onAccent = Color(0xFF282A36), // Dracula "Background"
        accentContainer = mixTowardBackground(Color(0xFFBD93F9), Color(0xFF282A36), 0.3f), // Purple mixed into Background
        onAccentContainer = Color(0xFFF8F8F2), // Dracula "Foreground"
        secondary = Color(0xFF6272A4), // Dracula "Comment"
        danger = Color(0xFFFF5555), // Dracula "Red"
        warn = Color(0xFFFFB86C), // Dracula "Orange"
        outline = Color(0xFF424450), // Dracula "Background lighter"
        outlineVariant = Color(0xFF343746), // Dracula "Background light"
        favourite = Color(0xFFF1FA8C), // Dracula "Yellow"
        bookmark = Color(0xFFFF79C6), // Dracula "Pink"
    ),
)
