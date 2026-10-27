package net.snakepit.sidewinder.ui.compose

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.snakepit.sidewinder.core.emoji.CustomEmoji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure helpers behind language selection, visibility labelling, and the emoji picker. */
class ComposeAssistTest {

    // ---- Language ---------------------------------------------------------

    @Test
    fun `the server posting default is read with either key spelling`() {
        val colon = Json.parseToJsonElement("""{"posting:default:language":"de"}""") as JsonObject
        val dot = Json.parseToJsonElement("""{"posting:default.language":"fr"}""") as JsonObject
        assertEquals("de", defaultPostLanguage(colon))
        assertEquals("fr", defaultPostLanguage(dot))
    }

    @Test
    fun `a missing or blank posting default is null`() {
        assertNull(defaultPostLanguage(JsonObject(emptyMap())))
        val blank = Json.parseToJsonElement("""{"posting:default:language":"  "}""") as JsonObject
        assertNull(defaultPostLanguage(blank))
    }

    @Test
    fun `unknown language codes stay visible`() {
        assertEquals("English", languageLabel("en"))
        assertEquals("cy", languageLabel("cy"))
    }

    // ---- Visibility -------------------------------------------------------

    @Test
    fun `a known visibility keeps its label`() {
        assertEquals("Public", visibilityOption("public").label)
        assertEquals("Followers only", visibilityOption("private").label)
    }

    @Test
    fun `an unknown server visibility shows its actual value`() {
        val option = visibilityOption("mutuals_only")
        assertEquals("mutuals_only", option.label)
        assertEquals("mutuals_only", option.value)
        assertEquals("Set by your server", option.description)
    }

    @Test
    fun `a blank visibility falls back to a neutral label`() {
        assertEquals("Server visibility", visibilityOption("").label)
    }

    // ---- Emoji picker grouping --------------------------------------------

    private fun emoji(shortcode: String, category: String? = null) = CustomEmoji(
        shortcode = shortcode,
        url = "https://example.test/$shortcode.png",
        category = category,
    )

    @Test
    fun `emoji group under their categories with uncategorized last`() {
        val groups = emojiGroups(
            listOf(
                emoji("blob", "Blobs"),
                emoji("party"),
                emoji("blobcat", "Blobs"),
                emoji("alarm", "Alarms"),
            ),
        )
        assertEquals(listOf("Alarms", "Blobs", "Other"), groups.map { it.first })
        assertEquals(listOf("blob", "blobcat"), groups.first { it.first == "Blobs" }.second.map { it.shortcode })
        assertEquals(listOf("party"), groups.first { it.first == "Other" }.second.map { it.shortcode })
    }

    @Test
    fun `no emoji yields no groups`() {
        assertEquals(emptyList<Pair<String, List<CustomEmoji>>>(), emojiGroups(emptyList()))
    }
}
