/**
 * Resolve admin.sign_up notifications against the current admin account state.
 * Handled accounts are cached, while pending accounts and lookup failures are
 * retried on later loads so a transient answer never becomes permanent.
 */
import { ApiError, type Api } from '../api/client'
import type { Notification } from '../api/types'
import type { AdminAccount } from './admin'
import { activeSession, api } from './auth'

export type SignupLookupResult =
  | { status: 'pending' }
  | { status: 'handled' }
  | { status: 'error'; error: Error }

export type SignupLookup = (accountId: string) => Promise<SignupLookupResult>

export interface SignupNotificationFilterResult {
  items: Notification[]
  error: Error | null
}

const handled = new Set<string>()
const inFlight = new Map<string, Promise<SignupLookupResult>>()
const generations = new Map<string, number>()

function cacheKey(sessionId: string, accountId: string): string {
  return JSON.stringify([sessionId, accountId])
}

function generation(key: string): number {
  return generations.get(key) ?? 0
}

/**
 * Build a lookup bound to one captured API client and session. A 404 and an
 * approved or confirmed account are handled; every other failure stays
 * visible and returns an error so the caller can report it.
 */
export function createSignupLookup(client: Pick<Api, 'get'>, sessionId: string): SignupLookup {
  return (accountId) => {
    const key = cacheKey(sessionId, accountId)
    if (handled.has(key)) return Promise.resolve<SignupLookupResult>({ status: 'handled' })
    const existing = inFlight.get(key)
    if (existing) return existing

    const startedAtGeneration = generation(key)
    const request = (async (): Promise<SignupLookupResult> => {
      let result: SignupLookupResult
      try {
        const account = await client.get<AdminAccount>(
          `/api/v1/admin/accounts/${encodeURIComponent(accountId)}`,
        )
        result = account.approved || account.confirmed ? { status: 'handled' } : { status: 'pending' }
      } catch (value) {
        const error = value instanceof Error ? value : new Error(String(value))
        result = error instanceof ApiError && error.status === 404
          ? { status: 'handled' }
          : { status: 'error', error }
      }
      if (result.status === 'handled' && generation(key) === startedAtGeneration) handled.add(key)
      return result
    })()
    inFlight.set(key, request)
    void request.finally(() => {
      if (inFlight.get(key) === request) inFlight.delete(key)
    })
    return request
  }
}

/** Forget a cached answer before retrying a moderation action. */
export function invalidateSignupLookup(accountId: string, sessionId = activeSession.value?.id ?? ''): void {
  if (!sessionId) return
  const key = cacheKey(sessionId, accountId)
  generations.set(key, generation(key) + 1)
  handled.delete(key)
  inFlight.delete(key)
}

/** Cache a successful approve/deny, including approved-but-unconfirmed. */
export function markSignupHandled(accountId: string, sessionId = activeSession.value?.id ?? ''): void {
  if (!sessionId) return
  const key = cacheKey(sessionId, accountId)
  generations.set(key, generation(key) + 1)
  inFlight.delete(key)
  handled.add(key)
}

/** Pure list reconciliation with an injected tri-state lookup. */
export async function resolveSignupNotifications(
  items: Notification[],
  lookup: SignupLookup,
): Promise<SignupNotificationFilterResult> {
  const accountIds = [...new Set(items.flatMap((n) =>
    n.type === 'admin.sign_up' && n.account ? [n.account.id] : [],
  ))]
  const outcomes = new Map<string, SignupLookupResult>()
  await Promise.all(accountIds.map(async (id) => {
    try {
      outcomes.set(id, await lookup(id))
    } catch (value) {
      outcomes.set(id, {
        status: 'error',
        error: value instanceof Error ? value : new Error(String(value)),
      })
    }
  }))

  let error: Error | null = null
  const visible = items.filter((n) => {
    if (n.type !== 'admin.sign_up' || !n.account) return true
    const outcome = outcomes.get(n.account.id) ?? { status: 'pending' as const }
    if (outcome.status === 'error' && !error) error = outcome.error
    return outcome.status !== 'handled'
  })
  return { items: visible, error }
}

/**
 * Session-safe wrapper used by every notification fetch path. Null means the
 * active account changed while lookups were running and the result is stale.
 */
export async function filterSignupNotifications(
  items: Notification[],
  injectedLookup?: SignupLookup,
): Promise<SignupNotificationFilterResult | null> {
  const session = activeSession.value
  if (!session || !(session.scopes ?? '').split(/\s+/).includes('admin')) {
    return { items, error: null }
  }
  const sessionId = session.id
  const lookup = injectedLookup ?? createSignupLookup(api.value, sessionId)
  const result = await resolveSignupNotifications(items, lookup)
  if (activeSession.value?.id !== sessionId) return null
  return result
}
