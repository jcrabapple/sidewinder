package net.snakepit.sidewinder.core.stream

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide bus for streamed events that screens collect while they are
 * composed. The shell forwards socket events into [TimelineStore]-style
 * state directly; notifications additionally ride this bus so the open
 * notifications screen can prepend them live (when no screen collects,
 * the 15-minute poll worker covers the unread badge as before).
 */
object StreamEventBus {
    private val _events = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 32)

    /** Streamed events, dropped on overflow rather than blocking the socket. */
    val events: SharedFlow<StreamEvent> = _events.asSharedFlow()

    /** Publishes an event; a full buffer drops it (never blocks the caller). */
    fun publish(event: StreamEvent) {
        _events.tryEmit(event)
    }
}
