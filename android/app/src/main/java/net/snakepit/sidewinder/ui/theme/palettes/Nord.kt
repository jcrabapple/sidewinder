package net.snakepit.sidewinder.ui.theme.palettes

import androidx.compose.ui.graphics.Color
import net.snakepit.sidewinder.ui.theme.AppTheme
import net.snakepit.sidewinder.ui.theme.ThemePalette
import net.snakepit.sidewinder.ui.theme.mixTowardBackground

// Source: https://www.nordtheme.com/docs/colors-and-palettes
// All hex values copied verbatim from that palette;
// comments carry each color's own palette name.

// Contrast-driven substitutions (official colors only): the light accent is nord10 (Nord identity); it carries only 3.5:1 white text, a documented onAccent exception in ThemeContrastTest; favourite is nord10 and bookmark nord11 (nord13/nord15 sit near 1.3-2.3 on the grounds), so bookmark shares nord11 with danger. Dark surface stays on nord0 so nord11 red clears 3:1. accentContainer: nord8 25% into nord0 (dark) / nord3 20% into nord6 (light).
//
// Contrast picks for every role are guarded by ThemeContrastTest.

internal val NordTheme = AppTheme(
    id = "nord",
    name = "Nord",
    light = ThemePalette(
        bg = Color(0xFFECEFF4), // "nord6"
        surface = Color(0xFFE5E9F0), // "nord6"
        surface2 = Color(0xFFD8DEE9), // "nord5"
        surface3 = Color(0xFFD8DEE9), // "nord4"
        fg = Color(0xFF2E3440), // "nord0"
        fgMuted = Color(0xFF434C5E), // "nord2"
        accent = Color(0xFF5E81AC), // "nord10" (onAccent 3.5:1, documented exception)
        onAccent = Color(0xFFECEFF4), // "nord6"
        accentContainer = mixTowardBackground(Color(0xFF5E81AC), Color(0xFFECEFF4), 0.2f), // nord10 mixed into nord6
        onAccentContainer = Color(0xFF2E3440), // "nord0"
        secondary = Color(0xFF81A1C1), // "nord9"
        danger = Color(0xFFBF616A), // "nord11"
        warn = Color(0xFFD08770), // "nord12"
        outline = Color(0xFF4C566A), // "nord3"
        outlineVariant = Color(0xFFD8DEE9), // "nord4"
        favourite = Color(0xFF5E81AC), // "nord10"
        bookmark = Color(0xFFBF616A), // "nord11"
    ),
    dark = ThemePalette(
        bg = Color(0xFF2E3440), // "nord0"
        surface = Color(0xFF2E3440), // "nord0"
        surface2 = Color(0xFF3B4252), // "nord1"
        surface3 = Color(0xFF434C5E), // "nord2"
        fg = Color(0xFFECEFF4), // "nord6"
        fgMuted = Color(0xFFD8DEE9), // "nord4"
        accent = Color(0xFF88C0D0), // "nord8"
        onAccent = Color(0xFF2E3440), // "nord0"
        accentContainer = mixTowardBackground(Color(0xFF88C0D0), Color(0xFF2E3440), 0.25f), // nord8 mixed into nord0
        onAccentContainer = Color(0xFFECEFF4), // "nord6"
        secondary = Color(0xFF81A1C1), // "nord9"
        danger = Color(0xFFBF616A), // "nord11"
        warn = Color(0xFFD08770), // "nord12"
        outline = Color(0xFF4C566A), // "nord3"
        outlineVariant = Color(0xFF3B4252), // "nord1"
        favourite = Color(0xFFEBCB8B), // "nord13"
        bookmark = Color(0xFFB48EAD), // "nord15"
    ),
)
