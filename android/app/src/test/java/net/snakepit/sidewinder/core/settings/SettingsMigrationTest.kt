package net.snakepit.sidewinder.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Migration of the old ThemePref meaning (MODE + AMOLED switch) to
 * mode + pureBlack: stored AMOLED decodes to DARK with pure black on;
 * every other stored value keeps decoding as the mode.
 */
class SettingsMigrationTest {

    @Test
    fun `legacy AMOLED migrates to dark mode with pure black on`() {
        val (mode, pureBlack) = migrateTheme("AMOLED", false)
        assertEquals(ThemePref.DARK, mode)
        assertTrue(pureBlack)
    }

    @Test
    fun `legacy AMOLED with pureBlack already set stays migrated`() {
        val (mode, pureBlack) = migrateTheme("AMOLED", true)
        assertEquals(ThemePref.DARK, mode)
        assertTrue(pureBlack)
    }

    @Test
    fun `mode values keep decoding without pure black`() {
        for (stored in listOf("SYSTEM", "DARK", "LIGHT")) {
            val (mode, pureBlack) = migrateTheme(stored, false)
            assertEquals(ThemePref.valueOf(stored), mode)
            assertFalse(pureBlack)
        }
    }

    @Test
    fun `unknown stored value falls back to system mode`() {
        val (mode, pureBlack) = migrateTheme("GARBAGE", true)
        assertEquals(ThemePref.SYSTEM, mode)
        assertTrue(pureBlack)
    }
}
