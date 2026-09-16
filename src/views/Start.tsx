/**
 * Login / start view. Signs the user in via the server's own authorization
 * page (there is no password grant to script); this app registers itself as
 * an OAuth client per server, once, and remembers the credentials.
 */
import { useState } from 'preact/hooks'
import { getOrCreateClient, authorizeUrl, loginErrorMessage, exchangeCode } from '../api/oauth'
import { defaultBase, upsertSession, sessionIdFor, fetchAccount, activeSession } from '../state/auth'
import { Api } from '../api/client'
import type { Instance } from '../api/types'
import { navigate } from '../router'
import { Icon } from '../components/Icon'
import './Start.css'

export async function consumeOAuthReturn(): Promise<void> {
  const params = new URLSearchParams(location.search)
  const code = params.get('code')
  const state = params.get('state')
  if (!code || !state) return
  // Clean the URL first so a refresh doesn't retry a consumed code.
  history.replaceState(null, '', location.pathname + location.hash)
  const { token, base } = await exchangeCode(code, state)
  const account = await fetchAccount(base, token)
  upsertSession({ id: sessionIdFor(base, account.id), base, token, account })
  navigate('/')
}

export function StartView() {
  const [server, setServer] = useState(defaultBase())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (activeSession.value) return null

  async function start(e: Event) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      const base = normalizeServer(server)
      const api = new Api(base)
      const instance = await api.get<Instance>('/api/v1/instance')
      const creds = await getOrCreateClient(base)
      location.assign(authorizeUrl(base, creds, instance))
    } catch (err) {
      setError(loginErrorMessage(err))
      setBusy(false)
    }
  }

  return (
    <div class="start">
      <div class="start__inner">
        <header class="start__brand">
          <img src="/favicon.png" alt="" width="56" height="56" />
          <div>
            <h1 class="start__title">Sidewinder</h1>
            <p class="start__tag">A client for your corner of the fediverse.</p>
          </div>
        </header>

        <form class="card start__form" onSubmit={start}>
          <div class="start__field">
            <label class="label" for="server">Server address</label>
            <input
              id="server"
              class="input"
              type="text"
              inputMode="url"
              autoComplete="url"
              spellcheck={false}
              placeholder="social.example.com"
              value={server}
              onInput={(e) => setServer((e.target as HTMLInputElement).value)}
              required
            />
            <p class="help-text">Your GoToSocial or Mastodon server, e.g. gts.example.org</p>
          </div>

          {error && (
            <p class="field-error" role="alert">
              <Icon name="alert" size={16} /> {error}
            </p>
          )}

          <button class="btn btn--primary start__go" type="submit" disabled={busy}>
            {busy ? 'Contacting server…' : 'Sign in'}
            {!busy && <Icon name="chevron-right" size={18} />}
          </button>
        </form>

        <p class="start__note">
          Sidewinder never sees your password: signing in happens on your
          server's own page, and only an access token comes back here.
          Revoke it any time from your server's settings.
        </p>
      </div>
    </div>
  )
}

export function normalizeServer(input: string): string {
  let s = input.trim()
  if (!/^https?:\/\//i.test(s)) s = 'https://' + s
  try {
    const u = new URL(s)
    return u.origin
  } catch {
    throw new Error(`"${input}" is not a valid server address.`)
  }
}
