/**
 * Emoji catalog for the composer picker: a built-in unicode set (so the picker
 * is always useful, even on servers with no custom emoji) merged with the
 * active server's custom emoji.
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
}

/** Curated unicode set, grouped by category. Names power search. */
const UNICODE: Record<string, [string, string][]> = {
  Smileys: [
    ['😀', 'grinning'], ['😃', 'smiley happy'], ['😄', 'laughing happy'], ['😁', 'beam'],
    ['😅', 'sweat laugh'], ['🤣', 'rofl'], ['😂', 'joy tears'], ['🙂', 'slight smile'],
    ['😉', 'wink'], ['😊', 'blush'], ['😍', 'heart eyes love'], ['🥰', 'love hearts'],
    ['😘', 'kiss'], ['😎', 'cool sunglasses'], ['🤩', 'starstruck'], ['🤔', 'thinking'],
    ['🤨', 'eyebrow'], ['😐', 'neutral'], ['😴', 'sleep'], ['🥱', 'yawn'],
    ['😪', 'sleepy'], ['😵', 'dizzy'], ['🤯', 'mind blown'], ['🥳', 'party'],
    ['😎', 'sunglasses'], ['😢', 'cry'], ['😭', 'sob crying'], ['😤', 'huff'],
    ['😡', 'rage angry'], ['🤬', 'swearing'], ['😱', 'scream fear'], ['😨', 'fearful'],
    ['🤗', 'hug'], ['🤭', 'giggle'], ['🤫', 'shh'], ['🙄', 'eye roll'],
    ['😬', 'grimace'], ['🥺', 'pleading'], ['😷', 'mask'], ['🤒', 'sick'],
    ['🤕', 'hurt'], ['🥴', 'woozy'], ['😈', 'devil'], ['👻', 'ghost'],
    ['💀', 'skull'], ['💩', 'poop'], ['🤖', 'robot'], ['👽', 'alien'],
  ],
  People: [
    ['👋', 'wave hello'], ['🤚', 'hand'], ['✌️', 'peace victory'], ['🤞', 'fingers crossed'],
    ['🤙', 'call me'], ['👍', 'thumbs up'], ['👎', 'thumbs down'], ['👊', 'fist bump'],
    ['👏', 'clap'], ['🤝', 'handshake'], ['🙏', 'pray thanks'], ['💪', 'muscle strong'],
    ['🫡', 'salute'], ['👀', 'eyes look'], ['🧠', 'brain'], ['🦴', 'bone'],
    ['🫂', 'hug people'], ['💃', 'dancer'], ['🕺', 'dancer man'], ['🧗', 'climbing'],
    ['🏄', 'surfing'], ['🏃', 'running'], ['🚶', 'walking'], ['🧘', 'meditate yoga'],
  ],
  Animals: [
    ['🐶', 'dog puppy'], ['🐱', 'cat kitten'], ['🐭', 'mouse'], ['🐹', 'hamster'],
    ['🐰', 'rabbit bunny'], ['🦊', 'fox'], ['🐻', 'bear'], ['🐼', 'panda'],
    ['🐨', 'koala'], ['🐯', 'tiger'], ['🦁', 'lion'], ['🐮', 'cow'],
    ['🐷', 'pig'], ['🐸', 'frog'], ['🐵', 'monkey'], ['🐔', 'chicken'],
    ['🐧', 'penguin'], ['🐦', 'bird'], ['🦆', 'duck'], ['🦅', 'eagle'],
    ['🦉', 'owl'], ['🐺', 'wolf'], ['🐴', 'horse'], ['🦄', 'unicorn'],
    ['🐝', 'bee'], ['🦋', 'butterfly'], ['🐌', 'snail'], ['🐞', 'ladybug'],
    ['🐢', 'turtle'], ['🐍', 'snake sidewinder'], ['🦖', 'dinosaur t-rex'], ['🐙', 'octopus'],
    ['🦀', 'crab'], ['🐠', 'fish'], ['🐬', 'dolphin'], ['🐳', 'whale'],
  ],
  Food: [
    ['🍎', 'apple'], ['🍊', 'orange'], ['🍋', 'lemon'], ['🍌', 'banana'],
    ['🍉', 'watermelon'], ['🍇', 'grapes'], ['🍓', 'strawberry'], ['🍒', 'cherries'],
    ['🍑', 'peach'], ['🥭', 'mango'], ['🍍', 'pineapple'], ['🥥', 'coconut'],
    ['🍅', 'tomato'], ['🥑', 'avocado'], ['🌽', 'corn'], ['🥕', 'carrot'],
    ['🍞', 'bread'], ['🥐', 'croissant'], ['🧀', 'cheese'], ['🍳', 'egg cooking'],
    ['🍔', 'burger'], ['🍟', 'fries'], ['🍕', 'pizza'], ['🌮', 'taco'],
    ['🍜', 'noodles ramen'], ['🍣', 'sushi'], ['🍦', 'ice cream'], ['🍩', 'donut'],
    ['🍪', 'cookie'], ['🎂', 'cake birthday'], ['🍫', 'chocolate'], ['🍿', 'popcorn'],
    ['☕', 'coffee'], ['🍵', 'tea'], ['🧋', 'boba'], ['🍺', 'beer'],
    ['🍻', 'beers cheers'], ['🍷', 'wine'], ['🥂', 'champagne'], ['🥤', 'soda'],
  ],
  Activities: [
    ['⚽', 'soccer'], ['🏀', 'basketball'], ['🏈', 'football'], ['⚾', 'baseball'],
    ['🎾', 'tennis'], ['🏐', 'volleyball'], ['🎱', 'billiards pool'], ['🏓', 'ping pong'],
    ['🎮', 'gaming controller'], ['🕹️', 'joystick arcade'], ['🎲', 'dice'], ['🧩', 'puzzle'],
    ['🎸', 'guitar'], ['🎺', 'trumpet'], ['🎻', 'violin'], ['🥁', 'drums'],
    ['🎤', 'microphone karaoke'], ['🎧', 'headphones'], ['🎵', 'music note'], ['🎬', 'movie clapper'],
    ['🎯', 'target dart'], ['🏆', 'trophy win'], ['🥇', 'gold medal'], ['🎿', 'ski'],
  ],
  Travel: [
    ['🚗', 'car'], ['🚕', 'taxi'], ['🚌', 'bus'], ['🏎️', 'race car'],
    ['🚓', 'police car'], ['🚑', 'ambulance'], ['🚒', 'fire truck'], ['🚚', 'truck'],
    ['🚜', 'tractor'], ['🛴', 'scooter'], ['🚲', 'bike bicycle'], ['🛵', 'scooter moto'],
    ['✈️', 'airplane'], ['🚀', 'rocket'], ['🛸', 'ufo'], ['🚁', 'helicopter'],
    ['⛵', 'sailboat'], ['🚢', 'ship'], ['🗺️', 'map'], ['🧭', 'compass'],
    ['🏔️', 'mountain'], ['🌋', 'volcano'], ['🏕️', 'camping'], ['🏖️', 'beach'],
    ['🌅', 'sunrise'], ['🌇', 'sunset city'], ['🌌', 'galaxy stars'], ['💫', 'dizzy stars'],
    ['⭐', 'star'], ['🌙', 'moon'], ['☀️', 'sun'], ['⛅', 'cloud sun'],
    ['🌧️', 'rain'], ['⛈️', 'storm'], ['❄️', 'snow'], ['🌈', 'rainbow'],
  ],
  Objects: [
    ['⌚', 'watch'], ['📱', 'phone mobile'], ['💻', 'laptop computer'], ['🖥️', 'desktop'],
    ['⌨️', 'keyboard'], ['🖨️', 'printer'], ['🖱️', 'mouse computer'], ['💾', 'floppy'],
    ['📷', 'camera'], ['📹', 'video camera'], ['📺', 'tv'], ['📻', 'radio'],
    ['🔋', 'battery'], ['💡', 'light bulb idea'], ['🔧', 'wrench'], ['🔨', 'hammer'],
    ['🛠️', 'tools'], ['🧲', 'magnet'], ['🔒', 'lock'], ['🔑', 'key'],
    ['📚', 'books'], ['📖', 'book reading'], ['📝', 'note memo'], ['✏️', 'pencil'],
    ['📌', 'pin'], ['📎', 'clip'], ['📅', 'calendar'], ['💰', 'money bag'],
    ['💳', 'credit card'], ['🎁', 'gift present'], ['🎈', 'balloon'], ['🎉', 'party popper celebrate'],
    ['✉️', 'envelope mail'], ['📦', 'package box'], ['🏠', 'house home'], ['🚪', 'door'],
    ['🛏️', 'bed'], ['🚿', 'shower'], ['🧹', 'broom clean'], ['🧸', 'teddy bear'],
  ],
  Symbols: [
    ['❤️', 'red heart love'], ['🧡', 'orange heart'], ['💛', 'yellow heart'], ['💚', 'green heart'],
    ['💙', 'blue heart'], ['💜', 'purple heart'], ['🖤', 'black heart'], ['🤍', 'white heart'],
    ['💔', 'broken heart'], ['💕', 'two hearts'], ['💖', 'sparkle heart'], ['💯', 'hundred perfect'],
    ['✅', 'check done'], ['❌', 'x no'], ['⚡', 'lightning zap'], ['🔥', 'fire hot lit'],
    ['☑️', 'checkbox'], ['⚠️', 'warning'], ['🚫', 'prohibited'], ['♻️', 'recycle'],
    ['❓', 'question'], ['❗', 'exclamation'], ['💬', 'speech bubble'], ['👁️‍🗨️', 'witness'],
    ['🔔', 'bell notification'], ['🔗', 'link'], ['➕', 'plus add'], ['✖️', 'multiply'],
    ['🆗', 'ok'], ['🔝', 'top'], ['🔴', 'red circle'], ['🟢', 'green circle'],
  ],
}

const unicodeItems: PickerEmoji[] = Object.entries(UNICODE).flatMap(([category, list]) =>
  list.map(([char, name]) => ({
    key: `u:${name}:${char}`,
    category,
    name,
    char,
  })),
)

/** Custom emoji mapped into the unified picker shape. */
function customItems(): PickerEmoji[] {
  return customEmojis.value.map((e) => ({
    key: `c:${e.shortcode}`,
    category: e.category ?? 'Custom',
    name: e.shortcode,
    shortcode: e.shortcode,
    url: e.static_url || e.url,
  }))
}

/** Full picker list: custom emoji first, then the unicode set. */
export function pickerEmojis(): PickerEmoji[] {
  const custom = customItems()
  const seen = new Set(custom.map((c) => c.key))
  return [...custom, ...unicodeItems.filter((u) => !seen.has(u.key))]
}

/** Case-insensitive match against name and shortcode. */
export function filterPickerEmojis(list: PickerEmoji[], query: string): PickerEmoji[] {
  const q = query.trim().toLowerCase()
  if (!q) return list
  return list.filter(
    (e) => e.name.toLowerCase().includes(q) || e.shortcode?.toLowerCase().includes(q),
  )
}

/** Group by category; server "Custom" and uncategorized first. */
export function groupPickerEmojis(list: PickerEmoji[]): [string, PickerEmoji[]][] {
  const groups = new Map<string, PickerEmoji[]>()
  for (const e of list) {
    const arr = groups.get(e.category)
    if (arr) arr.push(e)
    else groups.set(e.category, [e])
  }
  return [...groups.entries()].sort((a, b) => {
    if (a[0] === 'Custom' || a[0] === '') return -1
    if (b[0] === 'Custom' || b[0] === '') return 1
    return a[0].localeCompare(b[0])
  })
}
