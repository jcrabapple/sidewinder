/**
 * OAuth authorization-code flow against a Mastodon-compatible server.
 * GoToSocial has no password grant; the user authorizes in their browser on
 * the instance itself. The SPA registers itself dynamically per instance and
 * stores client credentials locally.
 */
import { Api, postForm, ApiError } from './client'
import type { Instance, TokenResponse } from './types'

export const SCOPES = 'read write follow push'
export const ADMIN_SCOPES = 'read write follow push admin'

export interface ClientCredentials {
  client_id: string
  client_secret: string
}

const STORE_KEY = 'sidewinder.clients'

function loadClients(): Record<string, ClientCredentials> {
  try {
    return JSON.parse(localStorage.getItem(STORE_KEY) ?? '{}')
  } catch {
    return {}
  }
}

function saveClients(clients: Record<string, ClientCredentials>) {
  localStorage.setItem(STORE_KEY, JSON.stringify(clients))
}

/** Apps are keyed per base+scope pair: admin tools use a separate
 *  registration so ordinary sessions never hold an admin-scoped token. */
function clientKey(base: string, scopes: string): string {
  return `${base}::${scopes}`
}

export async function getOrCreateClient(base: string, scopes: string = SCOPES): Promise<ClientCredentials> {
  const clients = loadClients()
  const key = clientKey(base, scopes)
  const existing = clients[key]
  if (existing) {
    // Sanity: try to use it; if the app row was wiped server-side, re-register.
    try {
      await new Api(base).post('/api/v1/apps/verify_credentials', existing)
      return existing
    } catch {
      // fall through to re-registration
    }
  }
  // GTS 0.22.x types redirect_uris as a single string (Mastodon accepts both).
  const created = await new Api(base).post<ClientCredentials>('/api/v1/apps', {
    client_name: 'Sidewinder',
    redirect_uris: location.origin + '/',
    scopes,
    website: location.origin,
  })
  clients[key] = { client_id: created.client_id, client_secret: created.client_secret }
  saveClients(clients)
  return clients[key]
}

export function authorizeUrl(
  base: string,
  creds: ClientCredentials,
  instance?: Instance,
  scopes: string = SCOPES,
): string {
  const state = crypto.randomUUID()
  sessionStorage.setItem(
    'sidewinder.oauth_state',
    JSON.stringify({ state, base, clientKey: clientKey(base, scopes), at: Date.now() }),
  )
  const u = new URL('/oauth/authorize', base)
  u.searchParams.set('response_type', 'code')
  u.searchParams.set('client_id', creds.client_id)
  u.searchParams.set('redirect_uri', location.origin + '/')
  u.searchParams.set('scope', scopes)
  u.searchParams.set('state', state)
  if (instance?.uri) u.searchParams.set('instance', instance.uri)
  return u.toString()
}

/** Consume ?code=...&state=... after the redirect back to the SPA origin. */
export async function exchangeCode(
  code: string,
  state: string,
): Promise<{ token: string; base: string; scopes: string }> {
  const raw = sessionStorage.getItem('sidewinder.oauth_state')
  sessionStorage.removeItem('sidewinder.oauth_state')
  if (!raw) throw new ApiError(400, 'No sign-in attempt in progress (state missing). Start again.')
  const saved = JSON.parse(raw) as { state: string; base: string; clientKey?: string; at: number }
  if (state !== saved.state) throw new ApiError(400, 'Sign-in state mismatch. Start again.')
  if (Date.now() - saved.at > 10 * 60 * 1000) throw new ApiError(400, 'Sign-in attempt expired. Start again.')
  const creds = loadClients()[saved.clientKey ?? saved.base]
  if (!creds) throw new ApiError(400, 'Client registration missing. Start again.')
  const tok = await postForm<TokenResponse>(saved.base, '/oauth/token', {
    grant_type: 'authorization_code',
    code,
    redirect_uri: location.origin + '/',
    client_id: creds.client_id,
    client_secret: creds.client_secret,
    scope: saved.clientKey ? saved.clientKey.split('::')[1] : SCOPES,
  })
  return { token: tok.access_token, base: saved.base, scopes: tok.scope }
}

/** Highest-value error to show the user during login. */
export function loginErrorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.message
  if (e instanceof TypeError) return 'Could not reach the server. Check the address and your connection.'
  if (e instanceof Error) return e.message
  return String(e)
}
