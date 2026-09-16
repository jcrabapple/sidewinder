/**
 * Notifications-side shared state: the follow-request count that badges nav,
 * and a live-notification wiring helper for views that show the stream.
 */
import { signal } from '@preact/signals'
import { api } from './auth'
import type { Account } from '../api/types'

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
