package net.snakepit.sidewinder.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import net.snakepit.sidewinder.ui.theme.palettes.CatppuccinTheme
import net.snakepit.sidewinder.ui.theme.palettes.DraculaTheme
import net.snakepit.sidewinder.ui.theme.palettes.EverforestTheme
import net.snakepit.sidewinder.ui.theme.palettes.GruvboxTheme
import net.snakepit.sidewinder.ui.theme.palettes.KanagawaTheme
import net.snakepit.sidewinder.ui.theme.palettes.NordTheme
import net.snakepit.sidewinder.ui.theme.palettes.RosePineTheme
import net.snakepit.sidewinder.ui.theme.palettes.SidewinderThemeDef
import net.snakepit.sidewinder.ui.theme.palettes.SolarizedTheme
import net.snakepit.sidewinder.ui.theme.palettes.TokyoNightTheme

/**
 * Bundled color themes. Every theme carries an official light and dark
 * palette (see ui/theme/palettes/ for sources); [toColorScheme] maps the
 * palette roles onto Material 3 exactly like the hand-built viper schemes
 * in Theme.kt did, so no screen code changes per theme.
 */

/**
 * One color role set for a theme variant. All values come from the
 * theme's official palette (the only blend allowed is [accentContainer],
 * an accent mixed toward the background, documented per palette).
 */
data class ThemePalette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val fg: Color,
    val fgMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val secondary: Color,
    val danger: Color,
    val warn: Color,
    val outline: Color,
    val outlineVariant: Color,
    /** Favourite-star accent (the theme's gold where contrast allows). */
    val favourite: Color,
    /** Bookmark accent (the theme's purple/magenta where contrast allows). */
    val bookmark: Color,
)

data class AppTheme(val id: String, val name: String, val light: ThemePalette, val dark: ThemePalette)

/** All bundled themes; [byId] falls back to Sidewinder for unknown ids. */
object AppThemes {
    val all: List<AppTheme> = listOf(
        SidewinderThemeDef,
        CatppuccinTheme,
        NordTheme,
        RosePineTheme,
        TokyoNightTheme,
        GruvboxTheme,
        DraculaTheme,
        SolarizedTheme,
        EverforestTheme,
        KanagawaTheme,
    )

    fun byId(id: String): AppTheme = all.firstOrNull { it.id == id } ?: SidewinderThemeDef
}

private val PureBlack = Color(0xFF000000)

/**
 * Blends [accent] into [bg] in sRGB space; [fraction] is the share of
 * accent. The one blend the palette rules allow (accentContainer only).
 */
internal fun mixTowardBackground(accent: Color, bg: Color, fraction: Float): Color {
    fun ch(x: Float, y: Float) = ((1f - fraction) * y + fraction * x).coerceIn(0f, 1f)
    return Color(red = ch(accent.red, bg.red), green = ch(accent.green, bg.green), blue = ch(accent.blue, bg.blue))
}

/**
 * Maps palette roles onto Material 3 exactly like the hand-built
 * DarkColors/LightColors in Theme.kt, so every screen keeps working.
 * [pureBlack] (dark only) pushes background, surface and the two lowest
 * containers to pure black and keeps everything else.
 */
fun ThemePalette.toColorScheme(dark: Boolean, pureBlack: Boolean): ColorScheme {
    val bg = if (dark && pureBlack) PureBlack else this.bg
    val surface = if (dark && pureBlack) PureBlack else this.surface
    return if (dark) darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        secondary = secondary,
        onSecondary = bg,
        tertiary = warn,
        onTertiary = bg,
        background = bg,
        onBackground = fg,
        surface = surface,
        onSurface = fg,
        surfaceVariant = surface2,
        onSurfaceVariant = fgMuted,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface,
        surfaceContainer = surface2,
        surfaceContainerHigh = surface2,
        surfaceContainerHighest = surface3,
        outline = outline,
        outlineVariant = outlineVariant,
        error = danger,
        onError = bg,
    ) else lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        secondary = secondary,
        onSecondary = surface,
        tertiary = warn,
        onTertiary = surface,
        background = bg,
        onBackground = fg,
        surface = surface,
        onSurface = fg,
        surfaceVariant = surface2,
        onSurfaceVariant = fgMuted,
        surfaceContainerLowest = surface,
        surfaceContainerLow = bg,
        surfaceContainer = surface2,
        surfaceContainerHigh = surface2,
        surfaceContainerHighest = surface3,
        outline = outline,
        outlineVariant = outlineVariant,
        error = danger,
        onError = surface,
    )
}

