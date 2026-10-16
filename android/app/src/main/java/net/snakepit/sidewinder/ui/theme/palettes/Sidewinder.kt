package net.snakepit.sidewinder.ui.theme.palettes

import net.snakepit.sidewinder.ui.theme.AppTheme
import net.snakepit.sidewinder.ui.theme.ThemePalette
import net.snakepit.sidewinder.ui.theme.DarkAccent
import net.snakepit.sidewinder.ui.theme.DarkOnAccent
import net.snakepit.sidewinder.ui.theme.DarkAccentContainer
import net.snakepit.sidewinder.ui.theme.DarkAccentStrong
import net.snakepit.sidewinder.ui.theme.DarkBg
import net.snakepit.sidewinder.ui.theme.DarkBookmark
import net.snakepit.sidewinder.ui.theme.DarkDanger
import net.snakepit.sidewinder.ui.theme.DarkFav
import net.snakepit.sidewinder.ui.theme.DarkFg
import net.snakepit.sidewinder.ui.theme.DarkFgMuted
import net.snakepit.sidewinder.ui.theme.DarkOutline
import net.snakepit.sidewinder.ui.theme.DarkOutlineVariant
import net.snakepit.sidewinder.ui.theme.DarkSurface
import net.snakepit.sidewinder.ui.theme.DarkSurface2
import net.snakepit.sidewinder.ui.theme.DarkSurface3
import net.snakepit.sidewinder.ui.theme.DarkWarn
import net.snakepit.sidewinder.ui.theme.LightAccent
import net.snakepit.sidewinder.ui.theme.LightOnAccent
import net.snakepit.sidewinder.ui.theme.LightAccentContainer
import net.snakepit.sidewinder.ui.theme.LightAccentStrong
import net.snakepit.sidewinder.ui.theme.LightBg
import net.snakepit.sidewinder.ui.theme.LightBookmark
import net.snakepit.sidewinder.ui.theme.LightDanger
import net.snakepit.sidewinder.ui.theme.LightFav
import net.snakepit.sidewinder.ui.theme.LightFg
import net.snakepit.sidewinder.ui.theme.LightFgMuted
import net.snakepit.sidewinder.ui.theme.LightOutline
import net.snakepit.sidewinder.ui.theme.LightOutlineVariant
import net.snakepit.sidewinder.ui.theme.LightSurface
import net.snakepit.sidewinder.ui.theme.LightSurface2
import net.snakepit.sidewinder.ui.theme.LightSurface3
import net.snakepit.sidewinder.ui.theme.LightWarn

// Source: the web client's src/app.css "viper in the grass" tokens,
// converted to sRGB in ui/theme/Color.kt (kept as the single source).

/** The app's own theme, built from the existing Color.kt tokens. */
internal val SidewinderThemeDef = AppTheme(
    id = "sidewinder",
    name = "Sidewinder",
    light = ThemePalette(
        bg = LightBg,
        surface = LightSurface,
        surface2 = LightSurface2,
        surface3 = LightSurface3,
        fg = LightFg,
        fgMuted = LightFgMuted,
        accent = LightAccent,
        onAccent = LightOnAccent,
        accentContainer = LightAccentContainer,
        onAccentContainer = LightAccentStrong,
        secondary = LightFgMuted,
        danger = LightDanger,
        warn = LightWarn,
        outline = LightOutline,
        outlineVariant = LightOutlineVariant,
        favourite = LightFav,
        bookmark = LightBookmark,
    ),
    dark = ThemePalette(
        bg = DarkBg,
        surface = DarkSurface,
        surface2 = DarkSurface2,
        surface3 = DarkSurface3,
        fg = DarkFg,
        fgMuted = DarkFgMuted,
        accent = DarkAccent,
        onAccent = DarkOnAccent,
        accentContainer = DarkAccentContainer,
        onAccentContainer = DarkAccentStrong,
        secondary = DarkFgMuted,
        danger = DarkDanger,
        warn = DarkWarn,
        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,
        favourite = DarkFav,
        bookmark = DarkBookmark,
    ),
)
