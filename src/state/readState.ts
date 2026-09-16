/**
 * Read-state memory: a per-session, per-timeline cursor marking what the user
 * has already read. Timeline feeds are reverse-chronological and GoToSocial
 * ids are ULIDs (time-ordered), so one max-id cursor marks everything older
 * as seen — no need to store thousands of individual ids.
 *
 * Stored in localStorage; nothing about reads ever leaves the browser.
 */

const key = (session: string, timeline: string) => `sidewinder.read.${session}.${timeline}`

export function getCursor(session: string, timeline: string): string {
  try {
    return localStorage.getItem(key(session, timeline)) ?? ''
  } catch {
    return ''
  }
}

/** Advance the cursor to maxId if it is newer than the current one. */
export function advanceCursor(session: string, timeline: string, maxId: string): string {
  const current = getCursor(session, timeline)
  if (maxId <= current) return current
  try {
    localStorage.setItem(key(session, timeline), maxId)
  } catch {
    // Storage full/blocked: read state stays session-local in memory.
  }
  return maxId
}

/** Unseen = strictly newer than the cursor. Empty cursor means "seen everything so far". */
export function isUnseen(id: string, cursor: string): boolean {
  return cursor !== '' && id > cursor
}
