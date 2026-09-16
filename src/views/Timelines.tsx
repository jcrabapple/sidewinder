/**
 * Timeline views: home, local, federated, hashtag, and list timelines.
 * Cursor pagination via Api.page() (Link header max_id), live prepends via
 * the streaming client, and a pending-new-items pill instead of jumpy inserts.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import type { Status } from '../api/types'
import { api } from '../state/auth'
import { onStatus, type StreamName } from '../state/streaming'
import { StatusCard } from '../components/StatusCard'
import { Icon } from '../components/Icon'
import { useTitle } from '../router'
import './Timelines.css'

export interface TimelinePageProps {
  kind: 'home' | 'local' | 'federated' | 'tag' | 'list'
  tag?: string
  listId?: string
}

type LoadState = 'loading' | 'error' | 'ready'

function endpoint(props: TimelinePageProps): string {
  switch (props.kind) {
    case 'home':
      return '/api/v1/timelines/home'
    case 'local':
    case 'federated':
      return '/api/v1/timelines/public'
    case 'tag':
      return `/api/v1/timelines/tag/${encodeURIComponent(props.tag ?? '')}`
    case 'list':
      return `/api/v1/timelines/list/${encodeURIComponent(props.listId ?? '')}`
  }
}

function streamFor(props: TimelinePageProps): StreamName | null {
  switch (props.kind) {
    case 'home':
      return 'user'
    case 'local':
      return 'public:local'
    case 'federated':
      return 'public'
    case 'tag':
      return props.tag ? `hashtag:${props.tag}` : null
    case 'list':
      return props.listId ? `list:${props.listId}` : null
  }
}

function titleFor(props: TimelinePageProps): string {
  switch (props.kind) {
    case 'home':
      return 'Home'
    case 'local':
      return 'Local'
    case 'federated':
      return 'Federated'
    case 'tag':
      return `#${props.tag ?? ''}`
    case 'list':
      return 'List'
  }
}

const PAGE_SIZE = 30

/** First federated query on a server that knows many domains can take ~2min
 *  server-side (GTS warms its cache); later loads are instant. */
function timeoutFor(props: TimelinePageProps): number {
  return props.kind === 'federated' ? 240000 : 90000
}

export function TimelinePage(props: TimelinePageProps) {
  const title = titleFor(props)
  useTitle(title)

  const [items, setItems] = useState<Status[]>([])
  const [pending, setPending] = useState<Status[]>([])
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const [nextMaxId, setNextMaxId] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [listTitle, setListTitle] = useState<string | null>(null)
  const requestSeq = useRef(0)

  const key = `${props.kind}:${props.tag ?? ''}:${props.listId ?? ''}`

  async function loadFirst() {
    const seq = ++requestSeq.current
    setState('loading')
    setError(null)
    setItems([])
    setPending([])
    setNextMaxId(null)
    try {
      const page = await api.value.page<Status>(endpoint(props), {
        query: { limit: PAGE_SIZE, local: props.kind === 'local' ? true : undefined },
        timeoutMs: timeoutFor(props),
      })
      if (seq !== requestSeq.current) return
      setItems(page.items)
      setNextMaxId(page.nextMaxId)
      setState('ready')
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : 'The timeline could not be loaded.')
      setState('error')
    }
  }

  async function loadMore() {
    if (!nextMaxId || loadingMore) return
    setLoadingMore(true)
    try {
      const page = await api.value.page<Status>(endpoint(props), {
        query: { limit: PAGE_SIZE, max_id: nextMaxId, local: props.kind === 'local' ? true : undefined },
      })
      setItems((cur) => {
        const seen = new Set(cur.map((s) => s.id))
        return [...cur, ...page.items.filter((s) => !seen.has(s.id))]
      })
      setNextMaxId(page.nextMaxId)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'More posts could not be loaded.')
    } finally {
      setLoadingMore(false)
    }
  }

  // Initial load + reload when the timeline identity changes.
  useEffect(() => {
    void loadFirst()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  // Resolve the list title for the header.
  useEffect(() => {
    if (props.kind !== 'list' || !props.listId) {
      setListTitle(null)
      return
    }
    let alive = true
    api.value
      .get<{ title: string }>(`/api/v1/lists/${encodeURIComponent(props.listId)}`)
      .then((l) => {
        if (alive) setListTitle(l.title)
      })
      .catch(() => {
        if (alive) setListTitle(null)
      })
    return () => {
      alive = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  // Live updates: hold new posts behind a pill so reading position is stable.
  useEffect(() => {
    const stream = streamFor(props)
    if (!stream) return
    const unsub = onStatus(stream, (status) => {
      setPending((cur) => (cur.some((s) => s.id === status.id) ? cur : [status, ...cur]))
    })
    return unsub
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  function showPending() {
    setItems((cur) => {
      const seen = new Set(cur.map((s) => s.id))
      return [...pending.filter((s) => !seen.has(s.id)), ...cur]
    })
    setPending([])
  }

  function onStatusUpdated(s: Status) {
    setItems((cur) => cur.map((it) => (it.id === s.id ? s : it)))
  }

  function onStatusDeleted(id: string) {
    setItems((cur) => cur.filter((it) => it.id !== id && it.reblog?.id !== id))
    setPending((cur) => cur.filter((it) => it.id !== id))
  }

  const heading = props.kind === 'list' && listTitle ? listTitle : title

  return (
    <div class="timelines">
      <header class="view-header">
        <h1>{heading}</h1>
        <div class="view-header__actions">
          <button
            class="icon-btn"
            aria-label="Refresh timeline"
            title="Refresh timeline"
            onClick={() => void loadFirst()}
          >
            <Icon name="refresh" size={18} />
          </button>
        </div>
      </header>

      {pending.length > 0 && (
        <button class="timelines__new-pill rise-in" onClick={showPending}>
          <Icon name="arrow-up" size={14} />
          {pending.length === 1 ? '1 new post' : `${pending.length} new posts`}
        </button>
      )}

      {state === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <div class="spinner" aria-hidden="true" />
          <p>
            {props.kind === 'federated'
              ? 'Asking your server to sort its whole known fediverse. The first load can take a minute or two; later loads are quick.'
              : 'Loading posts…'}
          </p>
        </div>
      )}

      {state === 'error' && (
        <div class="state-block" role="alert">
          <h2>Timeline unavailable</h2>
          <p>{error ?? 'The timeline could not be loaded.'}</p>
          <button class="btn" onClick={() => void loadFirst()}>
            Try again
          </button>
        </div>
      )}

      {state === 'ready' && items.length === 0 && (
        <div class="state-block">
          <h2>Nothing here yet</h2>
          <p>
            {props.kind === 'home'
              ? 'Posts from people you follow will show up here.'
              : props.kind === 'tag'
                ? `No posts tagged #${props.tag ?? ''} yet.`
                : 'No posts to show yet.'}
          </p>
        </div>
      )}

      {state === 'ready' && items.length > 0 && (
        <div class="timelines__list" aria-busy={loadingMore}>
          {items.map((s) => (
            <StatusCard
              key={s.id}
              status={s}
              variant="timeline"
              onStatusUpdated={onStatusUpdated}
              onStatusDeleted={onStatusDeleted}
            />
          ))}
          {error && <p class="error-note" role="alert">{error}</p>}
          {nextMaxId ? (
            <button class="btn timelines__more" disabled={loadingMore} onClick={() => void loadMore()}>
              {loadingMore ? 'Loading…' : 'Load older posts'}
            </button>
          ) : (
            <p class="timelines__end">You're all caught up.</p>
          )}
        </div>
      )}
    </div>
  )
}
