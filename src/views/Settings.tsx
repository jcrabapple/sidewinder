/**
 * Settings: theme, media defaults, posting defaults, timestamps, and the
 * account section (active account, sign out). Persists via state/settings.ts.
 */
import { settings, updateSettings, type ThemePref, type Settings } from '../state/settings'
import { seenPostsCount, clearSeenPosts } from '../state/seenPosts'
import { useEffect, useState } from 'preact/hooks'
import { activeSession, sessions, switchAccount, logout } from '../state/auth'
import { Icon } from '../components/Icon'
import { useTitle, navigate } from '../router'
import './Settings.css'

const THEME_OPTIONS: { value: ThemePref; label: string }[] = [
  { value: 'system', label: 'System' },
  { value: 'dark', label: 'Dark' },
  { value: 'light', label: 'Light' },
]

const VISIBILITY_OPTIONS: { value: Settings['defaultVisibility']; label: string }[] = [
  { value: 'public', label: 'Public' },
  { value: 'unlisted', label: 'Unlisted' },
  { value: 'private', label: 'Followers only' },
  { value: 'direct', label: 'Direct' },
]

export function SettingsView() {
  useTitle('Settings')
  const s = settings.value
  const session = activeSession.value
  const [seenCount, setSeenCount] = useState<number | null>(null)

  // Refresh the cache size readout whenever the toggle flips on.
  useEffect(() => {
    if (!s.seenHistory) {
      setSeenCount(null)
      return
    }
    void seenPostsCount().then(setSeenCount)
  }, [s.seenHistory])

  async function handleClearSeen() {
    await clearSeenPosts()
    setSeenCount(0)
  }

  return (
    <div class="settings">
      <header class="view-header">
        <h1>Settings</h1>
      </header>

      <section class="settings__section card" aria-labelledby="settings-appearance">
        <h2 id="settings-appearance">Appearance</h2>
        <fieldset class="settings__field">
          <legend class="label">Theme</legend>
          <div class="settings__segmented" role="radiogroup" aria-label="Theme">
            {THEME_OPTIONS.map((opt) => (
              <button
                key={opt.value}
                role="radio"
                aria-checked={s.theme === opt.value}
                class={`settings__segment${s.theme === opt.value ? ' is-active' : ''}`}
                onClick={() => updateSettings({ theme: opt.value })}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </fieldset>
        <Toggle
          label="Relative timestamps"
          help="Show “3m” / “2h” instead of full dates."
          checked={s.relativeTimes}
          onChange={(v) => updateSettings({ relativeTimes: v })}
        />
      </section>

      <section class="settings__section card" aria-labelledby="settings-media">
        <h2 id="settings-media">Media</h2>
        <Toggle
          label="Require alt text"
          help="Warn before posting media without a description."
          checked={s.requireAltText}
          onChange={(v) => updateSettings({ requireAltText: v })}
        />
        <Toggle
          label="Show alt badge"
          help="Mark media that has a text description."
          checked={s.altBadge}
          onChange={(v) => updateSettings({ altBadge: v })}
        />
        <Toggle
          label="Autoplay GIFs and videos"
          help="Off means animated media waits for a click."
          checked={s.autoplayGifs}
          onChange={(v) => updateSettings({ autoplayGifs: v })}
        />
      </section>

      <section class="settings__section card" aria-labelledby="settings-posting">
        <h2 id="settings-posting">Posting</h2>
        <label class="label" for="settings-visibility">Default post visibility</label>
        <select
          id="settings-visibility"
          class="select"
          value={s.defaultVisibility}
          onChange={(e) =>
            updateSettings({ defaultVisibility: (e.target as HTMLSelectElement).value as Settings['defaultVisibility'] })
          }
        >
          {VISIBILITY_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </select>
        <p class="help-text">New posts start with this audience. You can change it per post.</p>
      </section>

      <section class="settings__section card" aria-labelledby="settings-history">
        <h2 id="settings-history">Seen-post history</h2>
        <Toggle
          label="Keep a searchable history of seen posts"
          help="Stores the text of posts that appear in your timelines (up to 10,000, oldest removed first) so you can search them from the Seen tab in Search. Stored only in this browser; the server never sees it."
          checked={s.seenHistory}
          onChange={(v) => updateSettings({ seenHistory: v })}
        />
        {s.seenHistory && (
          <div class="settings__history-meta">
            <span class="help-text" role="status">
              {seenCount === null ? 'Counting…' : `${seenCount.toLocaleString()} posts cached (about ${Math.max(1, Math.round((seenCount * 700) / 1e6))} MB)`}
            </span>
            <button class="btn" onClick={() => void handleClearSeen()}>
              Clear history
            </button>
          </div>
        )}
      </section>

      {session && (
        <section class="settings__section card" aria-labelledby="settings-account">
          <h2 id="settings-account">Account</h2>
          <div class="settings__account">
            <img
              class="settings__avatar"
              src={session.account.avatar_static}
              alt=""
              width={44}
              height={44}
            />
            <div class="settings__account-names">
              <span class="name">{session.account.display_name || session.account.username}</span>
              <span class="handle">@{session.account.acct}</span>
            </div>
          </div>

          {sessions.value.length > 1 && (
            <>
              <h3 class="settings__subhead">Switch account</h3>
              <ul class="settings__sessions">
                {sessions.value.map((sess) => (
                  <li key={sess.id}>
                    <button
                      class="settings__session"
                      aria-current={sess.id === session.id ? 'true' : undefined}
                      onClick={() => switchAccount(sess.id)}
                    >
                      <img src={sess.account.avatar_static} alt="" width={28} height={28} />
                      <span>@{sess.account.acct}</span>
                      {sess.id === session.id && <Icon name="check" size={16} />}
                    </button>
                  </li>
                ))}
              </ul>
            </>
          )}

          <div class="settings__account-actions">
            <button
              class="btn btn--danger"
              onClick={() => {
                if (window.confirm(`Sign out of @${session.account.acct}?`)) {
                  logout()
                  navigate('/', { replace: true })
                }
              }}
            >
              <Icon name="logout" size={16} /> Sign out
            </button>
          </div>
        </section>
      )}

      <section class="settings__section card" aria-labelledby="settings-about">
        <h2 id="settings-about">About</h2>
        <p class="settings__about">
          Sidewinder is a web client for GoToSocial and Mastodon-compatible servers.
          {session && (
            <>
              {' '}Connected to <a href={session.base} target="_blank" rel="noopener">{session.base.replace(/^https?:\/\//, '')}</a>.
            </>
          )}
        </p>
      </section>
    </div>
  )
}

function Toggle({
  label,
  help,
  checked,
  onChange,
}: {
  label: string
  help?: string
  checked: boolean
  onChange: (v: boolean) => void
}) {
  return (
    <div class="settings__toggle">
      <div class="settings__toggle-text">
        <span class="settings__toggle-label">{label}</span>
        {help && <span class="help-text">{help}</span>}
      </div>
      <button
        class="settings__switch"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        onClick={() => onChange(!checked)}
      >
        <span class="settings__switch-thumb" aria-hidden="true" />
      </button>
    </div>
  )
}
