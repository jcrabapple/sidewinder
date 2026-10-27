/**
 * Client-side keyword mutes: private, per-browser content filters the
 * server never sees. Each mute is a phrase; a post is muted when the
 * phrase matches its text, content warning, or (for @-prefixed phrases)
 * the author's handle. Phrases starting with '@' are therefore a cheap
 * account mute.
 *
 * Matching is deliberately simple — case-insensitive substring — because
 * the point is noise control, not precision filtering. Applied at render
 * time in timelines and the catch-up digest; notifications are exempt so
 * mentions still get through.
 */
import { signal } from '@preact/signals'
import { activeSession } from './auth'
import { htmlToText } from '../util/text'
import type { Status } from '../api/types'

export interface KeywordMute {
  id: string
  phrase: string
}

const LEGACY_KEY = 'sidewinder.mutes'
const key = (session: string) => `sidewinder.mutes.${session}`
type MuteStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

function parse(raw: string | null): KeywordMute[] {
  try {
    const parsed = JSON.parse(raw ?? '[]') as KeywordMute[]
    return Array.isArray(parsed) ? parsed.filter((m) => m && typeof m.phrase === 'string') : []
  } catch {
    return []
  }
}

/** Load only this session's filters. Legacy global filters belong to the
 * first active account that reads them, never to every account. */
export function loadMutesFor(session: string | null, storage: MuteStorage = localStorage): KeywordMute[] {
  if (!session) return []
  try {
    const scoped = storage.getItem(key(session))
    if (scoped !== null) {
      try {
        storage.removeItem(LEGACY_KEY)
      } catch {
        // The scoped copy already prevents another account from claiming it.
      }
      return parse(scoped)
    }
    const legacy = storage.getItem(LEGACY_KEY)
    if (legacy === null) return []
    const migrated = parse(legacy)
    storage.setItem(key(session), JSON.stringify(migrated))
    try {
      storage.removeItem(LEGACY_KEY)
    } catch {
      // The scoped copy already prevents another account from claiming it.
    }
    return migrated
  } catch {
    return []
  }
}

export const keywordMutes = signal<KeywordMute[]>(loadMutesFor(activeSession.value?.id ?? null))

/** Reload after login, account switch, logout, or session reset. */
export function reloadKeywordMutes(): void {
  keywordMutes.value = loadMutesFor(activeSession.value?.id ?? null)
}

function persist(session: string) {
  try {
    localStorage.setItem(key(session), JSON.stringify(keywordMutes.value))
  } catch {
    // Blocked storage: mutes stay session-local.
  }
}

export function addKeywordMute(phrase: string): boolean {
  const session = activeSession.value?.id
  if (!session) return false
  const trimmed = phrase.trim()
  if (!trimmed) return false
  if (keywordMutes.value.some((m) => m.phrase.toLowerCase() === trimmed.toLowerCase())) return false
  keywordMutes.value = [...keywordMutes.value, { id: crypto.randomUUID(), phrase: trimmed }]
  persist(session)
  return true
}

export function removeKeywordMute(id: string): void {
  const session = activeSession.value?.id
  if (!session) return
  keywordMutes.value = keywordMutes.value.filter((m) => m.id !== id)
  persist(session)
}

/** The first mute matching this status (checked against the underlying
 *  post for boosts), or null. */
export function muteFor(status: Status): KeywordMute | null {
  const mutes = keywordMutes.value
  if (mutes.length === 0) return null
  const inner = status.reblog ?? status
  const text = `${htmlToText(inner.content)}\n${inner.spoiler_text ?? ''}`.toLowerCase()
  const acct = `@${inner.account.acct.toLowerCase()}`
  for (const m of mutes) {
    const phrase = m.phrase.toLowerCase()
    if (phrase.startsWith('@')) {
      // @user or @user@domain — match the handle prefix.
      if (acct.startsWith(phrase) || acct === phrase) return m
    } else if (text.includes(phrase)) {
      return m
    }
  }
  return null
}
