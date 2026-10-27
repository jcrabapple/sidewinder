package net.snakepit.sidewinder.core.profile

import kotlinx.coroutines.runBlocking
import net.snakepit.sidewinder.core.network.Api
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepositoryApiTest {
    @Test
    fun blockAndUnblockUsePostWithEmptyBodyAndDecodeRelationship() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"a1","blocking":true}"""))
        server.enqueue(MockResponse().setBody("""{"id":"a1","blocking":false}"""))
        server.start()

        try {
            val api = Api(server.url("/").toString().trimEnd('/'), "test")
            val blocked = ProfileRepository.block(api, "a1")
            val unblocked = ProfileRepository.unblock(api, "a1")

            assertTrue(blocked.blocking)
            assertFalse(unblocked.blocking)

            val blockRequest = server.takeRequest()
            assertEquals("POST", blockRequest.method)
            assertEquals("/api/v1/accounts/a1/block", blockRequest.path)
            assertEquals("", blockRequest.body.readUtf8())
            assertEquals("Bearer test", blockRequest.getHeader("Authorization"))

            val unblockRequest = server.takeRequest()
            assertEquals("POST", unblockRequest.method)
            assertEquals("/api/v1/accounts/a1/unblock", unblockRequest.path)
            assertEquals("", unblockRequest.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }
}
