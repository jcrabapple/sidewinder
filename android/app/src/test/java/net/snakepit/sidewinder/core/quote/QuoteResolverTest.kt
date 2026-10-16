package net.snakepit.sidewinder.core.quote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure negative-cache policy of [QuoteResolver]. */
class QuoteResolverTest {

    @Test
    fun `a failure inside five minutes is not retried`() {
        val failedAt = 1_000_000L
        assertFalse(QuoteResolver.shouldRetry(failedAt, failedAt + QuoteResolver.NEGATIVE_RETRY_MS - 1))
    }

    @Test
    fun `a failure five minutes old retries`() {
        val failedAt = 1_000_000L
        assertTrue(QuoteResolver.shouldRetry(failedAt, failedAt + QuoteResolver.NEGATIVE_RETRY_MS))
    }

    @Test
    fun `a much older failure retries`() {
        val failedAt = 1_000_000L
        assertTrue(QuoteResolver.shouldRetry(failedAt, failedAt + 60 * 60_000L))
    }
}
