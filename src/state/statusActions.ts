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
  return run(async () => onUpdated(await api.value.post<Status>(`/api/v1/statuses/${status.id}/${path}`)), 'Could not save that')
}

export function toggleBoost(status: Status, onUpdated: StatusUpdated): Promise<void> {
  const path = status.reblogged ? 'unreblog' : 'reblog'
  return run(async () => onUpdated(await api.value.post<Status>(`/api/v1/statuses/${status.id}/${path}`)), 'Could not boost that')
}

export function toggleBookmark(status: Status, onUpdated: StatusUpdated): Promise<void> {
  const path = status.bookmarked ? 'unbookmark' : 'bookmark'
  return run(async () => onUpdated(await api.value.post<Status>(`/api/v1/statuses/${status.id}/${path}`)), 'Could not bookmark that')
}

export function votePoll(pollId: string, choices: number[]): Promise<Poll> {
  return run(() => api.value.post<Poll>(`/api/v1/polls/${pollId}/votes`, { choices }), 'Vote failed')
}

export function deleteStatus(status: Status, onDeleted: () => void): Promise<void> {
  return run(async () => {
    await api.value.delete(`/api/v1/statuses/${status.id}`)
    onDeleted()
  }, 'Could not delete that post')
}

export function fetchStatus(id: string): Promise<Status> {
  return api.value.get<Status>(`/api/v1/statuses/${id}`)
}
