package net.snakepit.sidewinder.core.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class ThemePref { SYSTEM, DARK, LIGHT, AMOLED }

/** App preferences. Port of web `state/settings.ts` (localStorage-backed
 *  there, JSON file here). */
data class AppSettings(
    val theme: ThemePref = ThemePref.SYSTEM,
    /** Relative timestamps ("3m"); false shows absolute dates. */
    val relativeTimes: Boolean = true,
    /** Visibility preselected for new posts: public/unlisted/private/direct. */
    val defaultVisibility: String = "public",
    /** Confirm before posting media without descriptions. */
    val requireAltText: Boolean = true,
    /** Poll for new notifications via WorkManager (~15 min, active account). */
    val backgroundNotifications: Boolean = false,
    /** Hide reply/boost/favourite counts on status cards (wellbeing). */
    val hideEngagementCounts: Boolean = false,
    /** Ask before boosting a post (wellbeing). */
    val confirmBeforeBoost: Boolean = false,
    /** Ask before following an account (wellbeing). */
    val confirmBeforeFollow: Boolean = false,
    /** Don't render media previews in timelines (wellbeing). */
    val hideMediaPreviews: Boolean = false,
    /** Hide timeline posts whose media lack descriptions (wellbeing). */
    val hideNoAltPosts: Boolean = false,
    /** Collapse long posts in timelines behind a Show more button. */
    val collapseLongPosts: Boolean = true,
    /** Material You wallpaper colors on API 31+ (false = the viper palette). */
    val dynamicColor: Boolean = false,
    /** UI text scale multiplier (0.8 .. 1.3). */
    val textScale: Float = 1.0f,
    /** Tone down animations (jump pill, reveal transitions). */
    val reduceMotion: Boolean = false,
    /** Live timeline streaming over WebSocket while the app is open. */
    val liveStreaming: Boolean = true,
    /** Home/Local/Federated feed display filters (were per-screen chips). */
    val showBoosts: Boolean = true,
    val showReplies: Boolean = true,
    val hideSelfBoosts: Boolean = false,
    /** Bundled color theme id (see AppThemes); unknown ids fall back to sidewinder. */
    val themeId: String = "sidewinder",
    /** Pure-black grounds in dark mode (legacy ThemePref.AMOLED migrated here). */
    val pureBlack: Boolean = false,
)

/**
 * Legacy ThemePref values meant MODE plus an AMOLED switch; AMOLED now
 * decodes to mode DARK with pureBlack on. Top-level so unit tests can
 * exercise the migration without an Android Context.
 */
internal fun migrateTheme(storedTheme: String, storedPureBlack: Boolean): Pair<ThemePref, Boolean> =
    if (storedTheme == "AMOLED") {
        ThemePref.DARK to true
    } else {
        val mode = runCatching { ThemePref.valueOf(storedTheme) }.getOrDefault(ThemePref.SYSTEM)
        mode to storedPureBlack
    }

private val settingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A null document represents a missing file; every present document must decode. */
internal fun decodeSettings(contents: String?): AppSettings {
    if (contents == null) return AppSettings()
    val stored = settingsJson.decodeFromString(SettingsStore.Stored.serializer(), contents)
    val (mode, pureBlack) = migrateTheme(stored.theme, stored.pureBlack)
    return AppSettings(
        theme = mode,
        pureBlack = pureBlack,
        relativeTimes = stored.relativeTimes,
        defaultVisibility = stored.defaultVisibility,
        requireAltText = stored.requireAltText,
        backgroundNotifications = stored.backgroundNotifications,
        hideEngagementCounts = stored.hideEngagementCounts,
        confirmBeforeBoost = stored.confirmBeforeBoost,
        confirmBeforeFollow = stored.confirmBeforeFollow,
        hideMediaPreviews = stored.hideMediaPreviews,
        hideNoAltPosts = stored.hideNoAltPosts,
        collapseLongPosts = stored.collapseLongPosts,
        dynamicColor = stored.dynamicColor,
        textScale = stored.textScale,
        reduceMotion = stored.reduceMotion,
        liveStreaming = stored.liveStreaming,
        themeId = stored.themeId,
        showBoosts = stored.showBoosts,
        showReplies = stored.showReplies,
        hideSelfBoosts = stored.hideSelfBoosts,
    )
}

/**
 * JSON-file settings store at `filesDir/sidewinder/settings.json`, same
 * atomic temp+rename pattern as SessionStore. All access is suspend;
 * reads are cheap (small file) and always hit the latest state.
 */
class SettingsStore(context: Context) {

    @Serializable
    internal data class Stored(
        val theme: String = "SYSTEM",
        val relativeTimes: Boolean = true,
        val defaultVisibility: String = "public",
        val requireAltText: Boolean = true,
        val backgroundNotifications: Boolean = false,
        val hideEngagementCounts: Boolean = false,
        val confirmBeforeBoost: Boolean = false,
        val confirmBeforeFollow: Boolean = false,
        val hideMediaPreviews: Boolean = false,
        val hideNoAltPosts: Boolean = false,
        val collapseLongPosts: Boolean = true,
        val dynamicColor: Boolean = false,
        val textScale: Float = 1.0f,
        val reduceMotion: Boolean = false,
        val liveStreaming: Boolean = true,
        val themeId: String = "sidewinder",
        val pureBlack: Boolean = false,
        val showBoosts: Boolean = true,
        val showReplies: Boolean = true,
        val hideSelfBoosts: Boolean = false,
    )

    private val file: File =
        File(context.filesDir, "sidewinder/settings.json")

    /** Serializes read-modify-write cycles across every store instance. */
    private companion object {
        val mutex = Mutex()
    }

    suspend fun settings(): AppSettings = withContext(Dispatchers.IO) {
        decodeSettings(if (file.exists()) file.readText() else null)
    }

    /** Applies [transform] atomically and returns the new settings. */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = mutex.withLock {
        withContext(Dispatchers.IO) {
            val next = transform(settings())
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(
                settingsJson.encodeToString(
                    Stored.serializer(),
                    Stored(
                        theme = next.theme.name,
                        relativeTimes = next.relativeTimes,
                        defaultVisibility = next.defaultVisibility,
                        requireAltText = next.requireAltText,
                        backgroundNotifications = next.backgroundNotifications,
                        hideEngagementCounts = next.hideEngagementCounts,
                        confirmBeforeBoost = next.confirmBeforeBoost,
                        confirmBeforeFollow = next.confirmBeforeFollow,
                        hideMediaPreviews = next.hideMediaPreviews,
                        hideNoAltPosts = next.hideNoAltPosts,
                        collapseLongPosts = next.collapseLongPosts,
                        dynamicColor = next.dynamicColor,
                        textScale = next.textScale,
                        reduceMotion = next.reduceMotion,
                        liveStreaming = next.liveStreaming,
                        themeId = next.themeId,
                        showBoosts = next.showBoosts,
                        showReplies = next.showReplies,
                        hideSelfBoosts = next.hideSelfBoosts,
                        pureBlack = next.pureBlack,
                    ),
                ),
            )
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                // Never delete the last good settings before a replacement
                // is known to have succeeded.
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            next
        }
    }
}
