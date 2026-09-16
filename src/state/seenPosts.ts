/**
 * Seen-post history: a bounded, client-side searchable cache of statuses
 * that have streamed through the timelines. GoToSocial deliberately has no
 * federation-wide full-text search (its status search only covers your own
 * posts and replies to you), so this answers "I saw a post about X
 * recently" from the corpus the user actually saw.
 *
 * Hard cap of MAX_POSTS with oldest-first eviction (ULID ids are
 * time-ordered, so primary-key order IS chronology). Text only — no media,
 * no HTML — which keeps 10k posts around 7 MB. Off by default: nothing is
 * written unless settings.seenHistory is on. Everything lives in this
 * browser's IndexedDB and clears with one button.
 */
import { settings } from './settings'
import { htmlToText } from '../util/text'
import type { Status } from '../api/types'

export const MAX_POSTS = 10_000

export interface SeenPost {
  id: string
  acct: string
  name: string
  avatar: string
  text: string
  ts: string
  favs: number
  boosts: number
}

let dbPromise: Promise<IDBDatabase | null> | null = null

function openDb(): Promise<IDBDatabase | null> {
  dbPromise ??= new Promise((resolve) => {
    try {
      const req = indexedDB.open('sidewinder', 1)
      req.onupgradeneeded = () => {
        const db = req.result
        if (!db.objectStoreNames.contains('seen')) db.createObjectStore('seen', { keyPath: 'id' })
      }
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => resolve(null)
    } catch {
      // Private-browsing / blocked storage: the feature is simply off.
      resolve(null)
    }
  })
  return dbPromise
}

function txDone(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
    tx.onabort = () => reject(tx.error)
  })
}

function toRecord(s: Status): SeenPost {
  const text = s.spoiler_text ? `CW: ${s.spoiler_text} — ${htmlToText(s.content)}` : htmlToText(s.content)
  return {
    id: s.id,
    acct: s.account.acct.startsWith('@') ? s.account.acct : `@${s.account.acct}`,
    name: s.account.display_name || s.account.username,
    avatar: s.account.avatar_static || s.account.avatar,
    text: text.slice(0, 2000),
    ts: s.created_at,
    favs: s.favourites_count ?? 0,
    boosts: s.reblogs_count ?? 0,
  }
}

/** Record statuses shown by a timeline load. Boosts are unwrapped to the
 *  underlying post (a boost only re-confirms you saw the original). */
export async function recordSeenPosts(statuses: Status[]): Promise<void> {
  if (!settings.value.seenHistory || statuses.length === 0) return
  const db = await openDb()
  if (!db) return
  try {
    const records = new Map<string, SeenPost>()
    for (const s of statuses) {
      const inner = s.reblog ?? s
      if (!records.has(inner.id)) records.set(inner.id, toRecord(inner))
    }
    const tx = db.transaction('seen', 'readwrite')
    const store = tx.objectStore('seen')
    for (const r of records.values()) store.put(r)
    await txDone(tx)
    await evict(db)
  } catch (e) {
    console.debug('seenPosts: record failed', e)
  }
}

async function evict(db: IDBDatabase): Promise<void> {
  const tx = db.transaction('seen', 'readwrite')
  const store = tx.objectStore('seen')
  const total = await new Promise<number>((resolve, reject) => {
    const req = store.count()
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
  const excess = total - MAX_POSTS
  if (excess <= 0) return
  // Primary keys are ULIDs: ascending cursor = oldest first.
  await new Promise<void>((resolve, reject) => {
    let left = excess
    const req = store.openCursor()
    req.onsuccess = () => {
      const cursor = req.result
      if (!cursor || left <= 0) return resolve()
      cursor.delete()
      left--
      cursor.continue()
    }
    req.onerror = () => reject(req.error)
  })
  await txDone(tx)
}

/** All tokens must appear (case-insensitive) in the post text or author. */
export async function searchSeenPosts(query: string, limit = 80): Promise<SeenPost[]> {
  const db = await openDb()
  if (!db) return []
  const tokens = query.toLowerCase().split(/\s+/).filter(Boolean)
  if (tokens.length === 0) return []
  try {
    const all = await new Promise<SeenPost[]>((resolve, reject) => {
      const req = db.transaction('seen').objectStore('seen').getAll()
      req.onsuccess = () => resolve(req.result as SeenPost[])
      req.onerror = () => reject(req.error)
    })
    return all
      .filter((p) => {
        const hay = `${p.text}\n${p.acct}\n${p.name}`.toLowerCase()
        return tokens.every((t) => hay.includes(t))
      })
      .sort((a, b) => b.id.localeCompare(a.id))
      .slice(0, limit)
  } catch (e) {
    console.debug('seenPosts: search failed', e)
    return []
  }
}

export async function seenPostsCount(): Promise<number> {
  const db = await openDb()
  if (!db) return 0
  try {
    return await new Promise<number>((resolve, reject) => {
      const req = db.transaction('seen').objectStore('seen').count()
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => reject(req.error)
    })
  } catch {
    return 0
  }
}

export async function clearSeenPosts(): Promise<void> {
  const db = await openDb()
  if (!db) return
  try {
    const tx = db.transaction('seen', 'readwrite')
    tx.objectStore('seen').clear()
    await txDone(tx)
  } catch (e) {
    console.debug('seenPosts: clear failed', e)
  }
}
