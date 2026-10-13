/**
 * Timeline views: home, local, federated, hashtag, and list timelines.
 * Cursor pagination via Api.page() (Link header max_id), live prepends via
 * the streaming client, and a pending-new-items pill instead of jumpy inserts.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import { Fragment } from 'preact'
import type { Status, TagInfo } from '../api/types'
import { api, activeSession } from '../state/auth'
import { toast } from '../state/toast'
import { onStatus, type StreamName } from '../state/streaming'
import { getSeenTop, raiseSeenTop, isNewer, wasRecentlyActive, touchActivity } from '../state/readState'
import { isHiddenBooster } from '../state/hideBoosts'
import { queueMarkerPush } from '../state/markers'
import { recordSeenPosts } from '../state/seenPosts'
import { StatusCard } from '../components/StatusCard'
import { MutedPost } from '../components/MutedPost'
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

/**
 * View-only high-water marks for returning-after-absence loads. When the
 * user comes back after 5+ minutes away, the persistent read mark (and the
 * Catch-up boundary behind it) must NOT rise — that's the whole point. But
 * the jump pill should also not fire for posts the user is literally looking
 * at at the top of the feed. So on a returning load we give this visit a
 * view-only boundary at the newest loaded post; it never touches
 * localStorage or the server marker. Keyed per session+timeline so it
 * survives route changes (component unmount) but not a page reload.
 */
const viewBoundary = new Map<string, string>()

/** The newer of two ULID ids (empty strings allowed). */
function newerId(a: string, b: string): string {
  if (!a) return b
  if (!b) return a
  return a > b ? a : b
}

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
  const [tagFollowing, setTagFollowing] = useState<boolean | null>(null)
  const [tagBusy, setTagBusy] = useState(false)
  const [visitMark, setVisitMark] = useState('')
  const [jumped, setJumped] = useState(false)
  const requestSeq = useRef(0)

  const key = `${props.kind}:${props.tag ?? ''}:${props.listId ?? ''}`
  const session = activeSession.value?.id ?? ''

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
      // Raise the seen high-water mark to the newest post of this load; the
      // PREVIOUS mark (returned) is what this visit's pill/divider compare
      // against, so a plain reload never counts old posts as unread.
      if (page.items.length > 0) {
        // Absence-aware read marking: the first load after 5+ minutes away
        // is "just came back" — it must NOT mark away-posts as read (that
        // is exactly the Catch-up set). Only loads while actively using the
        // app raise the persistent mark.
        const fresh = wasRecentlyActive()
        touchActivity()
        const viewKey = `${session}::${key}`
        let prev: string
        if (fresh) {
          // Active reading: raise the persistent mark; the pill boundary for
          // this load is what was seen before — the older of the previous
          // persistent mark and any view-only boundary a returning load
          // established (that boundary's batch was already displayed).
          const persistedPrev = getSeenTop(session, key)
          raiseSeenTop(session, key, page.items[0].id)
          const viewPrev = viewBoundary.get(viewKey) ?? ''
          prev = viewPrev && viewPrev > persistedPrev ? viewPrev : persistedPrev
          viewBoundary.delete(viewKey)
        } else {
          // Returning: boundary is view-only, at the newest post on screen,
          // so the jump pill doesn't point at what the user is already
          // reading. The persistent mark (Catch-up + server marker) stays.
          prev = page.items[0].id
          viewBoundary.set(viewKey, prev)
        }
        setVisitMark(prev)
        // Push only when the mark actually ROSE (items[0] newer than prev).
        // `prev !== id` would also fire on a shrunk timeline (posts deleted
        // above the mark), regressing the server marker.
        if (props.kind === 'home' && fresh && page.items[0].id > prev) {
          queueMarkerPush('home', page.items[0].id)
        }
        void recordSeenPosts(page.items)
      }
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
      void recordSeenPosts(page.items)
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

  // Resolve hashtag follow state for #/tags/:tag timelines.
  useEffect(() => {
    if (props.kind !== 'tag' || !props.tag) {
      setTagFollowing(null)
      return
    }
    let alive = true
    setTagFollowing(null)
    api.value
      .get<TagInfo>(`/api/v1/tags/${encodeURIComponent(props.tag)}`)
      .then((info) => {
        if (alive) setTagFollowing(Boolean(info.following))
      })
      .catch(() => {
        if (alive) setTagFollowing(null)
      })
    return () => {
      alive = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  async function toggleTagFollow() {
    if (!props.tag || tagFollowing === null || tagBusy) return
    setTagBusy(true)
    const verb = tagFollowing ? 'unfollow' : 'follow'
    try {
      const info = await api.value.post<TagInfo>(
        `/api/v1/tags/${encodeURIComponent(props.tag)}/${verb}`,
      )
      const next = Boolean(info.following)
      setTagFollowing(next)
      toast(next ? `Following #${props.tag}` : `Unfollowed #${props.tag}`)
    } catch (e) {
      toast(e instanceof Error ? e.message : `Could not ${verb} #${props.tag}.`, 'error')
    } finally {
      setTagBusy(false)
    }
  }

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

  // Read-state memory: pick up the seen high-water mark for this timeline…
  // Prefer a view-only boundary from a returning-after-absence load over the
  // older persistent mark, so re-entering the view mid-visit doesn't repaint
  // the batch the user already looked at as "unread".
  useEffect(() => {
    const viewKey = `${session}::${key}`
    setVisitMark(newerId(getSeenTop(session, key), viewBoundary.get(viewKey) ?? ''))
    setJumped(false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, session])

  function jumpToUnseen() {
    setJumped(true)
    if (pending.length > 0) showPending()
    requestAnimationFrame(() => {
      const cards = Array.from(document.querySelectorAll<HTMLElement>('[data-status-id]'))
      const target = cards.find((el) => isNewer(el.dataset.statusId ?? '', visitMark)) ?? cards[0]
      target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    })
  }

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

  // Hidden-boost filter, computed at render so toggling "hide boosts" on a
  // profile takes effect immediately in an open timeline. Reading the signal
  // here also subscribes this component to further changes. A boost wrapper's
  // top-level `account` is the BOOSTER; `reblog.account` is the original
  // author — we hide by booster.
  const visibleItems = items.filter((s) => !(s.reblog && isHiddenBooster(s.account.id)))

  // Read-state: how many posts arrived since the last timeline load, and
  // where the new/old divider falls.
  const unseenCount = pending.length + visibleItems.filter((s) => isNewer(s.id, visitMark)).length
  const firstSeenIndex = visitMark ? visibleItems.findIndex((s) => !isNewer(s.id, visitMark)) : -1
  const showPill = unseenCount > 0 && (!jumped || pending.length > 0)

  return (
    <div class="timelines">
      <header class="view-header">
        <h1>{heading}</h1>
        <div class="view-header__actions">
          {props.kind === 'tag' && tagFollowing !== null && (
            <button
              class={`btn${tagFollowing ? '' : ' btn--primary'}`}
              disabled={tagBusy}
              aria-pressed={tagFollowing}
              onClick={() => void toggleTagFollow()}
            >
              <Icon name={tagFollowing ? 'check' : 'plus'} size={16} />
              {tagFollowing ? 'Following' : 'Follow tag'}
            </button>
          )}
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

      {showPill && (
        <button
          class="timelines__new-pill rise-in"
          onClick={jumpToUnseen}
          aria-label={`Jump to ${unseenCount} ${unseenCount === 1 ? 'post' : 'posts'} you haven't read`}
        >
          <Icon name="arrow-up" size={14} />
          {pending.length > 0
            ? pending.length === 1 ? '1 new post — jump' : `${pending.length} new posts — jump`
            : unseenCount === 1 ? '1 unread post — jump' : `${unseenCount} unread posts — jump`}
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
          {visibleItems.map((s, i) => (
            <Fragment key={s.id}>
              {i === firstSeenIndex && firstSeenIndex > 0 && (
                <div class="timelines__seen-divider" role="separator" aria-label="Posts above this line are new since your last visit to this timeline">
                  <span>new posts</span>
                </div>
              )}
              <MutedPost status={s}>
                <StatusCard
                  status={s}
                  variant="timeline"
                  onStatusUpdated={onStatusUpdated}
                  onStatusDeleted={onStatusDeleted}
                />
              </MutedPost>
            </Fragment>
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
