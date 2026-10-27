package net.snakepit.sidewinder.core.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.network.Api
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

internal fun streamingHttpClient(): OkHttpClient = Api.newClientBuilder()
    .pingInterval(30, TimeUnit.SECONDS) // client pings; server auto-pongs per RFC 6455
    .readTimeout(0, TimeUnit.MILLISECONDS) // server-streamed socket: no read timeout
    .build()

/**
 * OkHttp WebSocket client for the GTS `stream=user` endpoint. One
 * connection carries both home-timeline updates and notifications, so
 * the foreground path needs no second socket.
 *
 * Reconnects with [StreamBackoff] after failure or close; the attempt
 * counter resets on a successful open. [stop] fully cancels: no
 * reconnect fires afterwards, and the client can be restarted with
 * [start]. Events are forwarded on the caller's [scope]; pass a
 * Main.immediate scope to land callbacks on the UI thread.
 */
class StreamingClient(
    baseUrl: String,
    private val token: String,
    private val scope: CoroutineScope,
    private val onEvent: (StreamEvent) -> Unit,
    private val onConnectionState: (Boolean) -> Unit,
) {
    private val root: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    private val client: OkHttpClient = streamingHttpClient()

    private val lock = Any()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var stopped = false
    private var attempts = 0

    /** Connects to wss(s)://host/api/v1/streaming?access_token=...&stream=user. */
    fun start() {
        synchronized(lock) {
            stopped = false
            attempts = 0
            if (socket != null || reconnectJob != null) return // already running
        }
        connect()
    }

    /** Closes the socket and cancels any pending reconnect. */
    fun stop() {
        val wasLive: Boolean
        synchronized(lock) {
            wasLive = socket != null || reconnectJob != null
            stopped = true
            reconnectJob?.cancel()
            reconnectJob = null
            socket?.close(1000, "client stop")
            socket = null
        }
        if (wasLive) scope.launch { onConnectionState(false) }
    }

    private fun connect() {
        val url = root.newBuilder()
            .addPathSegments("api/v1/streaming")
            .addQueryParameter("access_token", token)
            .addQueryParameter("stream", "user")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val ws = client.newWebSocket(request, Listener())
        synchronized(lock) {
            if (stopped) {
                ws.cancel()
                return
            }
            socket = ws
        }
    }

    private fun onOpened() {
        synchronized(lock) {
            if (stopped) return
            attempts = 0
        }
        scope.launch { onConnectionState(true) }
    }

    /** Failure or close: schedule a reconnect with backoff unless stopped. */
    private fun onDown() {
        synchronized(lock) {
            socket = null
            if (stopped || reconnectJob != null) return
            scope.launch { onConnectionState(false) }
            val attempt = attempts++
            val pending = scope.launch {
                delay(StreamBackoff.delayMs(attempt))
                val go = synchronized(lock) {
                    if (stopped) false else {
                        reconnectJob = null
                        true
                    }
                }
                if (!go) return@launch
                connect()
            }
            reconnectJob = pending
        }
    }

    private fun onFrame(text: String) {
        val event = StreamEventParser.parse(text) ?: return
        scope.launch { onEvent(event) }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onOpened()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            onFrame(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onDown()
        }
    }

    private companion object {
        /** Cloudflare in front of GTS 401s default non-browser user agents (error 1010). */
        const val USER_AGENT = "Sidewinder-Android/0.1"
    }
}
