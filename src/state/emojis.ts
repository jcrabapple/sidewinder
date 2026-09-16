/**
 * Emoji catalog for the composer picker: the full Unicode CLDR set
 * (generated from emojibase-data by scripts/build-emoji-data.mjs) merged
 * with the active server's custom emoji.
 */
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
    // Custom emoji are optional; the unicode set still works.
    customEmojis.value = []
    emojisStatus.value = 'ready'
  }
}

export interface PickerEmoji {
  key: string
  category: string
  name: string
  /** Unicode emoji character (inserted verbatim). */
  char?: string
  /** Custom emoji shortcode (inserted as :shortcode:). */
  shortcode?: string
  url?: string
  /** Lowercase search tokens: label, CLDR tags, shortcodes. */
  tokens: string[]
}

// Group indices follow Unicode's CLDR emoji group order (group 2 = components, excluded).
const GROUP_NAMES: Record<number, string> = {
  0: 'Smileys & Emotion',
  1: 'People & Body',
  3: 'Animals & Nature',
  4: 'Food & Drink',
  5: 'Travel & Places',
  6: 'Activities',
  7: 'Objects',
  8: 'Symbols',
  9: 'Flags',
}

interface EmojiRow {
  c: string
  l: string
  g: number
  t: string[]
}

let unicodeItems: PickerEmoji[] = []
const unicodeLoaded = signal(false)

/** Load the unicode set lazily; the JSON is a separate chunk fetched on first use. */
export async function loadEmojiSet(): Promise<void> {
  if (unicodeLoaded.value) return
  const data = (await import('./emoji-data.json')).default as EmojiRow[]
  unicodeItems = data.map((r) => ({
    key: `u:${r.c}`,
    category: GROUP_NAMES[r.g] ?? 'Other',
    name: r.l,
    char: r.c,
    tokens: r.t,
  }))
  unicodeLoaded.value = true
}

/** Custom emoji mapped into the unified picker shape. */
function customItems(): PickerEmoji[] {
  return customEmojis.value.map((e) => ({
    key: `c:${e.shortcode}`,
    category: 'Custom',
    name: e.shortcode,
    shortcode: e.shortcode,
    url: e.static_url || e.url,
    tokens: [e.shortcode.toLowerCase()],
  }))
}

/** Full picker list: custom emoji first, then the unicode set. */
export function pickerEmojis(): PickerEmoji[] {
  // Read for reactivity: views re-render once the lazy set lands.
  unicodeLoaded.value
  return [...customItems(), ...unicodeItems]
}

/** Case-insensitive match against label, tags, and shortcodes. */
export function filterPickerEmojis(list: PickerEmoji[], query: string): PickerEmoji[] {
  const q = query.trim().toLowerCase()
  if (!q) return list
  return list.filter((e) => e.tokens.some((t) => t.includes(q)))
}

/** Group by category; server "Custom" first. */
export function groupPickerEmojis(list: PickerEmoji[]): [string, PickerEmoji[]][] {
  const groups = new Map<string, PickerEmoji[]>()
  for (const e of list) {
    const arr = groups.get(e.category)
    if (arr) arr.push(e)
    else groups.set(e.category, [e])
  }
  return [...groups.entries()].sort((a, b) => {
    if (a[0] === 'Custom') return -1
    if (b[0] === 'Custom') return 1
    return a[0].localeCompare(b[0])
  })
}
