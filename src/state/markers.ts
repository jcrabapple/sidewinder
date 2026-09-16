/**
 * Server read-marker sync (GoToSocial /api/v1/markers): makes the local
 * readState high-water mark follow the user across devices. On sync we
 * ADOPT a newer server marker into localStorage (so posts read elsewhere
 * don't re-trigger the jump pill); on timeline loads we PUSH the raised
 * mark back, trailing-debounced so bursts collapse to one POST.
 *
 * Everything here is best-effort: failures are swallowed (console.debug)
 * and never block the UI. Marker ids are ULIDs, so string compare orders
 * them chronologically.
 */
import { api, activeSession } from './auth'
import { getSeenTop, setSeenTop } from './readState'
import type { Api } from '../api/client'

export type MarkerTimeline = 'home' | 'notifications'

interface Marker {
  last_read_id: string
  updated_at: string
  version: string
}

type MarkersResponse = Partial<Record<MarkerTimeline, Marker>>

/** readState timeline key for a server marker timeline. Home matches the
 *  TimelinePage key ('home::'); notifications has no local pill yet but the
 *  mark is stored so adoption stays symmetric. */
function localKey(t: MarkerTimeline): string {
  return t === 'home' ? 'home::' : 'notifications'
}

function sessionId(): string {
  return activeSession.value?.id ?? ''
}

/** Adopt any server marker newer than the local mark. `session` and
 *  `client` are captured by the caller BEFORE the fetch: if the user
 *  switches accounts mid-flight, markers fetched for the old account must
 *  never be written into the new account's read state. */
function adopt(session: string, client: Api, t: MarkerTimeline, marker: Marker | undefined): void {
  if (!marker) return
  if (marker.last_read_id > getSeenTop(session, localKey(t))) {
    setSeenTop(session, localKey(t), marker.last_read_id)
  }
}

function fetchMarkersWith(client: Api): Promise<MarkersResponse> {
  return client.get<MarkersResponse>('/api/v1/markers', {
    'timeline[]': ['home', 'notifications'],
  })
}

// One in-flight sync per session — App bootstrap and view mounts both call
// this; the guard collapses duplicates without blocking later sessions.
let syncPromise: Promise<void> | null = null
let syncedSession = ''

export function syncMarkersFromServer(): Promise<void> {
  const session = sessionId()
  if (!session) return Promise.resolve()
  if (syncPromise && syncedSession === session) return syncPromise
  syncedSession = session
  syncPromise = (async () => {
    // Capture BOTH the session and its api client up front: a session
    // switch mid-fetch must not write account A's markers into account B.
    const client = api.value
    try {
      const markers = await fetchMarkersWith(client)
      adopt(session, client, 'home', markers.home)
      adopt(session, client, 'notifications', markers.notifications)
    } catch (e) {
      console.debug('markers: sync failed', e)
    } finally {
      // Allow later callers to re-sync (retry after a transient failure)
      // instead of caching a resolved promise for the app's lifetime.
      if (syncedSession === session) syncPromise = null
    }
  })()
  return syncPromise
}

// Trailing debounce, 3s per SESSION+timeline; only the highest queued id is
// sent. Keyed by session so two accounts' pending pushes never collide.
const DEBOUNCE_MS = 3000
const pending = new Map<string, { id: string; session: string; timer: ReturnType<typeof setTimeout> }>()

export function queueMarkerPush(timeline: MarkerTimeline, lastReadId: string): void {
  const session = sessionId()
  if (!session) return
  const key = `${session}:${timeline}`
  const cur = pending.get(key)
  if (cur && lastReadId <= cur.id) return
  if (cur) clearTimeout(cur.timer)
  const timer = setTimeout(() => {
    pending.delete(key)
    // The queued id belongs to the session that queued it: if the user
    // switched accounts during the debounce window, drop it — pushing it
    // would write account A's marker into account B.
    if (sessionId() !== session) return
    void pushMarker(session, timeline, lastReadId)
  }, DEBOUNCE_MS)
  pending.set(key, { id: lastReadId, session, timer })
}

async function pushMarker(session: string, timeline: MarkerTimeline, lastReadId: string): Promise<void> {
  // Same session-guard as above, at push time.
  if (sessionId() !== session) return
  const client = api.value
  try {
    await client.post<MarkersResponse>('/api/v1/markers', {
      [timeline]: { last_read_id: lastReadId },
    })
  } catch (e) {
    // 409 = concurrent writer raced us; re-fetch to reconcile and move on.
    console.debug('markers: push failed', e)
    try {
      const markers = await fetchMarkersWith(client)
      adopt(session, client, timeline, markers[timeline])
    } catch (e2) {
      console.debug('markers: reconcile failed', e2)
    }
  }
}
