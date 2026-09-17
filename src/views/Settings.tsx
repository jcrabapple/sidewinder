/**
 * Settings: theme, media defaults, posting defaults, timestamps, and the
 * account section (active account, sign out). Persists via state/settings.ts.
 */
import { settings, updateSettings, type ThemePref, type Settings } from '../state/settings'
import { seenPostsCount, clearSeenPosts } from '../state/seenPosts'
import { keywordMutes, addKeywordMute, removeKeywordMute } from '../state/mutes'
import { pushState, pushAlerts, initPushState, enablePush, disablePush, setPushAlert } from '../state/push'
import { useEffect, useState } from 'preact/hooks'
import { activeSession, sessions, switchAccount, logout } from '../state/auth'
import { Icon } from '../components/Icon'
import { toast } from '../state/toast'
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
  const [muteDraft, setMuteDraft] = useState('')

  useEffect(() => {
    void initPushState()
  }, [])

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
              {seenCount === null ? 'Counting…' : `${seenCount.toLocaleString()} posts cached (about ${seenCount === 0 ? 0 : Math.max(1, Math.round((seenCount * 700) / 1e6))} MB)`}
            </span>
            <button class="btn" onClick={() => void handleClearSeen()}>
              Clear history
            </button>
          </div>
        )}
      </section>

      <section class="settings__section card" aria-labelledby="settings-mutes">
        <h2 id="settings-mutes">Keyword mutes</h2>
        <p class="help-text">
          Hide posts matching a phrase (matched against post text and content warnings). Entries
          starting with <code>@</code> mute an account. Mutes are private to this browser and never
          apply to notifications.
        </p>
        <div class="settings__mute-add">
          <input
            class="input"
            value={muteDraft}
            placeholder="Add a phrase or @account"
            aria-label="New keyword mute"
            onInput={(e) => setMuteDraft((e.target as HTMLInputElement).value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && addKeywordMute(muteDraft)) setMuteDraft('')
            }}
          />
          <button
            class="btn"
            onClick={() => {
              if (addKeywordMute(muteDraft)) setMuteDraft('')
            }}
          >
            Add
          </button>
        </div>
        {keywordMutes.value.length === 0 ? (
          <p class="help-text">No mutes yet.</p>
        ) : (
          <ul class="settings__mute-list">
            {keywordMutes.value.map((m) => (
              <li key={m.id} class="settings__mute-row">
                <span>{m.phrase}</span>
                <button
                  class="icon-btn"
                  aria-label={`Remove mute ${m.phrase}`}
                  onClick={() => removeKeywordMute(m.id)}
                >
                  <Icon name="x" size={16} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section class="settings__section card" aria-labelledby="settings-push">
        <h2 id="settings-push">Push notifications</h2>
        {pushState.value === 'checking' && <p class="help-text">Checking push support…</p>}
        {pushState.value === 'unsupported' && (
          <p class="help-text">
            Push isn't available here: it needs an installed app (or HTTPS site), a browser with
            push support, and an instance with notifications enabled.
          </p>
        )}
        {pushState.value === 'denied' && (
          <p class="help-text" role="alert">
            Notification permission was denied for this site. Re-enable it in your browser's site
            settings, then reload.
          </p>
        )}
        {pushState.value === 'unsubscribed' && (
          <>
            <p class="help-text">
              Get notified of mentions and other activity even when Sidewinder is closed. Handled
              entirely between your browser and your instance — no third-party service.
            </p>
            <button
              class="btn btn--primary"
              onClick={() => {
                void enablePush()
                  .then(() => toast('Push notifications enabled'))
                  .catch((e: unknown) => {
                    toast(e instanceof Error ? e.message : 'Could not enable push.', 'error')
                    void initPushState()
                  })
              }}
            >
              Enable push notifications
            </button>
          </>
        )}
        {pushState.value === 'subscribed' && (
          <>
            <p class="help-text">
              Subscribed. Pick what reaches you — tap a notification to open it in Sidewinder.
            </p>
            <div class="settings__push-alerts" role="group" aria-label="Push notification types">
              <Toggle label="Mentions" checked={pushAlerts.value.mention} onChange={(v) => setPushAlert({ mention: v })} />
              <Toggle label="New followers" checked={pushAlerts.value.follow} onChange={(v) => setPushAlert({ follow: v })} />
              <Toggle label="Boosts" checked={pushAlerts.value.reblog} onChange={(v) => setPushAlert({ reblog: v })} />
              <Toggle label="Favourites" checked={pushAlerts.value.favourite} onChange={(v) => setPushAlert({ favourite: v })} />
              <Toggle label="Polls you voted in" checked={pushAlerts.value.poll} onChange={(v) => setPushAlert({ poll: v })} />
              <Toggle label="Edited posts" checked={pushAlerts.value.update} onChange={(v) => setPushAlert({ update: v })} />
            </div>
            <button class="btn btn--danger" onClick={() => void disablePush()}>
              Disable push notifications
            </button>
          </>
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
