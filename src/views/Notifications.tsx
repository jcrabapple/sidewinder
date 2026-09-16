/**
 * Notifications view: filterable, paginated notification list with live
 * prepends from the user stream. Status-bearing notifications render a
 * compact StatusCard under the actor line.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import type { Notification, Status } from '../api/types'
import { api } from '../state/auth'
import { onNotification } from '../state/streaming'
import { refreshFollowRequestCount } from '../state/notifications'
import { StatusCard, replaceEmojis } from '../components/StatusCard'
import { Icon } from '../components/Icon'
import { useTitle, navigate } from '../router'
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
          {items.map((n) => (
            <NotificationRow
              key={n.id}
              notification={n}
              onStatusUpdated={onStatusUpdated}
              onStatusDeleted={onStatusDeleted}
            />
          ))}
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

function NotificationRow({
  notification: n,
  onStatusUpdated,
  onStatusDeleted,
}: {
  notification: Notification
  onStatusUpdated: (s: Status) => void
  onStatusDeleted: (id: string) => void
}) {
  const kind = kindInfo(n)
  const account = n.account
  const name = account ? account.display_name || account.username : 'Someone'

  return (
    <article class="notifications__item" aria-label={`${name} ${kind.text}`}>
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
