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
import { activeSession, api, sessions } from './auth'
import type { Api } from '../api/client'
import { toast } from './toast'

export interface PushAlerts {
  mention: boolean
  follow: boolean
  follow_request: boolean
  favourite: boolean
  reblog: boolean
  poll: boolean
  status: boolean
  update: boolean
  admin_sign_up: boolean
  admin_report: boolean
}

const LEGACY_ALERTS_KEY = 'sidewinder.push.alerts'
const alertsKey = (session: string) => `sidewinder.push.alerts.${session}`
const enabledKey = (session: string) => `sidewinder.push.enabled.${session}`
type PushStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
const DEFAULTS: PushAlerts = {
  mention: true,
  follow: true,
  follow_request: true,
  favourite: false,
  reblog: true,
  poll: true,
  status: false,
  update: false,
  admin_sign_up: false,
  admin_report: false,
}

/** API field name for a UI alert key (admin ones use dots server-side). */
function apiField(k: keyof PushAlerts): string {
  return k === 'admin_sign_up' ? 'admin.sign_up' : k === 'admin_report' ? 'admin.report' : k
}

function fromApiField(k: string): keyof PushAlerts | null {
  return k === 'admin.sign_up' ? 'admin_sign_up' : k === 'admin.report' ? 'admin_report' : (k as keyof PushAlerts)
}

/** Legacy global preferences are claimed once by the active account. In
 * particular, admin alert preferences must never become every account's. */
export function loadPushAlerts(session: string | null, storage: PushStorage = localStorage): PushAlerts {
  if (!session) return { ...DEFAULTS }
  try {
    const scoped = storage.getItem(alertsKey(session))
    if (scoped !== null) {
      try {
        storage.removeItem(LEGACY_ALERTS_KEY)
      } catch {
        // A later load can retry cleanup; the scoped value is authoritative.
      }
      return { ...DEFAULTS, ...(JSON.parse(scoped) as Partial<PushAlerts>) }
    }
    const legacy = storage.getItem(LEGACY_ALERTS_KEY)
    if (legacy === null) return { ...DEFAULTS }
    const migrated = { ...DEFAULTS, ...(JSON.parse(legacy) as Partial<PushAlerts>) }
    storage.setItem(alertsKey(session), JSON.stringify(migrated))
    try {
      storage.removeItem(LEGACY_ALERTS_KEY)
    } catch {
      // The scoped copy prevents another account from claiming the legacy value.
    }
    return migrated
  } catch {
    return { ...DEFAULTS }
  }
}

export const pushAlerts = signal<PushAlerts>(loadPushAlerts(activeSession.value?.id ?? null))
export const pushState = signal<'checking' | 'unsupported' | 'denied' | 'unsubscribed' | 'subscribed'>('checking')

function persistAlerts(session: string, alerts: PushAlerts) {
  try {
    localStorage.setItem(alertsKey(session), JSON.stringify(alerts))
  } catch {
    // Blocked storage: alert prefs stay session-local.
  }
}

function pushEnabled(session: string): boolean {
  try {
    return localStorage.getItem(enabledKey(session)) === '1'
  } catch {
    return false
  }
}

function hasPushPreference(session: string): boolean {
  try {
    return localStorage.getItem(enabledKey(session)) !== null
  } catch {
    return false
  }
}

function setPushEnabled(session: string, enabled: boolean): void {
  try {
    localStorage.setItem(enabledKey(session), enabled ? '1' : '0')
  } catch {
    // The next server reconciliation will recover the actual state.
  }
}

/** True only when no other signed-in account still uses the shared browser
 * subscription. */
export function shouldUnsubscribeBrowser(enabledSessions: string[], disabledSession: string): boolean {
  return !enabledSessions.some((id) => id !== disabledSession)
}

function supported(): boolean {
  return (
    typeof window !== 'undefined' &&
    'serviceWorker' in navigator &&
    'PushManager' in window &&
    typeof Notification !== 'undefined' &&
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

function sessionIsActive(session: string): boolean {
  return activeSession.value?.id === session
}

/** Reload account-owned state without allowing an older async reconciliation
 * to overwrite the newly active account. */
export function reloadPushState(): void {
  pushAlerts.value = loadPushAlerts(activeSession.value?.id ?? null)
  pushState.value = 'checking'
  void initPushState().catch((e) => console.debug('push: state reload failed', e))
}

/** Determine current push state. Call once at Settings mount. */
export async function initPushState(): Promise<void> {
  const session = activeSession.value?.id
  const client = api.value
  if (!session) {
    pushState.value = 'unsubscribed'
    return
  }
  if (!supported()) {
    pushState.value = 'unsupported'
    return
  }
  if (Notification.permission === 'denied') {
    pushState.value = 'denied'
    return
  }
  const reg = await swRegistration()
  if (!sessionIsActive(session)) return
  if (!reg) {
    pushState.value = 'unsupported'
    return
  }
  const existing = await reg.pushManager.getSubscription()
  if (!sessionIsActive(session)) return
  if (!existing) {
    pushState.value = 'unsubscribed'
    return
  }

  // Before enabled state was scoped, one browser subscription represented
  // the active account. Claim that legacy state once, never once per account.
  if (!hasPushPreference(session) && !sessions.value.some((s) => hasPushPreference(s.id))) {
    setPushEnabled(session, true)
  }
  const enabled = pushEnabled(session)
  pushState.value = enabled ? 'subscribed' : 'unsubscribed'
  if (!enabled) return

  // Reconcile only into the account whose captured client made the request.
  const localAlerts = pushAlerts.value
  try {
    const sub = await client.get<ServerSubscription>('/api/v1/push/subscription')
    if (!sessionIsActive(session)) return
    const serverAlerts = sub.data?.alerts
    if (!serverAlerts) return
    let drifted = false
    const merged = { ...localAlerts }
    for (const [k, v] of Object.entries(serverAlerts)) {
      const key = fromApiField(k)
      if (key && key in merged && typeof v === 'boolean') {
        merged[key] = v
        if (v !== localAlerts[key]) drifted = true
      }
    }
    if (drifted) {
      pushAlerts.value = merged
      persistAlerts(session, merged)
    }
  } catch (e) {
    console.debug('push: alert reconcile failed', e)
  }
}

interface ServerSubscription {
  data?: { alerts?: Record<string, unknown> }
}

interface InstanceVapid {
  configuration?: { vapid?: { public_key?: string } }
}

async function vapidKey(client: Api): Promise<string> {
  const inst = await client.get<InstanceVapid>('/api/v2/instance')
  const key = inst.configuration?.vapid?.public_key
  if (!key) throw new Error('This instance has not enabled push notifications.')
  return key
}

function alertsBody(a: PushAlerts): Record<string, unknown> {
  const alerts: Record<string, boolean> = {}
  for (const k of Object.keys(a) as (keyof PushAlerts)[]) {
    alerts[apiField(k)] = a[k]
  }
  return {
    data: {
      alerts,
      policy: 'all',
    },
  }
}

/** Request permission + subscribe + register with the instance. */
export async function enablePush(): Promise<void> {
  const session = activeSession.value?.id
  const client = api.value
  if (!session || !supported()) return
  const permission = await Notification.requestPermission()
  if (!sessionIsActive(session)) return
  if (permission !== 'granted') {
    pushState.value = permission === 'denied' ? 'denied' : 'unsubscribed'
    return
  }
  const reg = await swRegistration()
  if (!sessionIsActive(session)) return
  if (!reg) return
  const key = await vapidKey(client)
  if (!sessionIsActive(session)) return
  const sub = await reg.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: urlBase64ToUint8Array(key).buffer as ArrayBuffer,
  })
  if (!sessionIsActive(session)) return
  const json = sub.toJSON()
  const endpoint = json.endpoint as string
  const keys = json.keys as { p256dh: string; auth: string }
  const body = alertsBody(pushAlerts.value)
  body.subscription = { endpoint, keys }
  await client.post('/api/v1/push/subscription', body)
  setPushEnabled(session, true)
  if (sessionIsActive(session)) pushState.value = 'subscribed'
}

/** Re-send the current alert set (PUT after a toggle change). */
export async function syncPushAlerts(): Promise<void> {
  const session = activeSession.value?.id
  const client = api.value
  if (!session || pushState.value !== 'subscribed') return
  const body = alertsBody(pushAlerts.value)
  const reg = await swRegistration()
  if (!sessionIsActive(session)) return
  const subscription = await reg?.pushManager.getSubscription()
  if (!sessionIsActive(session)) return
  const existing = subscription?.toJSON()
  if (!existing) return
  body.subscription = existing
  try {
    await client.request('/api/v1/push/subscription', { method: 'PUT', body })
  } catch (e) {
    toast('Could not update notification settings.', 'error')
    console.debug('push: alert sync failed', e)
  }
}

/** Show a local test notification via the service worker registration. */
export async function testNotification(): Promise<boolean> {
  if (!supported() || typeof Notification === 'undefined' || Notification.permission !== 'granted') {
    return false
  }
  try {
    const reg = await swRegistration()
    if (!reg) return false
    await reg.showNotification('Sidewinder test', {
      body: 'Notifications are working. This is what a mention will look like.',
      icon: '/favicon.png',
      badge: '/favicon.png',
      tag: 'sidewinder-test',
    })
    return true
  } catch (e) {
    console.debug('push: test notification failed', e)
    return false
  }
}

export function setPushAlert(patch: Partial<PushAlerts>): void {
  const session = activeSession.value?.id
  if (!session) return
  pushAlerts.value = { ...pushAlerts.value, ...patch }
  persistAlerts(session, pushAlerts.value)
  void syncPushAlerts()
}

/** Delete only this account's server subscription. The browser subscription
 * is shared, so it stays alive while any other signed-in account needs it. */
export async function disablePush(): Promise<void> {
  const session = activeSession.value?.id
  const client = api.value
  if (!session) return
  const reg = await swRegistration()
  const sub = await reg?.pushManager.getSubscription()
  try {
    if (pushEnabled(session)) {
      await client.request('/api/v1/push/subscription', { method: 'DELETE' })
    }
    setPushEnabled(session, false)
    const enabledSessions = sessions.value.filter((s) => pushEnabled(s.id)).map((s) => s.id)
    if (sub && shouldUnsubscribeBrowser(enabledSessions, session)) await sub.unsubscribe()
    if (sessionIsActive(session)) pushState.value = 'unsubscribed'
  } catch (e) {
    toast('Could not disable push notifications for this account.', 'error')
    console.debug('push: unsubscribe failed', e)
  }
}
