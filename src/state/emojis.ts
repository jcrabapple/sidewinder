/** Custom emoji catalog for the active server. Fetched once per base, cached. */
import { signal } from '@preact/signals'
import { api } from './auth'
import type { Emoji } from '../api/types'

export type EmojiStatus = 'idle' | 'loading' | 'ready' | 'error'

export const customEmojis = signal<Emoji[]>([])
export const emojisStatus = signal<EmojiStatus>('idle')

let loadedBase: string | null = null

/** Load custom emojis for the active session's server (no-op when cached). */
export async function ensureEmojis(): Promise<void> {
  const base = api.value.base
  if (loadedBase === base && emojisStatus.value === 'ready') return
  if (emojisStatus.value === 'loading' && loadedBase === base) return
  loadedBase = base
  emojisStatus.value = 'loading'
  try {
    const list = await api.value.get<Emoji[]>('/api/v1/custom_emojis')
    customEmojis.value = list.filter((e) => e.visible_in_picker)
    emojisStatus.value = 'ready'
  } catch {
    customEmojis.value = []
    emojisStatus.value = 'error'
  }
}

/** Group picker-visible emojis by category; uncategorized first. */
export function emojisByCategory(list: Emoji[]): [string, Emoji[]][] {
  const groups = new Map<string, Emoji[]>()
  for (const e of list) {
    const cat = e.category ?? ''
    const arr = groups.get(cat)
    if (arr) arr.push(e)
    else groups.set(cat, [e])
  }
  return [...groups.entries()].sort((a, b) => {
    if (a[0] === '') return -1
    if (b[0] === '') return 1
    return a[0].localeCompare(b[0])
  })
}

/** Case-insensitive shortcode match. */
export function searchEmojis(list: Emoji[], query: string): Emoji[] {
  const q = query.trim().toLowerCase()
  if (!q) return list
  return list.filter((e) => e.shortcode.toLowerCase().includes(q))
}
