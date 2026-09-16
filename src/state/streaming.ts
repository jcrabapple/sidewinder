/**
 * WebSocket streaming client for the Mastodon/GTS streaming API.
 * One multiplexed connection per token: GTS supports stream= query param per
 * connection, so we open one socket per subscribed stream (simple, cheap on
 * a single-user instance).
 *
 * GTS endpoint: wss://<host>/api/v1/streaming?access_token=<token>&stream=<name>
 * Stream names: user, public, public:local, hashtag&tag=x, list&list=x
 */
import { activeSession } from './auth'
import type { Status, Notification } from '../api/types'

export type StreamName = 'user' | 'public' | 'public:local' | `hashtag:${string}` | `list:${string}`

interface Handler {
  update?: (payload: unknown) => void
  notification?: (payload: unknown) => void
  delete?: (id: string) => void
}

const connections = new Map<string, { ws: WebSocket; handlers: Set<Handler>; retry: number }>()

function streamQuery(stream: StreamName): string {
  if (stream.startsWith('hashtag:')) return `hashtag&tag=${encodeURIComponent(stream.slice(8))}`
  if (stream.startsWith('list:')) return `list&list=${encodeURIComponent(stream.slice(5))}`
  return stream
}

function wssUrl(stream: StreamName, token: string): string {
  const sess = activeSession.value
  if (!sess) throw new Error('Cannot stream without a session')
  const base = new URL(sess.base)
  const scheme = base.protocol === 'https:' ? 'wss:' : 'ws:'
  const ws = new URL(`${scheme}//${base.host}/api/v1/streaming`)
  ws.searchParams.set('access_token', token)
  // GTS takes stream (and tag/list) as query params; the 'stream=' value can
  // carry '&tag=x' style suffixes per Mastodon's URL scheme.
  const q = streamQuery(stream)
  const [name, rest] = q.split('&')
  ws.searchParams.set('stream', name)
  if (rest) {
    const [k, v] = rest.split('=')
    ws.searchParams.set(k, v)
  }
  return ws.toString()
}

/** Subscribe to a stream; returns an unsubscribe function. */
export function subscribe(stream: StreamName, handler: Handler): () => void {
  const key = streamKey(stream)
  let conn = connections.get(key)
  if (!conn) {
    conn = { ws: connect(stream), handlers: new Set(), retry: 1000 }
    connections.set(key, conn)
  }
  conn.handlers.add(handler)

  return () => {
    const c = connections.get(key)
    if (!c) return
    c.handlers.delete(handler)
    if (c.handlers.size === 0) {
      c.ws.close()
      connections.delete(key)
    }
  }
}

function streamKey(stream: StreamName): string {
  return `${activeSession.value?.id ?? 'anon'}#${stream}`
}

function connect(stream: StreamName): WebSocket {
  const sess = activeSession.value
  if (!sess) throw new Error('Cannot stream without a session')
  const ws = new WebSocket(wssUrl(stream, sess.token))
  const key = streamKey(stream)

  ws.onmessage = (ev) => {
    let msg: { event: string; payload: string }
    try {
      msg = JSON.parse(ev.data)
    } catch {
      return
    }
    const conn = connections.get(key)
    if (!conn) return
    let payload: unknown = null
    try {
      payload = JSON.parse(msg.payload)
    } catch {
      payload = msg.payload
    }
    switch (msg.event) {
      case 'update':
        conn.handlers.forEach((h) => h.update?.(payload))
        break
      case 'notification':
        conn.handlers.forEach((h) => h.notification?.(payload))
        break
      case 'delete':
        conn.handlers.forEach((h) => h.delete?.(payload as string))
        break
    }
  }

  ws.onclose = () => {
    const conn = connections.get(key)
    if (!conn || conn.handlers.size === 0) return
    // Reconnect with capped exponential backoff.
    const delay = Math.min(conn.retry, 30000)
    conn.retry = Math.min(conn.retry * 2, 30000)
    setTimeout(() => {
      if (!connections.has(key) || connections.get(key) !== conn) return
      conn.ws = connect(stream)
    }, delay)
  }

  ws.onopen = () => {
    const conn = connections.get(key)
    if (conn) conn.retry = 1000
  }

  return ws
}

export type StreamStatusHandler = (status: Status) => void
export type StreamNotificationHandler = (notification: Notification) => void

/** Convenience: live statuses for a timeline stream. */
export function onStatus(stream: StreamName, cb: StreamStatusHandler): () => void {
  return subscribe(stream, { update: (p) => cb(p as Status) })
}

/** Convenience: live notifications. */
export function onNotification(cb: StreamNotificationHandler): () => void {
  return subscribe('user', { notification: (p) => cb(p as Notification) })
}

/** Close every socket (used on account switch/logout). */
export function closeAllStreams() {
  connections.forEach((c) => c.ws.close())
  connections.clear()
}
