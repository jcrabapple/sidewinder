package net.snakepit.sidewinder.core.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the scheduled list's in-place update operations. */
class ScheduledOpsTest {

    private fun item(id: String, at: String = "2026-01-01T12:00:00Z") = ScheduledStatus(
        id = id,
        scheduled_at = at,
        params = ScheduledParams(text = "post $id"),
    )

    @Test
    fun rescheduledReplacesOnlyTheTarget() {
        val items = listOf(item("1"), item("2"))
        val updated = ScheduledOps.rescheduled(items, item("2", "2026-01-02T09:00:00Z"))
        assertEquals("2026-01-01T12:00:00Z", updated[0].scheduled_at)
        assertEquals("2026-01-02T09:00:00Z", updated[1].scheduled_at)
        assertEquals(2, updated.size)
    }

    @Test
    fun rescheduledKeepsOrderAndUnknownIdChangesNothing() {
        val items = listOf(item("1"), item("2"))
        val updated = ScheduledOps.rescheduled(items, item("9"))
        assertEquals(items, updated)
    }

    @Test
    fun canceledRemovesTheTarget() {
        val remaining = ScheduledOps.canceled(listOf(item("1"), item("2")), "1")
        assertEquals(listOf("2"), remaining.map { it.id })
    }

    @Test
    fun canceledLastItemLeavesEmptyList() {
        assertTrue(ScheduledOps.canceled(listOf(item("1")), "1").isEmpty())
    }
}
