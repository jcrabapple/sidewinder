package net.snakepit.sidewinder.core.stream

/**
 * Pure exponential reconnect backoff, JVM-testable: 1s, 2s, 4s, ...,
 * capped at 60s. Negative attempts are treated as 0.
 */
object StreamBackoff {
    private const val BASE_MS = 1_000L
    private const val CAP_MS = 60_000L

    fun delayMs(attempt: Int): Long {
        val a = if (attempt < 0) 0 else attempt
        val shift = if (a > 30) 30 else a // 1L << 30 overflows toward the cap; clamp the shift
        return (BASE_MS shl shift).coerceAtMost(CAP_MS)
    }
}
