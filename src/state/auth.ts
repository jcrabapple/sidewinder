/**
 * Sessions: one row per signed-in account, stored in localStorage.
 * A session binds an access token to its server base + account snapshot.
 */
import { signal, computed } from '@preact/signals'
import { Api } from '../api/client'
import type { AccountCredentials } from '../api/types'

export interface Session {
  id: string
  base: string
  token: string
  account: AccountCredentials
  /** OAuth scopes this session's token carries (e.g. admin for moderation). */
  scopes?: string
}

const STORE_KEY = 'sidewinder.sessions'
const ACTIVE_KEY = 'sidewinder.activeSession'

function load(): Session[] {
  try {
    return JSON.parse(localStorage.getItem(STORE_KEY) ?? '[]')
  } catch {
    return []
  }
}

function persist(sessions: Session[]) {
  localStorage.setItem(STORE_KEY, JSON.stringify(sessions))
  localStorage.setItem(ACTIVE_KEY, activeId.value ?? '')
}

export const sessions = signal<Session[]>(load())
const storedActive = localStorage.getItem(ACTIVE_KEY)
export const activeId = signal<string | null>(storedActive || null)

export const activeSession = computed<Session | null>(() => {
  const list = sessions.value
  if (list.length === 0) return null
  return list.find((s) => s.id === activeId.value) ?? list[0]
})

/** Live Api instance bound to the active session's token. */
export const api = computed<Api>(() => {
  const s = activeSession.value
  const client = new Api(s?.base ?? location.origin)
  client.token = s?.token ?? null
  return client
})

export function upsertSession(sess: Session) {
  const list = sessions.value.filter((s) => s.id !== sess.id)
  sessions.value = [...list, sess]
  activeId.value = sess.id
  persist(sessions.value)
  reloadSessionState()
}

export function switchAccount(id: string) {
  activeId.value = id
  persist(sessions.value)
  reloadSessionState()
  void closeStreams()
  void import('./relationships').then((m) => m.clearRelationships()).catch(() => {})
  void import('./hideBoosts').then((m) => m.resetHiddenBoosters()).catch(() => {})
}

export function removeSession(id: string) {
  sessions.value = sessions.value.filter((s) => s.id !== id)
  if (activeId.value === id) activeId.value = sessions.value[0]?.id ?? null
  persist(sessions.value)
  reloadSessionState()
}

export function sessionIdFor(base: string, accountId: string): string {
  return `${base}#${accountId}`
}

export async function fetchAccount(base: string, token: string): Promise<AccountCredentials> {
  const client = new Api(base)
  client.token = token
  return client.get<AccountCredentials>('/api/v1/accounts/verify_credentials')
}

export function logout(id?: string) {
  const target = id ?? activeId.value ?? ''
  void closeStreams()
  void import('./relationships').then((m) => m.clearRelationships()).catch(() => {})
  void import('./hideBoosts').then((m) => m.resetHiddenBoosters()).catch(() => {})
  // Watched import jobs belong to the signed-out session: stop their polling and forget them.
  void import('./importer').then((m) => m.clearJobsFor(target)).catch(() => {})
  removeSession(target)
}

/** Refresh every account-owned cache after the active session changes.
 * Dynamic imports avoid auth's API client becoming a module-cycle dependency. */
function reloadSessionState(): void {
  void import('./push').then((m) => m.reloadPushState()).catch(() => {})
  void import('./mutes').then((m) => m.reloadKeywordMutes()).catch(() => {})
  void import('./seenPosts').then((m) => m.resetSeenPosts()).catch(() => {})
}

/** Tear down live streams; dynamic import avoids the auth↔streaming cycle. */
async function closeStreams() {
  try {
    const { closeAllStreams } = await import('./streaming')
    closeAllStreams()
  } catch {
    // Streams are best-effort cleanup.
  }
}

/** Escape hatch for tests / debugging. */
export function resetSessions() {
  sessions.value = []
  activeId.value = null
  persist([])
  reloadSessionState()
  void closeStreams()
}
