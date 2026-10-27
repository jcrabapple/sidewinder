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
import { getSeenTop, setSeenTop, isValidStatusId } from './readState'
import { Api } from '../api/client'

export type MarkerTimeline = 'home' | 'notifications'

interface Marker {
  last_read_id: string
  updated_at: string
  version: string
}

type MarkersResponse = Partial<Record<MarkerTimeline, Marker>>
type MarkerBody = Partial<Record<MarkerTimeline, { last_read_id: string }>>

/** readState timeline key for a server marker timeline. Home matches the
 * TimelinePage key ('home::'); notifications has no local pill yet but the
 * mark is stored so adoption stays symmetric. */
function localKey(t: MarkerTimeline): string {
  return t === 'home' ? 'home::' : 'notifications'
}

function sessionId(): string {
  return activeSession.value?.id ?? ''
}

/** Adopt any server marker newer than the local mark. The session and client
 * are captured by the caller BEFORE the fetch, so a response for account A
 * can never be applied or resent as account B. */
function adopt(session: string, t: MarkerTimeline, marker: Marker | undefined): void {
  if (!marker || !isValidStatusId(marker.last_read_id)) return
  if (marker.last_read_id > getSeenTop(session, localKey(t))) {
    setSeenTop(session, localKey(t), marker.last_read_id)
  }
}

function fetchMarkersWith(client: Api): Promise<MarkersResponse> {
  return client.get<MarkersResponse>('/api/v1/markers', {
    'timeline[]': ['home', 'notifications'],
  })
}

// One in-flight sync per session: App bootstrap and view mounts both call
// this; the guard collapses duplicates without blocking later sessions.
let syncPromise: Promise<void> | null = null
let syncedSession = ''

export function syncMarkersFromServer(): Promise<void> {
  const session = sessionId()
  if (!session) return Promise.resolve()
  if (syncPromise && syncedSession === session) return syncPromise
  syncedSession = session
  syncPromise = (async () => {
    const client = api.value
    try {
      const markers = await fetchMarkersWith(client)
      adopt(session, 'home', markers.home)
      adopt(session, 'notifications', markers.notifications)
    } catch (e) {
      console.debug('markers: sync failed', e)
    } finally {
      if (syncedSession === session) syncPromise = null
    }
  })()
  return syncPromise
}

const DEBOUNCE_MS = 3000

export interface PendingMarker {
  id: string
  session: string
  base: string
  token: string
  timeline: MarkerTimeline
}

interface PendingEntry extends PendingMarker {
  key: string
  timer: ReturnType<typeof setTimeout> | null
}

export interface MarkerFlush {
  base: string
  token: string
  body: MarkerBody
}

export function pendingMarkerKey(session: string, timeline: MarkerTimeline): string {
  return JSON.stringify([session, timeline])
}

/** Select the highest id for each session/timeline and group timelines into
 * one keepalive request per captured account credential. */
export function selectMarkerFlushes(values: Iterable<PendingMarker>): MarkerFlush[] {
  const highest = new Map<string, PendingMarker>()
  for (const value of values) {
    const key = pendingMarkerKey(value.session, value.timeline)
    const current = highest.get(key)
    if (!current || value.id > current.id) highest.set(key, value)
  }
  const grouped = new Map<string, MarkerFlush>()
  for (const value of highest.values()) {
    const credentialKey = JSON.stringify([value.session, value.base, value.token])
    let flush = grouped.get(credentialKey)
    if (!flush) {
      flush = { base: value.base, token: value.token, body: {} }
      grouped.set(credentialKey, flush)
    }
    flush.body[value.timeline] = { last_read_id: value.id }
  }
  return [...grouped.values()]
}

// Pending entries remain selectable until their normal POST finishes, so a
// pagehide immediately after the debounce can still flush them with keepalive.
const pending = new Map<string, PendingEntry>()

export function queueMarkerPush(timeline: MarkerTimeline, lastReadId: string): void {
  const active = activeSession.value
  if (!active) return
  const session = active.id
  const key = pendingMarkerKey(session, timeline)
  const current = pending.get(key)
  if (current && lastReadId <= current.id) return
  clearTimeout(current?.timer ?? undefined)
  const entry: PendingEntry = {
    id: lastReadId,
    session,
    base: active.base,
    token: active.token,
    timeline,
    key,
    timer: null,
  }
  entry.timer = setTimeout(() => {
    void pushMarker(entry).finally(() => {
      if (pending.get(key) === entry) pending.delete(key)
    })
  }, DEBOUNCE_MS)
  pending.set(key, entry)
}

async function pushMarker(entry: PendingEntry): Promise<void> {
  const client = new Api(entry.base)
  client.token = entry.token
  try {
    await client.post<MarkersResponse>('/api/v1/markers', {
      [entry.timeline]: { last_read_id: entry.id },
    })
  } catch (e) {
    console.debug('markers: push failed', e)
    try {
      const markers = await fetchMarkersWith(client)
      adopt(entry.session, entry.timeline, markers[entry.timeline])
    } catch (e2) {
      console.debug('markers: reconcile failed', e2)
    }
  }
}

/** The page may freeze before a normal fetch finishes. keepalive preserves
 * the highest queued markers using the account captured when they were read. */
function flushPendingMarkers(): void {
  const flushes = selectMarkerFlushes(pending.values())
  for (const entry of pending.values()) clearTimeout(entry.timer ?? undefined)
  pending.clear()
  for (const flush of flushes) {
    void fetch(`${flush.base.replace(/\/+$/, '')}/api/v1/markers`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${flush.token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(flush.body),
      keepalive: true,
    }).catch((e) => console.debug('markers: pagehide flush failed', e))
  }
}

if (typeof window !== 'undefined') window.addEventListener('pagehide', flushPendingMarkers)
