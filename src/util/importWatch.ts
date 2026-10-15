/**
 * Watch schedule for import jobs: how often to poll the account's counts and when to stop. Pure (no DOM, no
 * signals) so `pnpm test:import` can run it under node.
 *
 * Why it is shaped like this: GoToSocial works through an import one account at a time, and every unreachable
 * server costs six retries with backoff (about 250 s) before the next account starts. Measured on a real
 * 300-account blocks import (2026-10-15): about 25 accounts a minute while servers answer, three stalls of about
 * 4 minutes, 20+ minutes in all. A few thousand follows run for hours, so the watcher follows progress, not a
 * wall-clock budget. (The first version stopped every job after 15 minutes, with rows still landing.)
 */

export const POLL_FAST_MS = 3_000
export const POLL_SLOW_MS = 10_000
export const POLL_LONG_MS = 30_000
export const FAST_WINDOW_MS = 120_000
export const SLOW_WINDOW_MS = 15 * 60_000
/** No change for this long: the job is 'quiet' (still polling). */
export const QUIET_AFTER_MS = 30_000
/** No change for this long: stop watching, the import has most likely finished. The longest stall seen was ~6 min. */
export const IDLE_LIMIT_MS = 60 * 60_000
/** Hard stop, whether or not the count is still moving. */
export const WATCH_CAP_MS = 24 * 60 * 60_000

/** Delay before the next poll of a job that started `ageMs` ago. */
export function pollDelay(ageMs: number): number {
  if (ageMs < FAST_WINDOW_MS) return POLL_FAST_MS
  return ageMs < SLOW_WINDOW_MS ? POLL_SLOW_MS : POLL_LONG_MS
}

/** How long without a change before a job reads as 'quiet': three polls' worth, never under QUIET_AFTER_MS.
 * (A fixed 30 s would flip to quiet after a single unchanged poll once polls are 30 s apart.) */
export function quietAfter(ageMs: number): number {
  return Math.max(QUIET_AFTER_MS, 3 * pollDelay(ageMs))
}

export type WatchEnd = 'idle' | 'timeout'

/** Why a job that was just polled should stop, or null to keep going. `idleMs` is 0 on a poll that saw a change. */
export function watchEnd(ageMs: number, idleMs: number): WatchEnd | null {
  if (ageMs >= WATCH_CAP_MS) return 'timeout'
  return idleMs >= IDLE_LIMIT_MS ? 'idle' : null
}

interface JobLike {
  phase: 'watching' | 'quiet' | 'ended'
  endedReason?: string
  startedAt: number
}

export type ResumeAction = 'leave' | 'poll' | 'revive' | 'expire'

/**
 * What to do with a stored job when the page loads. A job a poll has not ended is polled again at once (the poll
 * applies the idle rule, so time spent away counts as idle only if the count really did not move). 'revive' takes
 * back a job the first version's fixed 15-minute limit cut off: it was stored as 'timeout', which now only means
 * the 24 h cap, so a 'timeout' younger than that is one of those.
 */
export function resumeAction(job: JobLike, now: number): ResumeAction {
  const age = now - job.startedAt
  if (job.phase === 'ended') return job.endedReason === 'timeout' && age < WATCH_CAP_MS ? 'revive' : 'leave'
  return age >= WATCH_CAP_MS ? 'expire' : 'poll'
}

/** Whether an ended job may be watched again. A newer import or a sign-out ended the others for good. */
export function canRewatch(reason: string | undefined, ageMs: number): boolean {
  if (ageMs >= WATCH_CAP_MS) return false
  return reason === 'stopped' || reason === 'timeout' || reason === 'idle' || reason === 'error'
}
