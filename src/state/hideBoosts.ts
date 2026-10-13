/**
 * Per-account "hide boosts": a client-side, per-session list of accounts
 * whose boost wrappers are dropped from timelines and digests. Their own
 * posts, profile, and notifications about their activity are untouched —
 * this is purely "stop showing me what they chose to share".
 *
 * Same privacy model as keyword mutes: stored only in this browser's
 * localStorage, keyed per session (account), never sent to the server.
 */
import { signal } from '@preact/signals'
import { activeSession } from './auth'

const keyFor = (session: string) => `sidewinder.hiddenBoosters.${session}`

function load(session: string): Set<string> {
  try {
    return new Set(JSON.parse(localStorage.getItem(keyFor(session)) ?? '[]') as string[])
  } catch {
    return new Set()
  }
}

/** Account ids whose boosts are hidden, for the active session. */
export const hiddenBoosters = signal<Set<string>>(new Set())

// Initialize for the session Auth has already restored at module load.
hiddenBoosters.value = load(activeSession.value?.id ?? '')

export function isHiddenBooster(id: string): boolean {
  return hiddenBoosters.value.has(id)
}

export function toggleHiddenBooster(id: string, hide: boolean): void {
  if (hide === isHiddenBooster(id)) return
  const next = new Set(hiddenBoosters.value)
  if (hide) next.add(id)
  else next.delete(id)
  hiddenBoosters.value = next
  try {
    localStorage.setItem(keyFor(activeSession.value?.id ?? ''), JSON.stringify([...next]))
  } catch {
    // Blocked storage: the preference stays session-local.
  }
}

/** Re-read for the active session — call on account switch/logout. */
export function resetHiddenBoosters(): void {
  hiddenBoosters.value = load(activeSession.value?.id ?? '')
}
