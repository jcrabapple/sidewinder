package net.snakepit.sidewinder.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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

/** True when the user asked for toned-down animations. */
val LocalReduceMotion = staticCompositionLocalOf { false }

private val Black = Color(0xFF000000)

/** AMOLED variant: pure-black ground, same accents. */
private val DarkAmoledColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkAccentContainer,
    onPrimaryContainer = DarkAccentStrong,
    secondary = DarkFgMuted,
    onSecondary = DarkBg,
    tertiary = DarkWarn,
    onTertiary = DarkBg,
    background = Black,
    onBackground = DarkFg,
    surface = Black,
    onSurface = DarkFg,
    surfaceVariant = DarkSurface2,
    onSurfaceVariant = DarkFgMuted,
    surfaceContainerLowest = Black,
    surfaceContainerLow = Black,
    surfaceContainer = DarkSurface2,
    surfaceContainerHigh = DarkSurface2,
    surfaceContainerHighest = DarkSurface3,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkDanger,
    onError = Black,
)

/** Accent pair for dynamic-color schemes: derived from the scheme itself. */
private fun actionColorsFor(scheme: ColorScheme) = ActionColors(scheme.primary, scheme.tertiary)

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
    /** Material You wallpaper colors on Android 12+; false keeps the viper palette. */
    dynamicColor: Boolean = false,
    /** Pure-black dark variant (independent of [darkTheme], applied when dark). */
    amoled: Boolean = false,
    /** Tone down animations; exposed to screens via [LocalReduceMotion]. */
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamicAvailable = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        dynamicAvailable && darkTheme -> dynamicDarkColorScheme(context)
        dynamicAvailable -> dynamicLightColorScheme(context)
        darkTheme && amoled -> DarkAmoledColors
        darkTheme -> DarkColors
        else -> LightColors
    }
    val actions = if (dynamicAvailable) actionColorsFor(scheme) else {
        if (darkTheme) ActionColors(DarkFav, DarkBookmark) else ActionColors(LightFav, LightBookmark)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(
            LocalActionColors provides actions,
            LocalReduceMotion provides reduceMotion,
        ) {
            content()
        }
    }
}
