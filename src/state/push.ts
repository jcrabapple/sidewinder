/**
 * Web push notifications (GoToSocial/Mastodon push API).
 *
 * Flow: user opts in from Settings → we request Notification permission,
 * subscribe the service worker with the instance's VAPID public key (from
 * /api/v2/instance), and register the subscription server-side with the
 * chosen alert set. Alert preferences are mirrored in localStorage so the
 * Settings UI can render them before/without a server round-trip.
 *
 * Everything here is best-effort: failures toast but never break the app.
 */
import { signal } from '@preact/signals'
import { api } from './auth'
import { toast } from './toast'

export interface PushAlerts {
  mention: boolean
  follow: boolean
  favourite: boolean
  reblog: boolean
  poll: boolean
  update: boolean
}

const ALERTS_KEY = 'sidewinder.push.alerts'
const DEFAULTS: PushAlerts = {
  mention: true,
  follow: true,
  favourite: false,
  reblog: true,
  poll: true,
  update: false,
}

function loadAlerts(): PushAlerts {
  try {
    return { ...DEFAULTS, ...(JSON.parse(localStorage.getItem(ALERTS_KEY) ?? '{}') as Partial<PushAlerts>) }
  } catch {
    return { ...DEFAULTS }
  }
}

export const pushAlerts = signal<PushAlerts>(loadAlerts())
export const pushState = signal<'checking' | 'unsupported' | 'denied' | 'unsubscribed' | 'subscribed'>('checking')

function persistAlerts() {
  try {
    localStorage.setItem(ALERTS_KEY, JSON.stringify(pushAlerts.value))
  } catch {
    // Blocked storage: alert prefs stay session-local.
  }
}

function supported(): boolean {
  return (
    typeof window !== 'undefined' &&
    'serviceWorker' in navigator &&
    'PushManager' in window &&
    window.isSecureContext
  )
}

function urlBase64ToUint8Array(base64: string): Uint8Array {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4)
  const b64 = (base64 + padding).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(b64)
  const out = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
  return out
}

async function swRegistration(): Promise<ServiceWorkerRegistration | null> {
  try {
    return (await navigator.serviceWorker.ready) ?? null
  } catch {
    return null
  }
}

/** Determine current push state. Call once at Settings mount. */
export async function initPushState(): Promise<void> {
  if (!supported()) {
    pushState.value = 'unsupported'
    return
  }
  if (Notification.permission === 'denied') {
    pushState.value = 'denied'
    return
  }
  const reg = await swRegistration()
  if (!reg) {
    pushState.value = 'unsupported'
    return
  }
  const existing = await reg.pushManager.getSubscription()
  pushState.value = existing ? 'subscribed' : 'unsubscribed'
}

interface InstanceVapid {
  configuration?: { vapid?: { public_key?: string } }
}

async function vapidKey(): Promise<string> {
  const inst = await api.value.get<InstanceVapid>('/api/v2/instance')
  const key = inst.configuration?.vapid?.public_key
  if (!key) throw new Error('This instance has not enabled push notifications.')
  return key
}

function alertsBody(): Record<string, unknown> {
  const a = pushAlerts.value
  return {
    subscription: {
      endpoint: 'PLACEHOLDER',
    },
    data: {
      alerts: {
        mention: a.mention,
        follow: a.follow,
        favourite: a.favourite,
        reblog: a.reblog,
        poll: a.poll,
        update: a.update,
      },
      policy: 'all',
    },
  }
}

/** Request permission + subscribe + register with the instance. */
export async function enablePush(): Promise<void> {
  if (!supported()) return
  const permission = await Notification.requestPermission()
  if (permission !== 'granted') {
    pushState.value = permission === 'denied' ? 'denied' : 'unsubscribed'
    return
  }
  const reg = await swRegistration()
  if (!reg) return
  const key = await vapidKey()
  const sub = await reg.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: urlBase64ToUint8Array(key).buffer as ArrayBuffer,
  })
  const json = sub.toJSON()
  const endpoint = json.endpoint as string
  const keys = json.keys as { p256dh: string; auth: string }
  const body = alertsBody()
  body.subscription = { endpoint, keys }
  await api.value.post('/api/v1/push/subscription', body)
  pushState.value = 'subscribed'
}

/** Re-send the current alert set (PUT after a toggle change). */
export async function syncPushAlerts(): Promise<void> {
  if (pushState.value !== 'subscribed') return
  const body = alertsBody()
  delete (body.subscription as { endpoint?: string }).endpoint
  const existing = (await (await swRegistration())?.pushManager.getSubscription())?.toJSON()
  if (!existing) return
  body.subscription = existing
  try {
    await api.value.request('/api/v1/push/subscription', { method: 'PUT', body })
  } catch (e) {
    toast('Could not update notification settings.', 'error')
    console.debug('push: alert sync failed', e)
  }
}

export function setPushAlert(patch: Partial<PushAlerts>): void {
  pushAlerts.value = { ...pushAlerts.value, ...patch }
  persistAlerts()
  void syncPushAlerts()
}

/** Unsubscribe locally and server-side. */
export async function disablePush(): Promise<void> {
  const reg = await swRegistration()
  const sub = await reg?.pushManager.getSubscription()
  try {
    if (sub) {
      await api.value.request('/api/v1/push/subscription', { method: 'DELETE' })
      await sub.unsubscribe()
    }
  } catch (e) {
    console.debug('push: unsubscribe failed', e)
  } finally {
    pushState.value = 'unsubscribed'
  }
}
