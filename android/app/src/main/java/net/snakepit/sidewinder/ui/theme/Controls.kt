package net.snakepit.sidewinder.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Opaque [fg] at [alpha] over [bg] (what the eye sees for a translucent token). */
internal fun over(fg: Color, alpha: Float, bg: Color): Color = Color(
    red = fg.red * alpha + bg.red * (1 - alpha),
    green = fg.green * alpha + bg.green * (1 - alpha),
    blue = fg.blue * alpha + bg.blue * (1 - alpha),
)

/** Every color a Switch paints, resolved to opaque values so tests can measure them. */
data class SwitchSpec(
    val checkedThumb: Color, val checkedTrack: Color,
    val uncheckedThumb: Color, val uncheckedTrack: Color, val uncheckedBorder: Color,
    val disabledCheckedThumb: Color, val disabledCheckedTrack: Color,
    val disabledUncheckedThumb: Color, val disabledUncheckedTrack: Color, val disabledUncheckedBorder: Color,
)

/**
 * Switch colors for a scheme. Enabled states keep Material's roles (the
 * theme's outline now clears 3:1 on the track). Disabled states replace
 * Material's alpha tokens, whose knob sat at about 1.2:1 on its track in every
 * theme: the knob takes whichever of surface / dimmed ink stands out more
 * from a lightly tinted track.
 */
fun switchSpec(s: ColorScheme): SwitchSpec {
    val disabledCheckedTrack = over(s.primary, 0.38f, s.surface)
    val disabledUncheckedTrack = over(s.onSurface, 0.10f, s.surface)
    fun knobOn(track: Color): Color = listOf(
        s.surface,
        over(s.onSurface, 0.55f, s.surface),
        over(s.onSurface, 0.75f, s.surface),
        s.onSurface, // low-contrast palettes (Tokyo Night Day) need full ink
    ).maxBy { contrast(it, track) }
    return SwitchSpec(
        checkedThumb = s.onPrimary,
        checkedTrack = s.primary,
        uncheckedThumb = s.outline,
        uncheckedTrack = s.surfaceContainerHighest,
        uncheckedBorder = s.outline,
        disabledCheckedThumb = knobOn(disabledCheckedTrack),
        disabledCheckedTrack = disabledCheckedTrack,
        disabledUncheckedThumb = knobOn(disabledUncheckedTrack),
        disabledUncheckedTrack = disabledUncheckedTrack,
        disabledUncheckedBorder = over(s.onSurface, 0.38f, s.surface),
    )
}

/** Use for every Switch in the app: `Switch(colors = appSwitchColors(), ...)`. */
@Composable
fun appSwitchColors(): SwitchColors {
    val spec = switchSpec(MaterialTheme.colorScheme)
    return SwitchDefaults.colors(
        checkedThumbColor = spec.checkedThumb,
        checkedTrackColor = spec.checkedTrack,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = spec.uncheckedThumb,
        uncheckedTrackColor = spec.uncheckedTrack,
        uncheckedBorderColor = spec.uncheckedBorder,
        disabledCheckedThumbColor = spec.disabledCheckedThumb,
        disabledCheckedTrackColor = spec.disabledCheckedTrack,
        disabledCheckedBorderColor = Color.Transparent,
        disabledUncheckedThumbColor = spec.disabledUncheckedThumb,
        disabledUncheckedTrackColor = spec.disabledUncheckedTrack,
        disabledUncheckedBorderColor = spec.disabledUncheckedBorder,
    )
}
