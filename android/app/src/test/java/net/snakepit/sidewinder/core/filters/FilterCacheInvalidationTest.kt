package net.snakepit.sidewinder.core.filters

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterCacheInvalidationTest {

    @Test
    fun invalidationDropsAnInFlightPreMutationRefresh() = runBlocking {
        val token = "token-in-flight-invalidation"
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<FilterV2>>()
        val stale = listOf(
            FilterV2(
                id = "stale",
                title = "Stale filter",
                context = listOf("home"),
            ),
        )

        try {
            val refresh = async {
                FilterCache.current(token, force = false, now = 1_000L) {
                    started.complete(Unit)
                    response.await()
                }
            }
            started.await()
            FilterCache.invalidate(token)
            response.complete(stale)

            assertEquals(stale, refresh.await())
            assertNull(FilterCache.peek(token))

            val fresh = listOf(stale.single().copy(id = "fresh", title = "Fresh filter"))
            assertEquals(fresh, FilterCache.current(token, force = false, now = 2_000L) { fresh })
            assertEquals(fresh, FilterCache.peek(token))
        } finally {
            response.cancel()
            FilterCache.invalidate(token)
        }
    }
}
