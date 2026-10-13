/**
 * Profile view: account header (banner, avatar, bio, fields, counts),
 * relationship actions (follow / mute / block) for other accounts, profile
 * editing for your own, and a paginated status timeline with tabs.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import type { Account, AccountCredentials, AccountField, Status } from '../api/types'
import { api, activeSession, sessions, upsertSession } from '../state/auth'
import { recordFollowerSnapshot, followerSnapshots, engagement, median } from '../state/insights'
import { toast } from '../state/toast'
import { StatusCard, replaceEmojis } from '../components/StatusCard'
import { InsightsPanel } from '../components/Insights'
import { Icon } from '../components/Icon'
import { sanitize } from '../util/sanitize'
import { formatCount, formatAbsoluteShort } from '../util/time'
import { useTitle } from '../router'
import {
  relationshipFor,
  fetchRelationship,
  followAccount,
  unfollowAccount,
  blockAccount,
  unblockAccount,
  muteAccount,
  unmuteAccount,
  updateAccountNote,
} from '../state/relationships'
import { isHiddenBooster, toggleHiddenBooster } from '../state/hideBoosts'
import './Profile.css'

type LoadState = 'loading' | 'error' | 'ready'
type Tab = 'posts' | 'replies' | 'media'

export function ProfileView({ id }: { id: string }) {
  const [account, setAccount] = useState<Account | null>(null)
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('posts')
  const [statuses, setStatuses] = useState<Status[]>([])
  const [nextMaxId, setNextMaxId] = useState<string | null>(null)
  const [listState, setListState] = useState<LoadState>('loading')
  const [loadingMore, setLoadingMore] = useState(false)
  const [editing, setEditing] = useState(false)
  const requestSeq = useRef(0)

  const me = activeSession.value?.account.id
  const own = account !== null && account.id === me
  useTitle(account ? (account.display_name || account.username) : 'Profile')

  async function load() {
    const seq = ++requestSeq.current
    setState('loading')
    setError(null)
    try {
      const acct = await api.value.get<Account>(`/api/v1/accounts/${encodeURIComponent(id)}`)
      if (seq !== requestSeq.current) return
      setAccount(acct)
      setState('ready')
      if (acct.id === me) recordFollowerSnapshot(acct.followers_count ?? 0)
      else void fetchRelationship(acct.id)
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : 'The profile could not be loaded.')
      setState('error')
    }
  }

  async function loadStatuses(reset: boolean, activeTab: Tab) {
    const seq = ++requestSeq.current
    if (reset) {
      setListState('loading')
      setStatuses([])
      setNextMaxId(null)
    } else {
      setLoadingMore(true)
    }
    try {
      const [pinnedPosts, page] = await Promise.all([
        reset && activeTab === 'posts'
          ? api.value
              .get<Status[]>(`/api/v1/accounts/${encodeURIComponent(id)}/statuses`, { pinned: true })
              .catch(() => [])
          : Promise.resolve([]),
        api.value.page<Status>(`/api/v1/accounts/${encodeURIComponent(id)}/statuses`, {
          query: {
            exclude_replies: activeTab === 'posts' ? true : undefined,
            only_media: activeTab === 'media' ? true : undefined,
            limit: 20,
            max_id: reset ? undefined : (nextMaxId ?? undefined),
          },
        }),
      ])
      if (seq !== requestSeq.current) return
      const pinnedList = (pinnedPosts || []).map((p) => ({ ...p, pinned: true }))
      const pinnedIds = new Set(pinnedList.map((p) => p.id))
      const freshItems = [...pinnedList, ...page.items.filter((item) => !pinnedIds.has(item.id))]
      setStatuses((cur) => (reset ? freshItems : [...cur, ...page.items.filter((item) => !pinnedIds.has(item.id))]))
      setNextMaxId(page.nextMaxId)
      setListState('ready')
    } catch (e) {
      if (seq !== requestSeq.current) return
      if (reset) setListState('error')
      else toast(e instanceof Error ? e.message : 'Could not load more posts.', 'error')
    } finally {
      if (seq === requestSeq.current) setLoadingMore(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  useEffect(() => {
    if (state === 'ready') void loadStatuses(true, tab)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, tab, state])

  function onStatusUpdated(s: Status) {
    setStatuses((cur) => cur.map((it) => (it.id === s.id ? s : it)))
  }
  function onStatusDeleted(deletedId: string) {
    setStatuses((cur) => cur.filter((it) => it.id !== deletedId))
  }

  if (state === 'loading') {
    return (
      <div class="state-block" role="status" aria-busy="true">
        <span class="spinner" aria-hidden="true" />
        <p>Loading profile…</p>
      </div>
    )
  }
  if (state === 'error' || !account) {
    return (
      <div class="state-block" role="alert">
        <h2>Profile unavailable</h2>
        <p>{error ?? 'The profile could not be loaded.'}</p>
        <button class="btn" onClick={() => void load()}>Try again</button>
      </div>
    )
  }

  const rel = relationshipFor(account.id)
  const name = account.display_name || account.username

  return (
    <div class="profile rise-in">
      <div class="profile__banner">
        {account.header && <img class="profile__header" src={account.header_static || account.header} alt="" loading="lazy" />}
        <img class="profile__avatar" src={account.avatar_static || account.avatar} alt="" width={80} height={80} />
      </div>

      <div class="profile__actions">
        {own ? (
          <button class="btn" onClick={() => setEditing(true)}>
            <Icon name="pencil" size={16} /> Edit profile
          </button>
        ) : (
          <RelationshipActions id={account.id} acct={account.acct} />
        )}
      </div>

      <div class="profile__identity">
        <h1 class="profile__name">{replaceEmojis(name, account.emojis)}</h1>
        <p class="profile__handle">
          @{account.acct}
          {account.bot && <span class="profile__badge">Bot</span>}
          {account.locked && (
            <span class="profile__badge">
              <Icon name="lock" size={12} /> Locked
            </span>
          )}
          {rel?.followed_by && <span class="profile__badge">Follows you</span>}
        </p>
      </div>

      {!own && rel && (
        <PrivateNote id={account.id} note={rel.note ?? ''} />
      )}

      {account.note && sanitize(account.note, api.value.base).trim() !== '' && (
        <div
          class="profile__bio status-content"
          // Sanitized with DOMPurify before render.
          dangerouslySetInnerHTML={{ __html: sanitize(account.note, api.value.base) }}
        />
      )}

      {account.fields && account.fields.length > 0 && (
        <dl class="profile__fields">
          {account.fields.map((f) => (
            <div class="profile__field" key={f.name}>
              <dt>{f.name}</dt>
              <dd
                class="status-content"
                // Sanitized with DOMPurify before render.
                dangerouslySetInnerHTML={{ __html: sanitize(f.value, api.value.base) }}
              />
            </div>
          ))}
        </dl>
      )}

      <p class="profile__meta">
        <Icon name="clock" size={14} /> Joined {formatAbsoluteShort(account.created_at)}
      </p>

      <div class="profile__counts">
        <span><strong class="num">{formatCount(account.statuses_count)}</strong> posts</span>
        <a class="profile__count-link" href={`#/accounts/${account.id}/following`}>
          <strong class="num">{formatCount(account.following_count)}</strong> following
        </a>
        <a class="profile__count-link" href={`#/accounts/${account.id}/followers`}>
          <strong class="num">{formatCount(account.followers_count)}</strong> followers
        </a>
      </div>

      {own && <InsightsPanel accountId={account.id} />}

      <div class="profile__tabs" role="tablist" aria-label="Profile timelines">
        {(['posts', 'replies', 'media'] as Tab[]).map((t) => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            class={`profile__tab${tab === t ? ' profile__tab--active' : ''}`}
            onClick={() => setTab(t)}
          >
            {t === 'posts' ? 'Posts' : t === 'replies' ? 'Posts and replies' : 'Media'}
          </button>
        ))}
      </div>

      {listState === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <span class="spinner" aria-hidden="true" />
          <p>Loading posts…</p>
        </div>
      )}
      {listState === 'error' && (
        <div class="state-block" role="alert">
          <h2>Posts unavailable</h2>
          <p>The posts could not be loaded.</p>
          <button class="btn" onClick={() => void loadStatuses(true, tab)}>Try again</button>
        </div>
      )}
      {listState === 'ready' && statuses.length === 0 && (
        <div class="state-block">
          <h2>Nothing here yet</h2>
          <p>{tab === 'media' ? 'This account has not posted any media.' : 'This account has not posted yet.'}</p>
        </div>
      )}
      {statuses.map((s) => (
        <StatusCard key={s.id} status={s} onStatusUpdated={onStatusUpdated} onStatusDeleted={onStatusDeleted} />
      ))}
      {listState === 'ready' && nextMaxId && (
        <button class="btn profile__more" disabled={loadingMore} onClick={() => void loadStatuses(false, tab)}>
          {loadingMore ? 'Loading…' : 'Load more'}
        </button>
      )}

      {editing && own && (
        <EditProfileDialog
          account={account}
          onClose={() => setEditing(false)}
          onSaved={(acct) => {
            setAccount(acct)
            setEditing(false)
          }}
        />
      )}
    </div>
  )
}

function RelationshipActions({ id, acct }: { id: string; acct: string }) {
  const rel = relationshipFor(id)
  const [busy, setBusy] = useState(false)
  const boostsHidden = isHiddenBooster(id)

  if (!rel) {
    return <span class="profile__rel-loading" role="status"><span class="spinner" aria-hidden="true" /></span>
  }

  async function run(action: (id: string) => Promise<unknown>) {
    setBusy(true)
    await action(id)
    setBusy(false)
  }

  if (rel.blocking) {
    return (
      <button class="btn btn--danger" disabled={busy} onClick={() => void run(unblockAccount)}>
        Unblock @{acct}
      </button>
    )
  }
  if (rel.blocked_by) {
    return <p class="profile__blocked-note">You are blocked by this account.</p>
  }

  return (
    <>
      <button
        class={`btn${rel.following || rel.requested ? '' : ' btn--primary'}`}
        disabled={busy}
        aria-pressed={rel.following}
        onClick={() => void run(rel.following ? unfollowAccount : followAccount)}
      >
        <Icon name={rel.following ? 'user-minus' : 'user-plus'} size={16} />
        {rel.following ? 'Following' : rel.requested ? 'Requested' : 'Follow'}
      </button>
      <button
        class="icon-btn"
        aria-label={rel.muting ? 'Unmute this account' : 'Mute this account'}
        title={rel.muting ? 'Unmute' : 'Mute'}
        aria-pressed={rel.muting}
        disabled={busy}
        onClick={() => void run(rel.muting ? unmuteAccount : muteAccount)}
      >
        <Icon name="volume-x" size={18} />
      </button>
      <button
        class="icon-btn profile__boost-hide-btn"
        aria-label={boostsHidden ? `Show boosts from @${acct}` : `Hide boosts from @${acct}`}
        title={boostsHidden ? 'Boosts hidden' : 'Hide boosts'}
        aria-pressed={boostsHidden}
        onClick={() => {
          const next = !boostsHidden
          toggleHiddenBooster(id, next)
          toast(next ? `Boosts from @${acct} are hidden` : `Boosts from @${acct} are visible again`)
        }}
      >
        <Icon name="repeat" size={18} />
      </button>
      <button
        class="icon-btn profile__block-btn"
        aria-label="Block this account"
        title="Block"
        disabled={busy}
        onClick={() => void run(blockAccount)}
      >
        <Icon name="shield" size={18} />
      </button>
    </>
  )
}

function PrivateNote({ id, note }: { id: string; note: string }) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(note)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    setDraft(note)
  }, [note])

  async function save(e: Event) {
    e.preventDefault()
    if (saving) return
    setSaving(true)
    const trimmed = draft.trim()
    const res = await updateAccountNote(id, trimmed)
    setSaving(false)
    if (res) {
      setEditing(false)
      toast(trimmed ? 'Private note saved' : 'Private note cleared')
    }
  }

  if (!editing) {
    return (
      <div class="profile__note-bar">
        {note ? (
          <div class="profile__note card">
            <div class="profile__note-head">
              <span class="profile__note-label">
                <Icon name="lock" size={12} /> Private note
              </span>
              <button
                class="icon-btn profile__note-edit"
                aria-label="Edit private note"
                title="Edit private note"
                onClick={() => setEditing(true)}
              >
                <Icon name="pencil" size={14} />
              </button>
            </div>
            <p class="profile__note-text">{note}</p>
          </div>
        ) : (
          <button class="btn profile__note-add" onClick={() => setEditing(true)}>
            <Icon name="pencil" size={14} /> Add private note
          </button>
        )}
      </div>
    )
  }

  return (
    <form class="profile__note-form card" onSubmit={(e) => void save(e)}>
      <label class="label" for={`private-note-${id}`}>
        Private note (only visible to you)
      </label>
      <textarea
        id={`private-note-${id}`}
        class="textarea"
        rows={2}
        maxLength={2000}
        value={draft}
        placeholder="Add context about this account"
        onInput={(e) => setDraft((e.target as HTMLTextAreaElement).value)}
      />
      <div class="profile__note-actions">
        <button class="btn btn--primary" type="submit" disabled={saving}>
          {saving ? 'Saving…' : 'Save note'}
        </button>
        <button
          class="btn"
          type="button"
          disabled={saving}
          onClick={() => {
            setDraft(note)
            setEditing(false)
          }}
        >
          Cancel
        </button>
      </div>
    </form>
  )
}

interface EditDialogProps {
  account: Account
  onClose: () => void
  onSaved: (a: Account) => void
}

function EditProfileDialog({ account, onClose, onSaved }: EditDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const creds = activeSession.value?.account
  const source = creds?.id === account.id ? creds.source : null

  const [displayName, setDisplayName] = useState(source ? (account.display_name || account.username) : (account.display_name || account.username))
  const [note, setNote] = useState(source?.note ?? '')
  const [locked, setLocked] = useState(account.locked)
  const [bot, setBot] = useState(Boolean(account.bot))
  const [discoverable, setDiscoverable] = useState(account.discoverable)
  const [fields, setFields] = useState<AccountField[]>(
    (source?.fields ?? account.fields ?? []).map((f) => ({ name: f.name, value: stripTags(f.value) })),
  )
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const dlg = dialogRef.current
    const prev = document.activeElement
    if (dlg && !dlg.open) dlg.showModal()
    return () => { if (prev instanceof HTMLElement) prev.focus() }
  }, [])

  function close() {
    dialogRef.current?.close()
  }

  async function save(e: Event) {
    e.preventDefault()
    setSaving(true)
    setError(null)
    try {
      const body: Record<string, unknown> = {
        display_name: displayName,
        note,
        locked,
        bot,
        discoverable,
      }
      fields.forEach((f, i) => {
        body[`fields_attributes[${i}][name]`] = f.name
        body[`fields_attributes[${i}][value]`] = f.value
      })
      const updated = await api.value.patch<AccountCredentials>('/api/v1/accounts/update_credentials', body)
      // Keep the stored session snapshot fresh so the rest of the app sees it.
      const sess = sessions.value.find((s) => s.id === activeSession.value?.id)
      if (sess) upsertSession({ ...sess, account: updated })
      toast('Profile saved.')
      onSaved(updated)
      close()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'The profile could not be saved.')
    } finally {
      setSaving(false)
    }
  }

  function setField(i: number, key: 'name' | 'value', value: string) {
    setFields((cur) => cur.map((f, idx) => (idx === i ? { ...f, [key]: value } : f)))
  }

  return (
    <dialog
      ref={dialogRef}
      class="sw-dialog profile__edit-dialog"
      aria-labelledby="edit-profile-title"
      onClose={onClose}
      onClick={(e) => { if (e.target === dialogRef.current) close() }}
    >
      <form onSubmit={save}>
        <div class="sw-dialog__head">
          <h2 id="edit-profile-title">Edit profile</h2>
          <button type="button" class="icon-btn" aria-label="Close" title="Close" onClick={close} style="margin-left:auto">
            <Icon name="x" size={18} />
          </button>
        </div>
        <div class="sw-dialog__body">
          {error && <p class="error-note" role="alert">{error}</p>}

          <label class="label" for="ep-name">Display name</label>
          <input
            id="ep-name"
            class="input"
            value={displayName}
            maxLength={60}
            onInput={(e) => setDisplayName((e.target as HTMLInputElement).value)}
          />

          <label class="label" for="ep-note">Bio</label>
          <textarea
            id="ep-note"
            class="textarea"
            rows={4}
            value={note}
            maxLength={500}
            onInput={(e) => setNote((e.target as HTMLTextAreaElement).value)}
          />
          <p class="help-text num">{note.length} / 500</p>

          {fields.map((f, i) => (
            <div class="profile__edit-field" key={i}>
              <div>
                <label class="label" for={`ep-fname-${i}`}>Label</label>
                <input
                  id={`ep-fname-${i}`}
                  class="input"
                  value={f.name}
                  maxLength={80}
                  onInput={(e) => setField(i, 'name', (e.target as HTMLInputElement).value)}
                />
              </div>
              <div>
                <label class="label" for={`ep-fval-${i}`}>Content</label>
                <input
                  id={`ep-fval-${i}`}
                  class="input"
                  value={f.value}
                  maxLength={255}
                  onInput={(e) => setField(i, 'value', (e.target as HTMLInputElement).value)}
                />
              </div>
              <button
                type="button"
                class="icon-btn"
                aria-label={`Remove field ${f.name || i + 1}`}
                title={`Remove field ${f.name || i + 1}`}
                onClick={() => setFields((cur) => cur.filter((_, idx) => idx !== i))}
              >
                <Icon name="trash" size={16} />
              </button>
            </div>
          ))}
          {fields.length < 4 && (
            <button
              type="button"
              class="btn btn--ghost"
              onClick={() => setFields((cur) => [...cur, { name: '', value: '' }])}
            >
              <Icon name="plus" size={16} /> Add field
            </button>
          )}

          <label class="profile__check">
            <input type="checkbox" checked={locked} onChange={(e) => setLocked((e.target as HTMLInputElement).checked)} />
            Lock account — manually approve followers
          </label>
          <label class="profile__check">
            <input type="checkbox" checked={bot} onChange={(e) => setBot((e.target as HTMLInputElement).checked)} />
            This is a bot account
          </label>
          <label class="profile__check">
            <input type="checkbox" checked={discoverable} onChange={(e) => setDiscoverable((e.target as HTMLInputElement).checked)} />
            Show this account in the directory
          </label>

          <div class="profile__edit-actions">
            <button type="button" class="btn" onClick={close} disabled={saving}>Cancel</button>
            <button type="submit" class="btn btn--primary" disabled={saving}>
              {saving ? 'Saving…' : 'Save profile'}
            </button>
          </div>
        </div>
      </form>
    </dialog>
  )
}

function stripTags(html: string): string {
  const el = document.createElement('template')
  el.innerHTML = html
  return el.content.textContent ?? ''
}
