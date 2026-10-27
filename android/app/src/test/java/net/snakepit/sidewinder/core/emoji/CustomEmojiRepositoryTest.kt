package net.snakepit.sidewinder.core.emoji

import kotlinx.coroutines.runBlocking
import net.snakepit.sidewinder.core.network.Api
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomEmojiRepositoryTest {
    @Test
    fun pickerFetchesFiltersAndCachesSuccessfulEmoji() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {"shortcode":"snake","url":"https://media.test/snake.webp","static_url":"https://media.test/snake.png","category":"Animals","visible_in_picker":true},
                  {"shortcode":"wave","url":"https://media.test/wave.png","visible_in_picker":true},
                  {"shortcode":"hidden","url":"https://media.test/hidden.png","visible_in_picker":false}
                ]
                """.trimIndent(),
            ),
        )
        server.start()

        try {
            val sessionId = "emoji-contract-${System.nanoTime()}"
            val api = Api(server.url("/").toString().trimEnd('/'), "test")
            val first = CustomEmojiRepository.list(api, sessionId)
            val cached = CustomEmojiRepository.list(api, sessionId)

            assertEquals(listOf("snake", "wave"), first.map { it.shortcode })
            assertEquals("https://media.test/snake.png", first.first().displayUrl)
            assertEquals(first, cached)
            assertEquals(1, server.requestCount)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/custom_emojis", request.path)
            assertEquals("Bearer test", request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
