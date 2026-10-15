/**
 * Notifications view: filterable, paginated notification list with live
 * prepends from the user stream. Status-bearing notifications render a
 * compact StatusCard under the actor line.
 */
import { Fragment } from 'preact'
import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import type { Notification, Status } from '../api/types'
import { toast } from '../state/toast'
import { api, activeSession } from '../state/auth'
import { hasAdminScope, approveSignup, denySignup } from '../state/admin'
import { onNotification } from '../state/streaming'
import { refreshFollowRequestCount, markNotificationsRead } from '../state/notifications'
import { syncMarkersFromServer } from '../state/markers'
import { StatusCard, replaceEmojis } from '../components/StatusCard'
import { Icon } from '../components/Icon'
import { useTitle, navigate, route } from '../router'
import { formatRelative, formatAbsolute } from '../util/time'
import './Notifications.css'

type Filter = 'all' | 'mention' | 'follow' | 'reblog' | 'favourite'

const FILTERS: { id: Filter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'mention', label: 'Mentions' },
  { id: 'follow', label: 'Follows' },
  { id: 'reblog', label: 'Boosts' },
  { id: 'favourite', label: 'Favourites' },
]

type LoadState = 'loading' | 'error' | 'ready'

const PAGE_SIZE = 30

function endpoint(filter: Filter, maxId?: string): string {
  const q = new URLSearchParams()
  q.set('limit', String(PAGE_SIZE))
  if (filter !== 'all') q.set('types[]', filter)
  if (maxId) q.set('max_id', maxId)
  return `/api/v1/notifications?${q.toString()}`
}

interface KindInfo {
  icon: string
  text: string
  cls: string
}

function kindInfo(n: Notification): KindInfo {
  switch (n.type) {
    case 'mention':
      return { icon: 'at', text: 'mentioned you', cls: 'notifications__badge--mention' }
    case 'follow':
      return { icon: 'user-plus', text: 'followed you', cls: 'notifications__badge--follow' }
    case 'follow_request':
      return { icon: 'user-plus', text: 'requested to follow you', cls: 'notifications__badge--follow' }
    case 'favourite':
      return { icon: 'heart', text: 'liked your post', cls: 'notifications__badge--fav' }
    case 'reblog':
      return { icon: 'repeat', text: 'boosted your post', cls: 'notifications__badge--boost' }
    case 'poll':
      return { icon: 'poll', text: 'a poll you voted in has ended', cls: '' }
    case 'status':
      return { icon: 'bell', text: 'posted', cls: '' }
    case 'update':
      return { icon: 'pencil', text: 'edited a post you interacted with', cls: '' }
    case 'admin.sign_up':
      return { icon: 'shield', text: 'signed up and is awaiting approval', cls: '' }
    case 'admin.report':
      return { icon: 'alert', text: 'a report was filed', cls: '' }
    default:
      return { icon: 'bell', text: n.type, cls: '' }
  }
}

type ListEntry = { kind: 'single'; notification: Notification } | { kind: 'group'; group: NotifGroup }

interface NotifGroup {
  key: string
  type: 'favourite' | 'reblog'
  status: Status
  /** Newest first. */
  items: Notification[]
}

/** Status id a notification points at, unwrapping any reblog wrapper. */
function targetStatusId(n: Notification): string | null {
  const s = n.status
  if (!s) return null
  return s.reblog ? s.reblog.id : s.id
}

/**
 * Collapse consecutive favourite/reblog notifications for the same status
 * into a single group. Runs over the full rendered list, so groups merge
 * across loadMore() page boundaries and showPending() prepends.
 */
function groupNotifications(items: Notification[]): ListEntry[] {
  const entries: ListEntry[] = []
  let open: NotifGroup | null = null
  const flush = () => {
    if (!open) return
    if (open.items.length === 1) entries.push({ kind: 'single', notification: open.items[0] })
    else entries.push({ kind: 'group', group: open })
    open = null
  }
  for (const n of items) {
    const groupable = (n.type === 'favourite' || n.type === 'reblog') && n.status
    const sid = groupable ? targetStatusId(n) : null
    if (groupable && sid && open && open.type === n.type && targetStatusId(open.items[0]) === sid) {
      stableGroupPush(open, n)
      continue
    }
    flush()
    if (groupable && sid && n.status) {
      open = { key: `group-${n.id}`, type: n.type as 'favourite' | 'reblog', status: n.status, items: [n] }
    } else {
      entries.push({ kind: 'single', notification: n })
    }
  }
  flush()
  return entries
}

function stableGroupPush(g: NotifGroup, n: Notification) {
  g.items.push(n)
}

/** 'Ada', 'Ada and Ben', or 'Ada, Ben and 3 others' from newest-first items. */
function actorSummary(items: Notification[]): string {
  const names = items.map((n) => {
    const a = n.account
    return a ? a.display_name || a.username : 'Someone'
  })
  if (names.length === 1) return names[0]
  if (names.length === 2) return `${names[0]} and ${names[1]}`
  const rest = names.length - 2
  return `${names[0]}, ${names[1]} and ${rest} other${rest === 1 ? '' : 's'}`
}

export function NotificationsView() {
  useTitle('Notifications')

  const [filter, setFilter] = useState<Filter>('all')
  const [items, setItems] = useState<Notification[]>([])
  const [pending, setPending] = useState<Notification[]>([])
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const [nextMaxId, setNextMaxId] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const requestSeq = useRef(0)

  // Push deep-link: tapping a notification lands here with ?n=<notification
  // id>. Resolve it to the post thread (status-bearing types) or highlight
  // the row; if it's older than the first page, page deeper to find it.
  const jumpId = useRef<string | null>(route.value.query.get('n'))
  const [highlightId, setHighlightId] = useState<string | null>(null)

  async function resolveJump(list: Notification[], moreId: string | null, depth = 0): Promise<void> {
    const id = jumpId.current
    if (!id) return
    const found = list.find((x) => x.id === id)
    if (!found) {
      if (moreId && depth < 2) {
        const page = await api.value.page<Notification>(endpoint('all', moreId))
        setItems((cur) => {
          const seen = new Set(cur.map((x) => x.id))
          return [...cur, ...page.items.filter((x) => !seen.has(x.id))]
        })
        setNextMaxId(page.nextMaxId)
        await resolveJump([...list, ...page.items], page.nextMaxId, depth + 1)
        return
      }
      jumpId.current = null
      return
    }
    jumpId.current = null
    const STATUS_TYPES = ['mention', 'status', 'poll', 'update', 'favourite', 'reblog']
    if (found.status && STATUS_TYPES.includes(found.type)) {
      navigate(`/statuses/${found.status.id}`)
      return
    }
    setHighlightId(id)
    requestAnimationFrame(() => {
      document.querySelector(`[data-notification-id="${id}"]`)?.scrollIntoView({ block: 'center' })
    })
  }

  async function loadFirst(f: Filter) {
    const seq = ++requestSeq.current
    setState('loading')
    setError(null)
    setItems([])
    setPending([])
    setNextMaxId(null)
    try {
      const page = await api.value.page<Notification>(endpoint(f))
      if (seq !== requestSeq.current) return
      setItems(page.items)
      setNextMaxId(page.nextMaxId)
      setState('ready')
      void resolveJump(page.items, page.nextMaxId)
      // Newest loaded notification is read by definition → push the marker.
      // Push the notifications marker only for the unfiltered view: the
      // newest item of a filtered view is not "the newest read
      // notification", and pushing it would misplace the server marker for
      // every other client.
      if (f === 'all' && page.items.length > 0) markNotificationsRead(page.items[0].id)
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : 'Notifications could not be loaded.')
      setState('error')
    }
  }

  async function loadMore() {
    if (!nextMaxId || loadingMore) return
    setLoadingMore(true)
    try {
      const page = await api.value.page<Notification>(endpoint(filter, nextMaxId))
      setItems((cur) => {
        const seen = new Set(cur.map((n) => n.id))
        return [...cur, ...page.items.filter((n) => !seen.has(n.id))]
      })
      setNextMaxId(page.nextMaxId)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'More notifications could not be loaded.')
    } finally {
      setLoadingMore(false)
    }
  }

  useEffect(() => {
    void loadFirst(filter)
    void refreshFollowRequestCount()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filter])

  // Pick up markers written by other devices (guarded to once per session).
  useEffect(() => {
    void syncMarkersFromServer()
  }, [])

  // Live notifications held behind a pill, like the timelines.
  useEffect(() => {
    const unsub = onNotification((n) => {
      if (filter !== 'all' && n.type !== filter) {
        // A follow_request still matters under the Follows filter.
        if (!(filter === 'follow' && n.type === 'follow_request')) return
      }
      if (n.type === 'follow_request') void refreshFollowRequestCount()
      setPending((cur) => (cur.some((x) => x.id === n.id) ? cur : [n, ...cur]))
    })
    return unsub
  }, [filter])

  function showPending() {
    // Showing the pill's notifications reads them, so the sidebar badge clears
    // with them. Same unfiltered-only rule as loadFirst for moving the mark.
    if (filter === 'all' && pending.length > 0) {
      markNotificationsRead(pending.reduce((max, n) => (n.id > max ? n.id : max), pending[0].id))
    }
    setItems((cur) => {
      const seen = new Set(cur.map((n) => n.id))
      return [...pending.filter((n) => !seen.has(n.id)), ...cur]
    })
    setPending([])
  }

  function onStatusUpdated(s: Status) {
    setItems((cur) =>
      cur.map((n) => {
        if (n.status?.id === s.id) return { ...n, status: s }
        if (n.status?.reblog?.id === s.id) return { ...n, status: { ...n.status, reblog: s } }
        return n
      }),
    )
  }

  function onStatusDeleted(id: string) {
    const drop = (n: Notification) => n.status?.id === id || n.status?.reblog?.id === id
    setItems((cur) => cur.filter((n) => !drop(n)))
    setPending((cur) => cur.filter((n) => !drop(n)))
  }

  function selectFilter(f: Filter) {
    if (f !== filter) setFilter(f)
  }

  // Grouping is derived from the flat items list so mutation handlers stay untouched.
  const entries = useMemo(() => groupNotifications(items), [items])

  return (
    <div class="notifications">
      <header class="view-header">
        <h1>Notifications</h1>
        <div class="view-header__actions">
          <button
            class="icon-btn"
            aria-label="Refresh notifications"
            title="Refresh notifications"
            onClick={() => void loadFirst(filter)}
          >
            <Icon name="refresh" size={18} />
          </button>
        </div>
      </header>

      <div class="notifications__filters" role="tablist" aria-label="Filter notifications">
        {FILTERS.map((f) => (
          <button
            key={f.id}
            role="tab"
            aria-selected={filter === f.id}
            class={`notifications__filter${filter === f.id ? ' notifications__filter--active' : ''}`}
            onClick={() => selectFilter(f.id)}
          >
            {f.label}
          </button>
        ))}
      </div>

      {pending.length > 0 && (
        <button class="notifications__new-pill rise-in" onClick={showPending}>
          <Icon name="arrow-up" size={14} />
          {pending.length === 1 ? '1 new notification' : `${pending.length} new notifications`}
        </button>
      )}

      {state === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <div class="spinner" aria-hidden="true" />
          <p>Loading notifications…</p>
        </div>
      )}

      {state === 'error' && (
        <div class="state-block" role="alert">
          <h2>Notifications unavailable</h2>
          <p>{error ?? 'Notifications could not be loaded.'}</p>
          <button class="btn" onClick={() => void loadFirst(filter)}>
            Try again
          </button>
        </div>
      )}

      {state === 'ready' && items.length === 0 && (
        <div class="state-block">
          <h2>All quiet</h2>
          <p>
            {filter === 'all'
              ? 'Mentions, follows, and reactions to your posts will show up here.'
              : `No ${FILTERS.find((f) => f.id === filter)?.label.toLowerCase() ?? ''} yet.`}
          </p>
        </div>
      )}

      {state === 'ready' && items.length > 0 && (
        <div class="notifications__list" aria-busy={loadingMore}>
          {entries.map((e) =>
            e.kind === 'single' ? (
              <NotificationRow
                key={e.notification.id}
                notification={e.notification}
                highlighted={highlightId === e.notification.id}
                onStatusUpdated={onStatusUpdated}
                onStatusDeleted={onStatusDeleted}
                onHandled={(id) => {
                  setItems((cur) => cur.filter((n) => n.id !== id))
                  setPending((cur) => cur.filter((n) => n.id !== id))
                }}
              />
            ) : (
              <GroupedRow
                key={e.group.key}
                group={e.group}
                onStatusUpdated={onStatusUpdated}
                onStatusDeleted={onStatusDeleted}
              />
            ),
          )}
          {error && <p class="error-note" role="alert">{error}</p>}
          {nextMaxId ? (
            <button class="btn notifications__more" disabled={loadingMore} onClick={() => void loadMore()}>
              {loadingMore ? 'Loading…' : 'Load older notifications'}
            </button>
          ) : (
            <p class="notifications__end">You're all caught up.</p>
          )}
        </div>
      )}
    </div>
  )
}

function GroupedRow({
  group,
  onStatusUpdated,
  onStatusDeleted,
}: {
  group: NotifGroup
  onStatusUpdated: (s: Status) => void
  onStatusDeleted: (id: string) => void
}) {
  const kind = kindInfo(group.items[0])
  const summary = `${actorSummary(group.items)} ${kind.text}`
  const newest = group.items[0]
  // Avatar cluster: up to 4 actors, newest first, plus a '+N' overflow chip.
  const shown = group.items.slice(0, 4)
  const overflow = group.items.length - shown.length
  // The two newest actors get linked names in the summary line.
  const named = group.items.slice(0, 2)
  const rest = group.items.length - named.length

  return (
    <article class="notifications__item" aria-label={summary}>
      <div class="notifications__line">
        <span class={`notifications__badge ${kind.cls}`} aria-hidden="true">
          <Icon name={kind.icon} size={16} />
        </span>
        <span class="notifications__group-avatars" aria-hidden="true">
          {shown.map((n) =>
            n.account ? (
              <img
                key={n.id}
                class="notifications__group-avatar"
                src={n.account.avatar_static}
                alt=""
                width={24}
                height={24}
                loading="lazy"
              />
            ) : null,
          )}
          {overflow > 0 && <span class="notifications__group-more">+{overflow}</span>}
        </span>
        <p class="notifications__summary">
          {named.map((n, i) => {
            const a = n.account
            const name = a ? a.display_name || a.username : 'Someone'
            return (
              <Fragment key={n.id}>
                {i > 0 && (rest > 0 ? ', ' : ' and ')}
                {a ? (
                  <a class="notifications__name" href={`#/accounts/${a.id}`}>
                    {replaceEmojis(name, a.emojis)}
                  </a>
                ) : (
                  <span class="notifications__name">{name}</span>
                )}
              </Fragment>
            )
          })}
          {rest > 0 && ` and ${rest} other${rest === 1 ? '' : 's'}`}{' '}
          <span class="notifications__verb">{kind.text}</span>
        </p>
        <span class="notifications__time" title={formatAbsolute(newest.created_at)}>
          <time dateTime={newest.created_at}>{formatRelative(newest.created_at)}</time>
        </span>
      </div>

      <div class="notifications__status">
        <StatusCard
          status={group.status}
          variant="compact"
          onStatusUpdated={onStatusUpdated}
          onStatusDeleted={onStatusDeleted}
        />
      </div>
    </article>
  )
}

function NotificationRow({
  notification: n,
  highlighted,
  onStatusUpdated,
  onStatusDeleted,
  onHandled,
}: {
  notification: Notification
  highlighted?: boolean
  onStatusUpdated: (s: Status) => void
  onStatusDeleted: (id: string) => void
  /** Called after a moderation action handled this notification. */
  onHandled?: (id: string) => void
}) {
  const kind = kindInfo(n)
  const account = n.account
  const name = account ? account.display_name || account.username : 'Someone'
  const [signupBusy, setSignupBusy] = useState<'approve' | 'deny' | null>(null)
  const canModerate = n.type === 'admin.sign_up' && hasAdminScope()

  async function handleSignup(decision: 'approve' | 'deny') {
    if (!account || signupBusy) return
    if (
      decision === 'deny' &&
      !window.confirm(`Deny @${account.acct}'s signup? Their pending account will be removed.`)
    ) {
      return
    }
    setSignupBusy(decision)
    try {
      if (decision === 'approve') {
        await approveSignup({
          id: account.id,
          username: account.username,
          domain: account.domain ?? null,
          created_at: n.created_at,
          confirmed: false,
          approved: false,
          account: {
            id: account.id,
            username: account.username,
            acct: account.acct,
            display_name: account.display_name || account.username,
            avatar: account.avatar,
            avatar_static: account.avatar_static,
          },
        })
        toast(`@${account.acct} approved — they can sign in now`)
      } else {
        await denySignup(account.id)
        toast(`@${account.acct}'s signup denied`)
      }
      onHandled?.(n.id)
    } catch (e) {
      toast(e instanceof Error ? e.message : `Could not ${decision} the signup.`, 'error')
    } finally {
      setSignupBusy(null)
    }
  }

  return (
    <article
      class={`notifications__item${highlighted ? ' notifications__item--highlight' : ''}`}
      data-notification-id={n.id}
      aria-label={`${name} ${kind.text}`}
    >
      <div class="notifications__line">
        <span class={`notifications__badge ${kind.cls}`} aria-hidden="true">
          <Icon name={kind.icon} size={16} />
        </span>
        {account && (
          <a
            class="notifications__avatar-link"
            href={`#/accounts/${account.id}`}
            aria-label={`${name} profile`}
          >
            <img class="notifications__avatar" src={account.avatar_static} alt="" width={32} height={32} loading="lazy" />
          </a>
        )}
        <p class="notifications__summary">
          {account ? (
            <a class="notifications__name" href={`#/accounts/${account.id}`}>
              {replaceEmojis(name, account.emojis)}
            </a>
          ) : (
            <span class="notifications__name">{name}</span>
          )}{' '}
          <span class="notifications__verb">{kind.text}</span>
        </p>
        <span class="notifications__time" title={formatAbsolute(n.created_at)}>
          <time dateTime={n.created_at}>{formatRelative(n.created_at)}</time>
        </span>
      </div>

      {n.type === 'admin.sign_up' && account && (
        <div class="notifications__signup">
          {canModerate ? (
            <div class="notifications__signup-actions">
              <button
                class="btn btn--primary"
                disabled={signupBusy !== null}
                onClick={() => void handleSignup('approve')}
              >
                <Icon name="check" size={16} />
                {signupBusy === 'approve' ? 'Approving…' : 'Approve'}
              </button>
              <button
                class="btn btn--danger"
                disabled={signupBusy !== null}
                onClick={() => void handleSignup('deny')}
              >
                <Icon name="x" size={16} />
                {signupBusy === 'deny' ? 'Denying…' : 'Deny'}
              </button>
            </div>
          ) : (
            <p class="help-text">
              Approving signups needs moderation rights. Enable them in{' '}
              <a
                href="#/settings"
                onClick={(e) => {
                  e.preventDefault()
                  navigate('/settings')
                }}
              >
                Settings
              </a>
              .
            </p>
          )}
        </div>
      )}

      {n.type === 'admin.report' && hasAdminScope() && (
        <div class="notifications__signup">
          <a
            class="btn"
            href="#/moderation"
            onClick={(e) => {
              e.preventDefault()
              navigate('/moderation')
            }}
          >
            Review reports
          </a>
        </div>
      )}

      {n.type === 'follow_request' && account && (
        <div class="notifications__fr-actions">
          <button class="btn" onClick={() => navigate('/follow_requests')}>
            <Icon name="users" size={16} /> Review follow requests
          </button>
        </div>
      )}

      {n.status && (
        <div class="notifications__status">
          <StatusCard
            status={n.status}
            variant="compact"
            onStatusUpdated={onStatusUpdated}
            onStatusDeleted={onStatusDeleted}
          />
        </div>
      )}
    </article>
  )
}
