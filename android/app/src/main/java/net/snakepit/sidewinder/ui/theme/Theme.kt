package net.snakepit.sidewinder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

// Note: Compose theme ports the web client's `src/app.css` tokens
// ("viper in the grass", hue-155 OKLCH) — keep palettes in sync when
// web tokens change. Converted values live in ui/theme/Color.kt.

/**
 * Theme ported from the web client's tokens ("viper in the grass"):
 * hue-155 green accent on warm-neutral grounds, dark first-class.
 */

/** Per-action accent colors from the web tokens (fav = hue 90, bookmark = hue 290). */
data class ActionColors(val favourite: androidx.compose.ui.graphics.Color, val bookmark: androidx.compose.ui.graphics.Color)

val LocalActionColors = staticCompositionLocalOf {
    ActionColors(DarkFav, DarkBookmark)
}

private val DarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkAccentContainer,
    onPrimaryContainer = DarkAccentStrong,
    secondary = DarkFgMuted,
    onSecondary = DarkBg,
    tertiary = DarkWarn,
    onTertiary = DarkBg,
    background = DarkBg,
    onBackground = DarkFg,
    surface = DarkSurface,
    onSurface = DarkFg,
    surfaceVariant = DarkSurface2,
    onSurfaceVariant = DarkFgMuted,
    surfaceContainerLowest = DarkBg,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface2,
    surfaceContainerHigh = DarkSurface2,
    surfaceContainerHighest = DarkSurface3,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkDanger,
    onError = DarkBg,
)

private val LightColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightOnAccent,
    primaryContainer = LightAccentContainer,
    onPrimaryContainer = LightAccentStrong,
    secondary = LightFgMuted,
    onSecondary = LightSurface,
    tertiary = LightWarn,
    onTertiary = LightSurface,
    background = LightBg,
    onBackground = LightFg,
    surface = LightSurface,
    onSurface = LightFg,
    surfaceVariant = LightSurface2,
    onSurfaceVariant = LightFgMuted,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightBg,
    surfaceContainer = LightSurface2,
    surfaceContainerHigh = LightSurface2,
    surfaceContainerHighest = LightSurface3,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightDanger,
    onError = LightSurface,
)

@Composable
fun SidewinderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = { CompositionLocalProvider(LocalActionColors provides if (darkTheme) ActionColors(DarkFav, DarkBookmark) else ActionColors(LightFav, LightBookmark), content) },
    )
}
