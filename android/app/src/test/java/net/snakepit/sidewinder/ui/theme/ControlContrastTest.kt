package net.snakepit.sidewinder.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Switches and outlined controls must be visible in every theme variant:
 * the off-switch knob once matched its track exactly (1.00:1) in several
 * themes, and Material's disabled knob sat near 1.2:1 everywhere.
 */
class ControlContrastTest {

    private fun variants() = AppThemes.all.flatMap { t ->
        listOf(
            Triple("${t.id} light", t.light, false to false),
            Triple("${t.id} dark", t.dark, true to false),
            Triple("${t.id} dark pure black", t.dark, true to true),
        )
    }

    @Test
    fun `outline clears 3 to 1 on surface and the switch track`() {
        for ((label, palette, mode) in variants()) {
            val s = palette.toColorScheme(dark = mode.first, pureBlack = mode.second)
            val onSurface = contrast(s.outline, s.surface)
            val onTrack = contrast(s.outline, s.surfaceContainerHighest)
            assertTrue("$label: outline on surface is %.2f".format(onSurface), onSurface >= 3.0)
            assertTrue("$label: outline on track is %.2f".format(onTrack), onTrack >= 3.0)
        }
    }

    @Test
    fun `every switch state shows its knob`() {
        for ((label, palette, mode) in variants()) {
            val sw = switchSpec(palette.toColorScheme(dark = mode.first, pureBlack = mode.second))
            fun check(what: String, a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color, min: Double) {
                val r = contrast(a, b)
                assertTrue("$label: $what is %.2f, needs >= $min".format(r), r >= min)
            }
            check("on knob vs track", sw.checkedThumb, sw.checkedTrack, 3.0)
            check("off knob vs track", sw.uncheckedThumb, sw.uncheckedTrack, 3.0)
            check("disabled on knob vs track", sw.disabledCheckedThumb, sw.disabledCheckedTrack, 2.0)
            check("disabled off knob vs track", sw.disabledUncheckedThumb, sw.disabledUncheckedTrack, 2.0)
        }
    }
}
