package net.snakepit.sidewinder.core.schedule

/**
 * Pure list operations for the scheduled-posts screen's in-place
 * updates: a reschedule or cancel patches the loaded list instead of
 * triggering a full reload.
 */
object ScheduledOps {

    /** Replace the rescheduled entry with the server's fresh version. */
    fun rescheduled(items: List<ScheduledStatus>, updated: ScheduledStatus): List<ScheduledStatus> =
        items.map { if (it.id == updated.id) updated else it }

    /** Drop a canceled entry; an empty result means the empty state. */
    fun canceled(items: List<ScheduledStatus>, id: String): List<ScheduledStatus> =
        items.filterNot { it.id == id }
}
