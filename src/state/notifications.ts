/**
 * Notifications-side shared state: the follow-request count that badges nav,
 * and a live-notification wiring helper for views that show the stream.
 */
import { signal } from '@preact/signals'
import { api, activeSession } from './auth'
import { getSeenTop, raiseSeenTop, setSeenTop, isNewer, isValidStatusId } from './readState'
import { queueMarkerPush, syncMarkersFromServer } from './markers'
import type { Account, Notification } from '../api/types'

/** Unread notifications, shown as the sidebar Notifications badge. Counted
 *  over the newest COUNT_WINDOW notifications, so a value of COUNT_WINDOW
 *  means "that many or more". */
export const unreadNotificationCount = signal(0)
export const COUNT_WINDOW = 40

/**
 * Recount unread notifications: anything newer than the notifications read
 * mark (the same mark the Notifications view advances). A session with no
 * mark yet gets its baseline set to the newest notification, so a fresh
 * device starts at zero instead of showing the whole history as unread.
 */
export async function refreshUnreadNotificationCount(): Promise<void> {
  const session = activeSession.value?.id ?? ''
  if (!session) return
  const client = api.value
  try {
    await syncMarkersFromServer()
    const page = await client.page<Notification>('/api/v1/notifications', {
      query: { limit: COUNT_WINDOW },
    })
    if ((activeSession.value?.id ?? '') !== session) return // account switched mid-flight
    const mark = getSeenTop(session, 'notifications')
    if (!mark) {
      if (page.items.length > 0) setSeenTop(session, 'notifications', page.items[0].id)
      unreadNotificationCount.value = 0
      return
    }
    unreadNotificationCount.value = page.items.filter((n) => isNewer(n.id, mark)).length
  } catch {
    // Keep the last known count; a failed recount should not invent one.
  }
}

/**
 * Advance the read mark to newestId (local and server) and clear the badge.
 * Callers must only pass the newest notification of the unfiltered list:
 * the mark is a single boundary, so filtered views must not move it.
 */
export function markNotificationsRead(newestId: string): void {
  const session = activeSession.value?.id ?? ''
  if (!session || !isValidStatusId(newestId)) return
  raiseSeenTop(session, 'notifications', newestId)
  queueMarkerPush('notifications', newestId)
  unreadNotificationCount.value = 0
}

/** Pending follow requests awaiting approval (locked accounts). */
export const followRequestCount = signal(0)

/** Refresh the pending-request count. Quietly ignores failures. */
export async function refreshFollowRequestCount(): Promise<void> {
  try {
    const page = await api.value.page<Account>('/api/v1/follow_requests', {
      query: { limit: 80 },
    })
    followRequestCount.value = page.items.length
  } catch {
    // Keep the last known count; a failed count should not badge wrong data.
  }
}

/** Local adjust after authorizing/rejecting one request. */
export function decrementFollowRequestCount(): void {
  followRequestCount.value = Math.max(0, followRequestCount.value - 1)
}
