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
import { htmlToText } from '../util/text'
import type { Status } from '../api/types'

export interface KeywordMute {
  id: string
  phrase: string
}

const KEY = 'sidewinder.mutes'

function load(): KeywordMute[] {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? '[]') as KeywordMute[]
    return Array.isArray(raw) ? raw.filter((m) => m && typeof m.phrase === 'string') : []
  } catch {
    return []
  }
}

export const keywordMutes = signal<KeywordMute[]>(load())

function persist() {
  try {
    localStorage.setItem(KEY, JSON.stringify(keywordMutes.value))
  } catch {
    // Blocked storage: mutes stay session-local.
  }
}

export function addKeywordMute(phrase: string): boolean {
  const trimmed = phrase.trim()
  if (!trimmed) return false
  if (keywordMutes.value.some((m) => m.phrase.toLowerCase() === trimmed.toLowerCase())) return false
  keywordMutes.value = [...keywordMutes.value, { id: crypto.randomUUID(), phrase: trimmed }]
  persist()
  return true
}

export function removeKeywordMute(id: string): void {
  keywordMutes.value = keywordMutes.value.filter((m) => m.id !== id)
  persist()
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
