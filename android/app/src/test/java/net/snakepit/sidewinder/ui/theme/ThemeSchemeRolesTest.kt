package net.snakepit.sidewinder.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Material role a component can paint must come from the theme,
 * never from the M3 baseline (purple) defaults. Selected chips, the
 * bottom-nav indicator and segmented buttons use secondaryContainer,
 * which was once left unset and stayed purple on every theme.
 */
class ThemeSchemeRolesTest {

    private val roles: List<Pair<String, (ColorScheme) -> Color>> = listOf(
        "secondaryContainer" to { it.secondaryContainer },
        "onSecondaryContainer" to { it.onSecondaryContainer },
        "tertiaryContainer" to { it.tertiaryContainer },
        "onTertiaryContainer" to { it.onTertiaryContainer },
        "errorContainer" to { it.errorContainer },
        "onErrorContainer" to { it.onErrorContainer },
        "inverseSurface" to { it.inverseSurface },
        "inverseOnSurface" to { it.inverseOnSurface },
        "inversePrimary" to { it.inversePrimary },
        "surfaceTint" to { it.surfaceTint },
        "surfaceBright" to { it.surfaceBright },
        "surfaceDim" to { it.surfaceDim },
    )

    @Test
    fun `no theme variant inherits a Material baseline role`() {
        val baseDark = darkColorScheme()
        val baseLight = lightColorScheme()
        for (theme in AppThemes.all) {
            for ((dark, palette) in listOf(false to theme.light, true to theme.dark)) {
                val scheme = palette.toColorScheme(dark = dark, pureBlack = false)
                val baseline = if (dark) baseDark else baseLight
                for ((name, role) in roles) {
                    assertNotEquals("${theme.id} ${if (dark) "dark" else "light"}: $name is the M3 baseline", role(baseline), role(scheme))
                }
            }
        }
    }

    @Test
    fun `selection highlight follows the theme accent`() {
        for (theme in AppThemes.all) {
            for ((dark, palette) in listOf(false to theme.light, true to theme.dark)) {
                val scheme = palette.toColorScheme(dark = dark, pureBlack = false)
                assertTrue("${theme.id}: secondaryContainer must be the accent container", scheme.secondaryContainer == palette.accentContainer)
            }
        }
    }

    private fun lin(c: Float): Double { val x = c.toDouble(); return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun ratio(a: Color, b: Color): Double { val (h, l) = listOf(lum(a), lum(b)).sortedDescending(); return (h + 0.05) / (l + 0.05) }

    @Test
    fun `new container roles stay readable`() {
        for (theme in AppThemes.all) {
            for ((dark, palette) in listOf(false to theme.light, true to theme.dark)) {
                for (pureBlack in if (dark) listOf(false, true) else listOf(false)) {
                    val s = palette.toColorScheme(dark = dark, pureBlack = pureBlack)
                    val label = "${theme.id} ${if (dark) "dark" else "light"}${if (pureBlack) " pure black" else ""}"
                    fun check(what: String, fg: Color, bg: Color, min: Double) =
                        assertTrue("$label: $what is %.2f, needs >= $min".format(ratio(fg, bg)), ratio(fg, bg) >= min)
                    check("onSecondaryContainer on secondaryContainer", s.onSecondaryContainer, s.secondaryContainer, 4.5)
                    check("onTertiaryContainer on tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, 4.5)
                    check("onErrorContainer on errorContainer", s.onErrorContainer, s.errorContainer, 4.5)
                    check("inverseOnSurface on inverseSurface", s.inverseOnSurface, s.inverseSurface, 4.5)
                    check("inversePrimary on inverseSurface", s.inversePrimary, s.inverseSurface, 3.0)
                }
            }
        }
    }
}
