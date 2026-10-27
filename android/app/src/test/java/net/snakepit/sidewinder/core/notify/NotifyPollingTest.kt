package net.snakepit.sidewinder.core.notify

import kotlinx.coroutines.runBlocking
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Notification
import net.snakepit.sidewinder.core.network.NetworkError
import net.snakepit.sidewinder.core.network.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotifyPollingTest {

    private fun notification(id: String) = Notification(
        id = id,
        type = "mention",
        created_at = "2026-01-01T00:00:00Z",
        account = Account(id = "account", username = "account", acct = "account"),
    )

    private fun page(ids: List<String>, nextMaxId: String? = null) =
        Page(ids.map(::notification), nextMaxId)

    @Test
    fun burstAcrossPagesIsCompleteAndDeduplicated() = runBlocking {
        val calls = mutableListOf<String?>()
        val pages = mapOf(
            null to page(listOf("106", "105", "104"), "104"),
            "104" to page(listOf("104", "103", "102"), "102"),
            "102" to page(listOf("102", "101")),
        )

        val fresh = collectFreshNotifications("100") { maxId ->
            calls += maxId
            pages.getValue(maxId)
        }

        assertEquals(listOf(null, "104", "102"), calls)
        assertEquals(listOf("106", "105", "104", "103", "102", "101"), fresh.map { it.id })
    }

    @Test
    fun watermarkBoundaryKeepsNewRowsAndStopsOlderPages() = runBlocking {
        val calls = mutableListOf<String?>()

        val fresh = collectFreshNotifications("100") { maxId ->
            calls += maxId
            when (maxId) {
                null -> page(listOf("110", "109"), "109")
                "109" -> page(listOf("109", "108", "100", "099"), "098")
                else -> error("Watermark crossing must stop pagination")
            }
        }

        assertEquals(listOf(null, "109"), calls)
        assertEquals(listOf("110", "109", "108"), fresh.map { it.id })
    }

    @Test
    fun nextCursorAtWatermarkDoesNotFetchAnOlderPage() = runBlocking {
        var calls = 0

        val fresh = collectFreshNotifications("100") {
            calls += 1
            page(listOf("105", "104"), "100")
        }

        assertEquals(1, calls)
        assertEquals(listOf("105", "104"), fresh.map { it.id })
    }

    @Test
    fun paginationIsCappedAtTenPages() = runBlocking {
        var calls = 0

        val fresh = collectFreshNotifications("0000") { maxId ->
            calls += 1
            val id = maxId?.toInt() ?: 1000
            page(listOf(id.toString()), (id - 1).toString())
        }

        assertEquals(NOTIFY_PAGE_LIMIT, calls)
        assertEquals((1000 downTo 991).map(Int::toString), fresh.map { it.id })
    }

    @Test
    fun pageFailurePropagatesInsteadOfReturningPartialResults() {
        assertThrows(NetworkError::class.java) {
            runBlocking {
                collectFreshNotifications("100") { maxId ->
                    if (maxId == null) page(listOf("110"), "109")
                    else throw NetworkError("second page failed")
                }
            }
        }
    }
}
