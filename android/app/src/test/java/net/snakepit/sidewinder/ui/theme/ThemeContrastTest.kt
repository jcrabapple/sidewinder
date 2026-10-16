package net.snakepit.sidewinder.ui.theme

import androidx.compose.ui.graphics.Color
import net.snakepit.sidewinder.ui.theme.palettes.SidewinderThemeDef
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Contrast guard for every bundled theme x light/dark x pure black.
 * WCAG 2.x relative luminance is computed here so the check stays
 * independent of platform rendering.
 *
 * Documented 4.5 text exceptions (official text/base pair below 7,
 * per THEMES.md; the only relaxation allowed):
 * - solarized: dark fg (base1) on base03 is about 5.6.
 * - tokyonight: Day fg (3760bf) on bg is about 4.5 (the style's own pair).
 * - everforest: light fg (5C6A72) on bg0 is about 5.2.
 */
class ThemeContrastTest {

    private fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Themes whose official text pair sits between 4.5 and 7. */
    private val textExceptions = setOf("solarized", "tokyonight", "everforest")

    private fun fgFloor(themeId: String) = if (themeId in textExceptions) 4.5 else 7.0

    /**
     * Light variants whose official palette has NO accent hue reaching 4.5
     * against any palette neutral (checked exhaustively): the signature hue
     * is kept for theme identity with a 3.0 floor on its button text.
     * Nord nord10 3.5, Solarized blue 3.4, Everforest blue 3.1.
     */
    private val onAccentExceptions = setOf("nord light", "solarized light", "everforest light")

    private fun onAccentFloor(themeId: String, label: String) =
        if ("$themeId $label" in onAccentExceptions) 3.0 else 4.5

    private fun check(palette: ThemePalette, themeId: String, label: String) {
        fun assertRatio(what: String, fg: Color, bg: Color, min: Double) {
            val r = ratio(fg, bg)
            assertTrue(
                "$themeId $label: $what ${"#%06X".format(fg.hashCode() and 0xFFFFFF)} on " +
                    "#%06X".format(bg.hashCode() and 0xFFFFFF) + " is %.2f, needs >= %.1f".format(r, min),
                r >= min,
            )
        }
        assertRatio("fg on bg", palette.fg, palette.bg, fgFloor(themeId))
        assertRatio("fg on surface", palette.fg, palette.surface, fgFloor(themeId))
        assertRatio("fgMuted on surface", palette.fgMuted, palette.surface, 4.5)
        assertRatio("onAccent on accent", palette.onAccent, palette.accent, onAccentFloor(themeId, label))
        assertRatio("onAccentContainer on accentContainer", palette.onAccentContainer, palette.accentContainer, 4.5)
        assertRatio("accent on bg", palette.accent, palette.bg, 3.0)
        assertRatio("favourite on surface", palette.favourite, palette.surface, 3.0)
        assertRatio("bookmark on surface", palette.bookmark, palette.surface, 3.0)
        assertRatio("danger on surface", palette.danger, palette.surface, 3.0)
    }

    @Test
    fun `every theme variant passes the contrast floors`() {
        for (theme in AppThemes.all) {
            check(theme.light, theme.id, "light")
            check(theme.dark, theme.id, "dark")
        }
    }

    @Test
    fun `pure black variants keep every non-ground pairing`() {
        for (theme in AppThemes.all) {
            val blacked = theme.dark.copy(bg = Color(0xFF000000), surface = Color(0xFF000000))
            check(blacked, theme.id, "dark pure black")
        }
    }

    @Test
    fun `sidewinder palette matches the web-synced tokens`() {
        // The default theme must stay wired to Color.kt.
        assertTrue(AppThemes.all.first() == SidewinderThemeDef)
        assertTrue(AppThemes.byId("sidewinder") == SidewinderThemeDef)
    }

    @Test
    fun `unknown theme id falls back to sidewinder`() {
        assertTrue(AppThemes.byId("nope") == AppThemes.byId("sidewinder"))
        assertTrue(AppThemes.byId("") == SidewinderThemeDef)
    }
}
