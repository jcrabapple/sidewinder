package net.snakepit.sidewinder.ui.settings

import kotlinx.serialization.json.Json
import net.snakepit.sidewinder.core.settings.AppSettings
import net.snakepit.sidewinder.core.settings.ThemePref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip of the settings export/import payload (K48/G3-9, K51):
 * every stored field must survive export -> import, fields added after
 * a file was written default sanely, and out-of-range values coerce.
 */
class SettingsBackupTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fullBackup(liveStreaming: Boolean) = SettingsBackup(
        theme = "AMOLED",
        relativeTimes = false,
        defaultVisibility = "unlisted",
        requireAltText = false,
        backgroundNotifications = true,
        hideEngagementCounts = true,
        confirmBeforeBoost = true,
        confirmBeforeFollow = true,
        hideMediaPreviews = true,
        hideNoAltPosts = true,
        collapseLongPosts = false,
        dynamicColor = true,
        textScale = 1.2f,
        reduceMotion = true,
        liveStreaming = liveStreaming,
    )

    @Test
    fun `liveStreaming false survives export and import`() {
        val exported = json.encodeToString(SettingsBackup.serializer(), fullBackup(liveStreaming = false))
        val imported = json.decodeFromString(SettingsBackup.serializer(), exported)
        assertEquals(false, imported.toSettings().liveStreaming)
        // The other fields ride along unchanged.
        val restored = imported.toSettings()
        assertEquals(ThemePref.AMOLED, restored.theme)
        assertEquals("unlisted", restored.defaultVisibility)
        assertEquals(1.2f, restored.textScale)
    }

    @Test
    fun `old export file without liveStreaming defaults it to true`() {
        // A file written before live streaming existed: the key is absent.
        val oldJson = """
            {"theme":"DARK","relativeTimes":true,"defaultVisibility":"public",
             "requireAltText":true,"backgroundNotifications":false,
             "hideEngagementCounts":false,"confirmBeforeBoost":false,
             "confirmBeforeFollow":false,"hideMediaPreviews":false,
             "hideNoAltPosts":false,"collapseLongPosts":true,
             "dynamicColor":false,"textScale":1.0,"reduceMotion":false}
        """.trimIndent()
        val restored = json.decodeFromString(SettingsBackup.serializer(), oldJson)
        assertTrue("liveStreaming must default to true for old files", restored.toSettings().liveStreaming)
    }

    @Test
    fun `invalid defaultVisibility falls back to public`() {
        val backup = fullBackup(liveStreaming = true).copy(defaultVisibility = "mutuals-only")
        assertEquals("public", backup.toSettings().defaultVisibility)
    }

    @Test
    fun `each valid visibility passes through unchanged`() {
        for (value in VALID_VISIBILITIES) {
            assertEquals(value, fullBackup(liveStreaming = true).copy(defaultVisibility = value).toSettings().defaultVisibility)
        }
    }

    @Test
    fun `from and toSettings round-trip an AppSettings`() {
        val settings = AppSettings(
            theme = ThemePref.LIGHT,
            relativeTimes = false,
            defaultVisibility = "direct",
            requireAltText = false,
            backgroundNotifications = true,
            hideEngagementCounts = true,
            confirmBeforeBoost = true,
            confirmBeforeFollow = true,
            hideMediaPreviews = true,
            hideNoAltPosts = true,
            collapseLongPosts = false,
            dynamicColor = true,
            textScale = 0.9f,
            reduceMotion = true,
            liveStreaming = false,
        )
        val restored = SettingsBackup.from(settings).toSettings()
        assertEquals(settings, restored)
    }
}
