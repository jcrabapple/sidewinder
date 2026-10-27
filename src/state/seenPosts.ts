/**
 * Seen-post history: a bounded, client-side searchable cache of statuses
 * that have streamed through the timelines. GoToSocial deliberately has no
 * federation-wide full-text search (its status search only covers your own
 * posts and replies to you), so this answers "I saw a post about X
 * recently" from the corpus the user actually saw.
 *
 * Hard cap of MAX_POSTS per session with oldest-first eviction (ULID ids are
 * time-ordered, so primary-key order IS chronology). Text only, no media,
 * no HTML, which keeps 10k posts around 7 MB. Off by default: nothing is
 * written unless settings.seenHistory is on. Everything lives in this
 * browser's IndexedDB and each account clears its own history.
 */
import { activeSession } from './auth'
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

interface StoredSeenPost extends SeenPost {
  key: string
  session: string
}

const STORE = 'seenBySession'
const LEGACY_STORE = 'seen'

export function seenRecordKey(session: string, id: string): string {
  return JSON.stringify([session, id])
}

/** Assign legacy or newly seen records to exactly one account. */
export function scopeSeenPost(post: SeenPost, session: string): StoredSeenPost {
  return { ...post, session, key: seenRecordKey(session, post.id) }
}

let dbPromise: Promise<IDBDatabase | null> | null = null
let sessionGeneration = 0

/** Invalidate reads that were started before an account change. */
export function resetSeenPosts(): void {
  sessionGeneration++
}

function openDb(session: string): Promise<IDBDatabase | null> {
  dbPromise ??= new Promise((resolve) => {
    try {
      const req = indexedDB.open('sidewinder', 2)
      req.onupgradeneeded = () => {
        const db = req.result
        if (!db.objectStoreNames.contains(STORE)) {
          const store = db.createObjectStore(STORE, { keyPath: 'key' })
          store.createIndex('session', 'session')
        }
        // Version 1 had one global store. Claim its contents for the active
        // account during the upgrade, then remove the unscoped store.
        if (db.objectStoreNames.contains(LEGACY_STORE) && req.transaction) {
          const scoped = req.transaction.objectStore(STORE)
          const legacy = req.transaction.objectStore(LEGACY_STORE)
          const cursor = legacy.openCursor()
          cursor.onsuccess = () => {
            const item = cursor.result
            if (item) {
              scoped.put(scopeSeenPost(item.value as SeenPost, session))
              item.continue()
            } else {
              db.deleteObjectStore(LEGACY_STORE)
            }
          }
        }
      }
      req.onsuccess = () => {
        const db = req.result
        db.onversionchange = () => db.close()
        resolve(db)
      }
      req.onerror = () => resolve(null)
      // An older tab may still hold the v1 connection. Seen history is
      // optional, so degrade instead of leaving Search and writes pending.
      req.onblocked = () => resolve(null)
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
 * underlying post (a boost only re-confirms you saw the original). */
export async function recordSeenPosts(statuses: Status[]): Promise<void> {
  const session = activeSession.value?.id
  const generation = sessionGeneration
  if (!session || !settings.value.seenHistory || statuses.length === 0) return
  const db = await openDb(session)
  if (!db || generation !== sessionGeneration || activeSession.value?.id !== session) return
  try {
    const records = new Map<string, StoredSeenPost>()
    for (const s of statuses) {
      const inner = s.reblog ?? s
      if (!records.has(inner.id)) records.set(inner.id, scopeSeenPost(toRecord(inner), session))
    }
    const tx = db.transaction(STORE, 'readwrite')
    const store = tx.objectStore(STORE)
    for (const r of records.values()) store.put(r)
    await txDone(tx)
    await evict(db, session)
  } catch (e) {
    console.debug('seenPosts: record failed', e)
  }
}

async function countFor(db: IDBDatabase, session: string): Promise<number> {
  try {
    return await new Promise<number>((resolve, reject) => {
      const req = db.transaction(STORE).objectStore(STORE).index('session').count(IDBKeyRange.only(session))
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => reject(req.error)
    })
  } catch {
    return 0
  }
}

async function evict(db: IDBDatabase, session: string): Promise<void> {
  const excess = (await countFor(db, session)) - MAX_POSTS
  if (excess <= 0) return
  const tx = db.transaction(STORE, 'readwrite')
  const index = tx.objectStore(STORE).index('session')
  await new Promise<void>((resolve, reject) => {
    let left = excess
    const req = index.openCursor(IDBKeyRange.only(session))
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
  const session = activeSession.value?.id
  const generation = sessionGeneration
  if (!session) return []
  const tokens = query.toLowerCase().split(/\s+/).filter(Boolean)
  if (tokens.length === 0) return []
  const db = await openDb(session)
  if (!db || generation !== sessionGeneration || activeSession.value?.id !== session) return []
  try {
    const all = await new Promise<StoredSeenPost[]>((resolve, reject) => {
      const req = db.transaction(STORE).objectStore(STORE).index('session').getAll(IDBKeyRange.only(session))
      req.onsuccess = () => resolve(req.result as StoredSeenPost[])
      req.onerror = () => reject(req.error)
    })
    if (generation !== sessionGeneration || activeSession.value?.id !== session) return []
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
  const session = activeSession.value?.id
  const generation = sessionGeneration
  if (!session) return 0
  const db = await openDb(session)
  if (!db || generation !== sessionGeneration || activeSession.value?.id !== session) return 0
  const count = await countFor(db, session)
  return generation === sessionGeneration && activeSession.value?.id === session ? count : 0
}

export async function clearSeenPosts(): Promise<void> {
  const session = activeSession.value?.id
  const generation = sessionGeneration
  if (!session) return
  const db = await openDb(session)
  if (!db || generation !== sessionGeneration || activeSession.value?.id !== session) return
  try {
    const tx = db.transaction(STORE, 'readwrite')
    const index = tx.objectStore(STORE).index('session')
    await new Promise<void>((resolve, reject) => {
      const req = index.openCursor(IDBKeyRange.only(session))
      req.onsuccess = () => {
        const cursor = req.result
        if (!cursor) return resolve()
        cursor.delete()
        cursor.continue()
      }
      req.onerror = () => reject(req.error)
    })
    await txDone(tx)
  } catch (e) {
    console.debug('seenPosts: clear failed', e)
  }
}
