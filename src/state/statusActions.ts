/**
 * Shared status/poll mutations. Each toggle calls the API and returns the
 * fresh object so the caller can update its state. Failures surface as toasts
 * and reject.
 */
import { api } from './auth'
import { toast } from './toast'
import type { Status, Poll } from '../api/types'

export type StatusUpdated = (s: Status) => void

async function run<T>(mutate: () => Promise<T>, errorMsg: string): Promise<T> {
  try {
    return await mutate()
  } catch (e) {
    toast(e instanceof Error ? e.message : errorMsg, 'error')
    throw e
  }
}

export function toggleFav(status: Status, onUpdated: StatusUpdated): Promise<void> {
  const path = status.favourited ? 'unfavourite' : 'favourite'
  return run(async () => onUpdated(await api.value.post<Status>(`/api/v1/statuses/${encodeURIComponent(status.id)}/${path}`)), 'Could not save that')
}

export async function toggleBoost(status: Status, onUpdated: StatusUpdated): Promise<void> {
  const path = status.reblogged ? 'unreblog' : 'reblog'
  try {
    const res = await api.value.post<Status>(`/api/v1/statuses/${encodeURIComponent(status.id)}/${path}`)
    // reblog returns a boost wrapper (account = booster, empty content);
    // unreblog returns the original. Either way, update the card in place
    // with the ORIGINAL status so the UI just flips the button state.
    if (res.reblog) {
      onUpdated({ ...res.reblog, reblogged: path === 'reblog' })
    } else {
      onUpdated({
        ...status,
        reblogged: path === 'reblog',
        reblogs_count: Math.max(0, status.reblogs_count + (path === 'reblog' ? 1 : -1)),
      })
    }
  } catch (e) {
    toast(e instanceof Error ? e.message : 'Could not boost that', 'error')
  }
}

export function toggleBookmark(status: Status, onUpdated: StatusUpdated): Promise<void> {
  const path = status.bookmarked ? 'unbookmark' : 'bookmark'
  return run(async () => onUpdated(await api.value.post<Status>(`/api/v1/statuses/${encodeURIComponent(status.id)}/${path}`)), 'Could not bookmark that')
}

export function togglePin(status: Status, onUpdated: StatusUpdated): Promise<void> {
  const path = status.pinned ? 'unpin' : 'pin'
  return run(
    async () => {
      const updated = await api.value.post<Status>(`/api/v1/statuses/${encodeURIComponent(status.id)}/${path}`)
      onUpdated(updated)
      toast(path === 'pin' ? 'Pinned to profile' : 'Unpinned from profile', 'info')
    },
    path === 'pin' ? 'Could not pin post' : 'Could not unpin post',
  )
}

export function toggleMuteStatus(status: Status, onUpdated: StatusUpdated): Promise<void> {
  const path = status.muted ? 'unmute' : 'mute'
  return run(
    async () => {
      const updated = await api.value.post<Status>(`/api/v1/statuses/${encodeURIComponent(status.id)}/${path}`)
      onUpdated(updated)
      toast(path === 'mute' ? 'Conversation muted' : 'Conversation unmuted', 'info')
    },
    path === 'mute' ? 'Could not mute conversation' : 'Could not unmute conversation',
  )
}

export function votePoll(pollId: string, choices: number[]): Promise<Poll> {
  return run(() => api.value.post<Poll>(`/api/v1/polls/${encodeURIComponent(pollId)}/votes`, { choices }), 'Vote failed')
}

export function deleteStatus(status: Status, onDeleted: () => void): Promise<void> {
  return run(async () => {
    await api.value.delete(`/api/v1/statuses/${encodeURIComponent(status.id)}`)
    onDeleted()
  }, 'Could not delete that post')
}

export function fetchStatus(id: string): Promise<Status> {
  return api.value.get<Status>(`/api/v1/statuses/${encodeURIComponent(id)}`)
}
