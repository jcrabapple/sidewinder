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
// ("viper in the grass", hue-155) — keep palettes in sync when
// web tokens change. Converted values live in ui/theme/Color.kt.

/**
 * Theme ported from the web client's tokens ("viper in the grass"):
 * hue-155 green accent on warm-neutral grounds, dark first-class.
 * Bundled color themes live in ui/theme/palettes/ and resolve through
 * [AppThemes.byId]; the Sidewinder palette is built from Color.kt.
 */

/** Per-action accent colors from the web tokens (fav = hue 90, bookmark = hue 290). */
data class ActionColors(val favourite: androidx.compose.ui.graphics.Color, val bookmark: androidx.compose.ui.graphics.Color)

val LocalActionColors = staticCompositionLocalOf {
    ActionColors(DarkFav, DarkBookmark)
}

/** True when the user asked for toned-down animations. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Accent pair for dynamic-color schemes: derived from the scheme itself. */
private fun actionColorsFor(scheme: ColorScheme) = ActionColors(scheme.primary, scheme.tertiary)

@Composable
fun SidewinderTheme(
    /** Bundled theme id; unknown ids fall back to Sidewinder. */
    themeId: String = "sidewinder",
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Material You wallpaper colors on Android 12+; false keeps the theme palette. */
    dynamicColor: Boolean = false,
    /** Pure-black grounds in dark mode (OLED). */
    pureBlack: Boolean = false,
    /** Tone down animations; exposed to screens via [LocalReduceMotion]. */
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamicAvailable = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val theme = AppThemes.byId(themeId)
    val scheme = when {
        dynamicAvailable && darkTheme -> dynamicDarkColorScheme(context)
        dynamicAvailable -> dynamicLightColorScheme(context)
        else -> theme.palette(darkTheme).toColorScheme(darkTheme, pureBlack)
    }
    val actions = if (dynamicAvailable) actionColorsFor(scheme) else {
        val palette = theme.palette(darkTheme)
        ActionColors(palette.favourite, palette.bookmark)
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

/** Picks the light or dark variant of an [AppTheme]. */
private fun AppTheme.palette(darkMode: Boolean): ThemePalette =
    if (darkMode) dark else light
