package net.snakepit.sidewinder.core.profile

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class VerifiedProfileTest {
    @Test
    fun `editor source uses plain source note rather than rendered account note`() {
        val profile = Json { ignoreUnknownKeys = true }.decodeFromString(
            VerifiedProfile.serializer(),
            """
                {
                  "note": "<p>Rendered HTML</p>",
                  "source": { "note": "Plain **markdown** source" },
                  "avatar_description": "A portrait"
                }
            """.trimIndent(),
        )

        assertEquals("Plain **markdown** source", profile.source.note)
        assertEquals("A portrait", profile.avatar_description)
    }
}
