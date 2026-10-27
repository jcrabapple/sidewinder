/**
 * Compose draft recovery: the in-progress new-post draft persists per
 * account in localStorage (debounced on every change), so a crash, tab
 * close, or accidental dismissal never loses a half-written post.
 *
 * Scope: new-post drafts only — reply drafts are bound to their parent
 * status and follow the reply flow instead. Cleared when the whole post or
 * chain goes out or the user empties the composer.
 */
import type { MediaAttachment } from '../api/types'

export interface DraftMedia {
  attachment: MediaAttachment
  uploading: boolean
}

export interface ComposeDraft {
  text: string
  cw: boolean
  spoiler: string
  visibility: string
  media: DraftMedia[]
  pollOn: boolean
  pollOptions: string[]
  pollMultiple: boolean
  pollExpiry: number
  chain?: string[]
  /** Root first, then one key per chain segment. */
  idempotencyKeys?: string[]
  /** Number of contiguous segments already accepted by the server. */
  publishedCount?: number
  /** Id of the latest published segment, used as the next reply parent. */
  publishedParentId?: string
  savedAt: number
}

const key = (sessionId: string) => `sidewinder.draft.${sessionId}`

/** Only completed server uploads are safe to recover or submit. */
export function readyDraftMedia(media: readonly DraftMedia[] | undefined): DraftMedia[] {
  return (media ?? [])
    .filter((m) => !m.uploading && m.attachment.id.length > 0 && !m.attachment.id.startsWith('pending-'))
    .map((m) => ({ attachment: m.attachment, uploading: false }))
}

/** Normalize optional fields added after the original draft format. */
export function normalizeDraft(raw: ComposeDraft): ComposeDraft | null {
  if (typeof raw.text !== 'string') return null
  const chain = Array.isArray(raw.chain) ? raw.chain.filter((part): part is string => typeof part === 'string') : []
  const segmentCount = chain.length + 1
  let publishedCount = typeof raw.publishedCount === 'number' && Number.isInteger(raw.publishedCount)
    ? Math.max(0, Math.min(raw.publishedCount, segmentCount))
    : 0
  let publishedParentId = typeof raw.publishedParentId === 'string' ? raw.publishedParentId : undefined
  if (publishedCount > 0 && !publishedParentId) {
    publishedCount = 0
    publishedParentId = undefined
  }
  // A complete draft should have been cleared. Never resurrect its final
  // segment and risk publishing it twice.
  if (publishedCount >= segmentCount) return null
  return {
    text: raw.text,
    cw: Boolean(raw.cw),
    spoiler: typeof raw.spoiler === 'string' ? raw.spoiler : '',
    visibility: typeof raw.visibility === 'string' ? raw.visibility : 'public',
    media: readyDraftMedia(Array.isArray(raw.media) ? raw.media : []),
    pollOn: Boolean(raw.pollOn),
    pollOptions: Array.isArray(raw.pollOptions)
      ? raw.pollOptions.filter((option): option is string => typeof option === 'string')
      : ['', ''],
    pollMultiple: Boolean(raw.pollMultiple),
    pollExpiry: typeof raw.pollExpiry === 'number' ? raw.pollExpiry : 86400,
    chain,
    idempotencyKeys: Array.isArray(raw.idempotencyKeys)
      ? raw.idempotencyKeys.filter((value): value is string => typeof value === 'string' && value.length > 0).slice(0, segmentCount)
      : [],
    publishedCount,
    publishedParentId,
    savedAt: typeof raw.savedAt === 'number' ? raw.savedAt : Date.now(),
  }
}

export function saveDraft(sessionId: string, draft: ComposeDraft): void {
  try {
    localStorage.setItem(key(sessionId), JSON.stringify({ ...draft, media: readyDraftMedia(draft.media) }))
  } catch {
    // Blocked storage / quota: draft recovery silently unavailable.
  }
}

export function loadDraft(sessionId: string): ComposeDraft | null {
  try {
    const raw = JSON.parse(localStorage.getItem(key(sessionId)) ?? 'null') as ComposeDraft | null
    if (!raw) return null
    const normalized = normalizeDraft(raw)
    // Drop stale media attachments (older than 48h) whose upload URLs may
    // have been cleaned up server-side, without discarding a thread resume.
    if (normalized && raw.savedAt && Date.now() - raw.savedAt > 48 * 3600_000) normalized.media = []
    return normalized
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
