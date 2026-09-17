/**
 * Compose draft recovery: the in-progress new-post draft persists per
 * account in localStorage (debounced on every change), so a crash, tab
 * close, or accidental dismissal never loses a half-written post.
 *
 * Scope: new-post drafts only — reply drafts are bound to their parent
 * status and follow the reply flow instead. Cleared when the post goes
 * out or the user empties the composer.
 */
import type { MediaAttachment } from '../api/types'

export interface ComposeDraft {
  text: string
  cw: boolean
  spoiler: string
  visibility: string
  media: { attachment: MediaAttachment; uploading: boolean }[]
  pollOn: boolean
  pollOptions: string[]
  pollMultiple: boolean
  pollExpiry: number
  savedAt: number
}

const key = (sessionId: string) => `sidewinder.draft.${sessionId}`

export function saveDraft(sessionId: string, draft: ComposeDraft): void {
  try {
    localStorage.setItem(key(sessionId), JSON.stringify(draft))
  } catch {
    // Blocked storage / quota: draft recovery silently unavailable.
  }
}

export function loadDraft(sessionId: string): ComposeDraft | null {
  try {
    const raw = JSON.parse(localStorage.getItem(key(sessionId)) ?? 'null') as ComposeDraft | null
    if (!raw || typeof raw.text !== 'string') return null
    // Drop stale media attachments (older than 48h) whose upload URLs may
    // have been cleaned up server-side.
    if (raw.savedAt && Date.now() - raw.savedAt > 48 * 3600_000) {
      clearDraft(sessionId)
      return null
    }
    return raw
  } catch {
    return null
  }
}

export function clearDraft(sessionId: string): void {
  try {
    localStorage.removeItem(key(sessionId))
  } catch {
    // Nothing to do.
  }
}
