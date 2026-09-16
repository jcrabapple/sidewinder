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
  const client = new Api(s?.base ?? defaultBase())
  client.token = s?.token ?? null
  return client
})

/** The default server suggested on the login screen (same origin policy as GTS). */
export function defaultBase(): string {
  return 'https://gts.example.org'
}

export function upsertSession(sess: Session) {
  const list = sessions.value.filter((s) => s.id !== sess.id)
  sessions.value = [...list, sess]
  activeId.value = sess.id
  persist(sessions.value)
}

export function switchAccount(id: string) {
  activeId.value = id
  persist(sessions.value)
}

export function removeSession(id: string) {
  sessions.value = sessions.value.filter((s) => s.id !== id)
  if (activeId.value === id) activeId.value = sessions.value[0]?.id ?? null
  persist(sessions.value)
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
  removeSession(id ?? activeId.value ?? '')
}

/** Escape hatch for tests / debugging. */
export function resetSessions() {
  sessions.value = []
  activeId.value = null
  persist([])
}
