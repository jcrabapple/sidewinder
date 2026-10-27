package net.snakepit.sidewinder.ui.compose

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.snakepit.sidewinder.core.network.Api

/** One selectable post language: the ISO 639-1 code sent to the server. */
internal data class LanguageOption(val code: String, val label: String)

/**
 * Curated ISO 639-1 list. Server preferences or a post language outside
 * this list still display (as their raw code), so the control never lies
 * about what will be submitted.
 */
internal val LANGUAGE_OPTIONS = listOf(
    LanguageOption("en", "English"),
    LanguageOption("ar", "Arabic"),
    LanguageOption("zh", "Chinese"),
    LanguageOption("cs", "Czech"),
    LanguageOption("da", "Danish"),
    LanguageOption("nl", "Dutch"),
    LanguageOption("fi", "Finnish"),
    LanguageOption("fr", "French"),
    LanguageOption("de", "German"),
    LanguageOption("el", "Greek"),
    LanguageOption("he", "Hebrew"),
    LanguageOption("hi", "Hindi"),
    LanguageOption("hu", "Hungarian"),
    LanguageOption("id", "Indonesian"),
    LanguageOption("it", "Italian"),
    LanguageOption("ja", "Japanese"),
    LanguageOption("ko", "Korean"),
    LanguageOption("no", "Norwegian"),
    LanguageOption("fa", "Persian"),
    LanguageOption("pl", "Polish"),
    LanguageOption("pt", "Portuguese"),
    LanguageOption("ro", "Romanian"),
    LanguageOption("ru", "Russian"),
    LanguageOption("es", "Spanish"),
    LanguageOption("sv", "Swedish"),
    LanguageOption("th", "Thai"),
    LanguageOption("tr", "Turkish"),
    LanguageOption("uk", "Ukrainian"),
    LanguageOption("vi", "Vietnamese"),
)

/** Human label for a language code; unknown codes stay visible as-is. */
internal fun languageLabel(code: String): String =
    LANGUAGE_OPTIONS.firstOrNull { it.code == code }?.label ?: code

/**
 * The server's posting default from `GET /api/v1/preferences`, or null
 * when the key is absent/not a string. Mastodon-compatible servers use
 * `posting:default:language`; the dotted spelling is accepted too.
 */
internal fun defaultPostLanguage(preferences: JsonObject): String? {
    val value = preferences["posting:default:language"] ?: preferences["posting:default.language"]
    val code = (value as? JsonPrimitive)?.content?.trim().orEmpty()
    return code.takeIf { it.isNotBlank() }
}

/**
 * Reads the posting-language preference. Any failure (endpoint missing,
 * network, malformed body) yields null so the caller keeps its fallback
 * instead of blocking the composer.
 */
internal suspend fun fetchDefaultPostLanguage(api: Api): String? = try {
    api.request(
        path = "/api/v1/preferences",
        serializer = JsonObject.serializer(),
    ).let(::defaultPostLanguage)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
