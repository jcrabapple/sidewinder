package net.snakepit.sidewinder.core.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

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
)

/**
 * JSON-file settings store at `filesDir/sidewinder/settings.json`, same
 * atomic temp+rename pattern as SessionStore. All access is suspend;
 * reads are cheap (small file) and always hit the latest state.
 */
class SettingsStore(context: Context) {

    @Serializable
    private data class Stored(
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
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: File =
        File(context.filesDir, "sidewinder/settings.json")

    /** Serializes read-modify-write cycles in [update]. */
    private val mutex = Mutex()

    suspend fun settings(): AppSettings = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext AppSettings()
        runCatching {
            val stored = json.decodeFromString(Stored.serializer(), file.readText())
            AppSettings(
                theme = runCatching { ThemePref.valueOf(stored.theme) }.getOrDefault(ThemePref.SYSTEM),
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
            )
        }.getOrDefault(AppSettings())
    }

    /** Applies [transform] atomically and returns the new settings. */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = mutex.withLock {
        withContext(Dispatchers.IO) {
            val next = transform(settings())
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(
                json.encodeToString(
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
                    ),
                ),
            )
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
            next
        }
    }
}
