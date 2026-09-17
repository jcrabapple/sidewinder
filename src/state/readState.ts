/**
 * Read-state memory: a per-session, per-timeline high-water mark of the
 * newest status id the app has shown the user. Timeline feeds are reverse-
 * chronological and GoToSocial ids are ULIDs (time-ordered), so one id is
 * the read/unread boundary.
 *
 * The mark is raised when a timeline LOADS, never on scroll: reloading the
 * page must not resurrect the jump pill for posts the user has already seen.
 * Stored in localStorage; nothing about reads ever leaves the browser.
 */

const key = (session: string, timeline: string) => `sidewinder.read.${session}.${timeline}`

export function getSeenTop(session: string, timeline: string): string {
  try {
    return localStorage.getItem(key(session, timeline)) ?? ''
  } catch {
    return ''
  }
}

/** Raise the high-water mark to id if newer. Returns the PREVIOUS mark. */
export function raiseSeenTop(session: string, timeline: string, id: string): string {
  const prev = getSeenTop(session, timeline)
  if (id <= prev) return prev
  try {
    localStorage.setItem(key(session, timeline), id)
  } catch {
    // Storage full/blocked: read state stays session-local in memory.
  }
  return prev
}

/** Force-set the high-water mark (server marker adoption) — no newer-than check. */
export function setSeenTop(session: string, timeline: string, id: string): void {
  try {
    localStorage.setItem(key(session, timeline), id)
  } catch {
    // Storage full/blocked: read state stays session-local in memory.
  }
}

/**
 * Absence-aware read marking: a timeline load right after the user was
 * actively using the app means they're reading now (raise the mark), but
 * the FIRST load after an absence (no interaction for ABSENCE_MS) is the
 * "just came back" moment — raising then would mark away-posts as read
 * before the user has seen them, emptying Catch-up. So:
 *
 *   - touchActivity(): heartbeat, called on app open + user interaction.
 *   - wasRecentlyActive(): true when a load counts as active reading.
 */
const ACTIVITY_KEY = 'sidewinder.lastActive'
export const ABSENCE_MS = 5 * 60_000

export function touchActivity(): void {
  try {
    localStorage.setItem(ACTIVITY_KEY, String(Date.now()))
  } catch {
    // Blocked storage: every load then counts as active (safe default).
  }
}

export function wasRecentlyActive(): boolean {
  try {
    const last = Number(localStorage.getItem(ACTIVITY_KEY) ?? '0')
    return last > 0 && Date.now() - last < ABSENCE_MS
  } catch {
    return true
  }
}

/** Unseen = strictly newer than the mark. Empty mark means "nothing to catch up on yet". */
export function isNewer(id: string, seenTop: string): boolean {
  return seenTop !== '' && id > seenTop
}
