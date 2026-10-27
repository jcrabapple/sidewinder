package net.snakepit.sidewinder.core.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StreamTest {
    private fun statusJson(id: String): String = Json.encodeToString(
        Status.serializer(),
        Status(
            id = id,
            uri = "https://gts.example/users/alice/statuses/$id",
            created_at = "2026-10-16T00:00:00.000Z",
            account = Account(id = "a1", username = "alice", acct = "alice"),
        ),
    )

    /** Wire-format frame: payload is a JSON-escaped string, as GTS sends it. */
    private fun frame(event: String, payload: String?): String = buildJsonObject {
        put("event", event)
        payload?.let { put("payload", it) }
    }.toString()

    // ---- StreamEventParser ----

    @Test
    fun `update event parses into StatusUpdate with the status id`() {
        val event = StreamEventParser.parse(
            frame("update", statusJson("s-123")),
        )
        assertEquals(StreamEvent.StatusUpdate::class, event!!::class)
        assertEquals("s-123", (event as StreamEvent.StatusUpdate).status.id)
    }

    @Test
    fun `notification event parses into NotificationArrived`() {
        val payload = Json.encodeToString(
            net.snakepit.sidewinder.core.model.Notification.serializer(),
            net.snakepit.sidewinder.core.model.Notification(
                id = "n-1",
                type = "mention",
                created_at = "2026-10-16T00:00:00.000Z",
                account = Account(id = "a2", username = "bob", acct = "bob@example"),
            ),
        )
        val event = StreamEventParser.parse(frame("notification", payload))
        assertEquals("n-1", (event as StreamEvent.NotificationArrived).notification.id)
    }

    @Test
    fun `delete event yields StatusDeleted with the payload id`() {
        assertEquals(
            StreamEvent.StatusDeleted("s-999"),
            StreamEventParser.parse(frame("delete", "s-999")),
        )
    }

    @Test
    fun `filters_changed event yields FiltersChanged without payload`() {
        assertEquals(
            StreamEvent.FiltersChanged,
            StreamEventParser.parse("""{"event":"filters_changed"}"""),
        )
    }

    @Test
    fun `unknown event type returns null`() {
        assertNull(StreamEventParser.parse(frame("announcement", "hello")))
    }

    @Test
    fun `malformed JSON returns null instead of throwing`() {
        assertNull(StreamEventParser.parse("not json at all"))
        assertNull(StreamEventParser.parse("[1,2,3]"))
    }

    @Test
    fun `update with missing payload returns null`() {
        assertNull(StreamEventParser.parse("""{"event":"update"}"""))
        assertNull(StreamEventParser.parse(frame("update", null)))
    }

    // ---- StreamBackoff ----

    @Test
    fun `backoff sequence doubles then caps at 60s`() {
        assertEquals(1000L, StreamBackoff.delayMs(0))
        assertEquals(2000L, StreamBackoff.delayMs(1))
        assertEquals(4000L, StreamBackoff.delayMs(2))
        assertEquals(8000L, StreamBackoff.delayMs(3))
        assertEquals(16000L, StreamBackoff.delayMs(4))
        assertEquals(32000L, StreamBackoff.delayMs(5))
        assertEquals(60000L, StreamBackoff.delayMs(6))
        assertEquals(60000L, StreamBackoff.delayMs(7))
        assertEquals(60000L, StreamBackoff.delayMs(100))
    }

    @Test
    fun `negative attempt treated as zero`() {
        assertEquals(1000L, StreamBackoff.delayMs(-1))
        assertEquals(1000L, StreamBackoff.delayMs(-100))
    }

    @Test
    fun `streaming clients share network resources and keep sockets alive`() {
        val first = streamingHttpClient()
        val second = streamingHttpClient()
        assertSame(first.dispatcher, second.dispatcher)
        assertSame(first.connectionPool, second.connectionPool)
        assertEquals(30_000, first.pingIntervalMillis)
        assertEquals(0, first.readTimeoutMillis)
    }
}
