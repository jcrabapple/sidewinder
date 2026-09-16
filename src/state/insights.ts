/**
 * Local insights: point-in-time numbers the API gives us, tracked over
 * time so trends emerge. All data lives in localStorage; nothing is sent
 * anywhere.
 *
 * - Follower snapshots: recorded at most once per day per session, so a
 *   sparkline builds up as the app is used.
 * - Post performance helpers: engagement = favourites + 2×boosts + replies
 *   (boosts weighted as the stronger signal, matching how the algorithms
 *   people actually care about treat reach).
 */
import type { Status } from '../api/types'

export interface FollowerSnapshot {
  /** YYYY-MM-DD (local) — one snapshot per day. */
  d: string
  n: number
}

const KEY = 'sidewinder.followers'
const MAX_SNAPSHOTS = 365

export function followerSnapshots(): FollowerSnapshot[] {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? '[]') as FollowerSnapshot[]
    return Array.isArray(raw) ? raw : []
  } catch {
    return []
  }
}

function today(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Record today's follower count (no-op if one exists for today). */
export function recordFollowerSnapshot(count: number): void {
  if (!Number.isFinite(count)) return
  const snaps = followerSnapshots()
  const day = today()
  if (snaps.length > 0 && snaps[snaps.length - 1].d === day) {
    if (snaps[snaps.length - 1].n === count) return
    // Same day, different count: update in place.
    snaps[snaps.length - 1] = { d: day, n: count }
  } else {
    snaps.push({ d: day, n: count })
  }
  try {
    localStorage.setItem(KEY, JSON.stringify(snaps.slice(-MAX_SNAPSHOTS)))
  } catch {
    // Blocked storage: insights stay session-local.
  }
}

export function engagement(s: Status): number {
  return (s.favourites_count ?? 0) + 2 * (s.reblogs_count ?? 0) + (s.replies_count ?? 0)
}

export function median(values: number[]): number {
  if (values.length === 0) return 0
  const sorted = [...values].sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
}
