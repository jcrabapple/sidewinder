/**
 * Timeline views: home, local, federated, hashtag, and list timelines.
 * Cursor pagination via Api.page() (Link header max_id), live prepends via
 * the streaming client, and a pending-new-items pill instead of jumpy inserts.
 */
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks'
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

/**
 * Timeline snapshots: what was on screen when the reader left a timeline
 * (open a thread, a profile, any route) so coming BACK lands on the same
 * post instead of a fresh load at the top. Module scope, so they survive the
 * component unmounting on route change but not a page reload. Keyed per
 * session+timeline. Restoring touches no read-state: the marks were already
 * handled by the load that produced these items, and posts that arrived
 * while away go behind the pending pill (fetchNewer), never above the
 * reader's position.
 */
interface TimelineSnapshot {
  items: Status[]
  nextMaxId: string | null
  /** The first card still visible below the sticky header… */
  anchorId: string | null
  /** …and its top edge in viewport px at capture time. */
  anchorTop: number
  /** Fallback when the anchor card is gone (deleted while away). */
  scrollY: number
  savedAt: number
}

const snapshots = new Map<string, TimelineSnapshot>()
/** Past this, a return is a fresh visit: reload from the top. */
const SNAPSHOT_TTL_MS = 30 * 60_000
/** How long layout gets to settle (images above the anchor) while restoring. */
const RESTORE_SETTLE_MS = 1200

function freshSnapshot(viewKey: string): TimelineSnapshot | null {
  const snap = snapshots.get(viewKey)
  if (!snap) return null
  if (Date.now() - snap.savedAt > SNAPSHOT_TTL_MS || snap.items.length === 0) {
    snapshots.delete(viewKey)
    return null
  }
  return snap
}

/** The first timeline card whose bottom clears the sticky view header. */
function captureAnchor(): { anchorId: string | null; anchorTop: number } {
  const header = document.querySelector<HTMLElement>('.timelines .view-header')
  const headerBottom = header ? header.getBoundingClientRect().bottom : 0
  const cards = document.querySelectorAll<HTMLElement>('.timelines__list [data-status-id]')
  for (const el of cards) {
    const r = el.getBoundingClientRect()
    if (r.bottom > headerBottom + 1) return { anchorId: el.dataset.statusId ?? null, anchorTop: r.top }
  }
  return { anchorId: null, anchorTop: 0 }
}

/**
 * Put the anchor card back where it was, re-applying for a short window
 * while images above it finish laying out. Any reader input (touch, wheel,
 * key) ends the settle loop so it never fights a deliberate scroll.
 */
function restoreScroll(snap: TimelineSnapshot): () => void {
  let stopped = false
  const stop = () => {
    stopped = true
  }
  const opts = { passive: true, capture: true } as AddEventListenerOptions
  const inputs = ['touchstart', 'wheel', 'keydown', 'mousedown'] as const
  for (const ev of inputs) window.addEventListener(ev, stop, opts)
  const apply = () => {
    const el = snap.anchorId
      ? document.querySelector<HTMLElement>(`.timelines__list [data-status-id="${CSS.escape(snap.anchorId)}"]`)
      : null
    if (el) {
      const delta = el.getBoundingClientRect().top - snap.anchorTop
      if (Math.abs(delta) >= 1) window.scrollTo(0, window.scrollY + delta)
    } else {
      window.scrollTo(0, snap.scrollY)
    }
  }
  apply()
  const until = performance.now() + RESTORE_SETTLE_MS
  const tick = () => {
    if (stopped || performance.now() > until) return cleanup()
    apply()
    requestAnimationFrame(tick)
  }
  const cleanup = () => {
    stopped = true
    for (const ev of inputs) window.removeEventListener(ev, stop, opts)
  }
  requestAnimationFrame(tick)
  return cleanup
}

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

  const key = `${props.kind}:${props.tag ?? ''}:${props.listId ?? ''}`
  const session = activeSession.value?.id ?? ''
  const viewKey = `${session}::${key}`

  // A fresh snapshot for THIS mount seeds state directly, so the restored
  // list paints on the first frame with no spinner flash or scroll jump.
  const [mountSnap] = useState(() => freshSnapshot(viewKey))
  const [items, setItems] = useState<Status[]>(mountSnap?.items ?? [])
  const [pending, setPending] = useState<Status[]>([])
  const [state, setState] = useState<LoadState>(mountSnap ? 'ready' : 'loading')
  const [error, setError] = useState<string | null>(null)
  const [nextMaxId, setNextMaxId] = useState<string | null>(mountSnap?.nextMaxId ?? null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [listTitle, setListTitle] = useState<string | null>(null)
  const [tagFollowing, setTagFollowing] = useState<boolean | null>(null)
  const [tagBusy, setTagBusy] = useState(false)
  const [visitMark, setVisitMark] = useState('')
  const [jumped, setJumped] = useState(false)
  const requestSeq = useRef(0)

  // Snapshot bookkeeping. Refs, because the save runs from listeners and
  // effect cleanups whose closures would otherwise hold stale state.
  const itemsRef = useRef(items)
  itemsRef.current = items
  const nextMaxIdRef = useRef(nextMaxId)
  nextMaxIdRef.current = nextMaxId
  const anchorRef = useRef<{ anchorId: string | null; anchorTop: number; scrollY: number } | null>(null)
  /** Snapshot waiting for its list to be in the DOM before scrolling to it. */
  const restoreRef = useRef<TimelineSnapshot | null>(mountSnap)
  /** Set when the mount already applied the snapshot for this key. */
  const seededKeyRef = useRef<string | null>(mountSnap ? viewKey : null)

  async function loadFirst() {
    const seq = ++requestSeq.current
    setState('loading')
    setError(null)
    setJumped(false)
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

  /**
   * After a snapshot restore: posts that arrived while the reader was away
   * (the stream was unsubscribed) go behind the pending pill, so the list
   * under the reader's thumb does not move. min_id pages forward from the
   * newest restored post with no gap; a backlog larger than one page is
   * left to the refresh button.
   */
  async function fetchNewer(newestId: string) {
    const seq = requestSeq.current
    try {
      const page = await api.value.page<Status>(endpoint(props), {
        query: { limit: 40, min_id: newestId, local: props.kind === 'local' ? true : undefined },
        timeoutMs: timeoutFor(props),
      })
      if (seq !== requestSeq.current || page.items.length === 0) return
      setPending((cur) => {
        const seen = new Set(cur.map((s) => s.id))
        const merged = [...cur, ...page.items.filter((s) => !seen.has(s.id) && s.id > newestId)]
        return merged.sort((a, b) => (a.id < b.id ? 1 : a.id > b.id ? -1 : 0))
      })
    } catch {
      // Best-effort: the restored list is still correct, just not topped up.
    }
  }

  // Track where the reader is, continuously, so leaving by any route saves
  // the right post. Captured on scroll (rAF-throttled) and once more on
  // hashchange, which fires before the route re-render tears down the list.
  useEffect(() => {
    let frame = 0
    const capture = () => {
      if (!document.querySelector('.timelines__list')) return
      anchorRef.current = { ...captureAnchor(), scrollY: window.scrollY }
    }
    const onScroll = () => {
      if (frame) return
      frame = requestAnimationFrame(() => {
        frame = 0
        capture()
      })
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('hashchange', capture, true)
    return () => {
      if (frame) cancelAnimationFrame(frame)
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('hashchange', capture, true)
    }
  }, [])

  // Initial load + reload when the timeline identity changes. A fresh
  // snapshot (same session + timeline, left < 30 min ago) is restored
  // instead of refetched; the cleanup writes this key's snapshot on the way
  // out, whether that is an unmount or a switch to another timeline.
  useEffect(() => {
    const myKey = viewKey
    let snap: TimelineSnapshot | null = null
    if (seededKeyRef.current === myKey) {
      snap = mountSnap
    } else {
      snap = freshSnapshot(myKey)
      if (snap) {
        ++requestSeq.current
        setItems(snap.items)
        setNextMaxId(snap.nextMaxId)
        setPending([])
        setError(null)
        setState('ready')
        restoreRef.current = snap
      }
    }
    seededKeyRef.current = null
    if (snap) {
      anchorRef.current = { anchorId: snap.anchorId, anchorTop: snap.anchorTop, scrollY: snap.scrollY }
      void fetchNewer(snap.items[0].id)
    } else {
      anchorRef.current = null
      void loadFirst()
    }
    return () => {
      const list = itemsRef.current
      const anchor = anchorRef.current
      if (list.length === 0) {
        snapshots.delete(myKey)
        return
      }
      snapshots.set(myKey, {
        items: list,
        nextMaxId: nextMaxIdRef.current,
        anchorId: anchor?.anchorId ?? null,
        anchorTop: anchor?.anchorTop ?? 0,
        scrollY: anchor?.scrollY ?? 0,
        savedAt: Date.now(),
      })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  // Scroll back to the saved post once the restored list is in the DOM.
  // Layout effect, so the first painted frame is already in position. The
  // settle loop outlives later renders (the pending top-up re-renders) and
  // is only cut short by unmount or reader input.
  const settleStopRef = useRef<(() => void) | null>(null)
  useLayoutEffect(() => {
    const snap = restoreRef.current
    if (!snap || state !== 'ready' || items.length === 0) return
    restoreRef.current = null
    settleStopRef.current?.()
    settleStopRef.current = restoreScroll(snap)
  })
  useEffect(() => () => settleStopRef.current?.(), [])

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
  // `jumped` = the reader has reached the unread posts, by the pill OR by
  // simply having the top of the list on screen (see the effect below).
  // Pending (stream / away top-up) posts are not in the list yet, so their
  // pill always shows.
  const showPill = unseenCount > 0 && (!jumped || pending.length > 0)

  // In-list unread posts are always the FIRST cards (newest first), so once
  // the top of the list has been on screen the reader has already seen what
  // the pill would jump to. Check before paint (a fresh load starts at the
  // top, so the pill never even flashes) and again on scroll for a load that
  // started scrolled down. Once seen, it stays dismissed for this visit.
  const listUnseen = unseenCount - pending.length
  useLayoutEffect(() => {
    if (jumped || listUnseen === 0 || state !== 'ready') return
    const topVisible = () => {
      const first = document.querySelector('.timelines__list')?.firstElementChild
      if (!first) return false
      const header = document.querySelector('.timelines .view-header')
      const headerBottom = header ? header.getBoundingClientRect().bottom : 0
      const r = first.getBoundingClientRect()
      return r.bottom > headerBottom + 1 && r.top < window.innerHeight
    }
    if (topVisible()) {
      setJumped(true)
      return
    }
    let frame = 0
    const onScroll = () => {
      if (frame) return
      frame = requestAnimationFrame(() => {
        frame = 0
        if (topVisible()) setJumped(true)
      })
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      if (frame) cancelAnimationFrame(frame)
      window.removeEventListener('scroll', onScroll)
    }
  }, [jumped, listUnseen, state])

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
