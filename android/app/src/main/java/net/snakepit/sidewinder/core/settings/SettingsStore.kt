package net.snakepit.sidewinder.core.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class ThemePref { SYSTEM, DARK, LIGHT }

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
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: File =
        File(context.filesDir, "sidewinder/settings.json")

    /** Serializes read-modify-write cycles in [update]. */
    private val mutex = Mutex()

    suspend fun settings(): AppSettings {
        if (!file.exists()) return AppSettings()
        return runCatching {
            val stored = json.decodeFromString(Stored.serializer(), file.readText())
            AppSettings(
                theme = runCatching { ThemePref.valueOf(stored.theme) }.getOrDefault(ThemePref.SYSTEM),
                relativeTimes = stored.relativeTimes,
                defaultVisibility = stored.defaultVisibility,
                requireAltText = stored.requireAltText,
                backgroundNotifications = stored.backgroundNotifications,
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
