/**
 * Catch-up view: an email-style digest of home-timeline posts the user
 * hasn't read yet. The since-boundary is the read-state high-water mark
 * written by the home timeline; when no mark exists yet (first run), a
 * range picker (1h/6h/12h/24h) stands in and posts are cut off by
 * created_at instead of id.
 *
 * Fetch strategy: paginate /api/v1/timelines/home backwards with max_id
 * until we pass the boundary (status.id <= B, or created_at older than the
 * range cutoff), capped at 800 posts / 20 pages. Boosts of the same status
 * collapse into one row; links shared by 2+ people surface in a Top links
 * section above the digest.
 */
import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import type { Status } from '../api/types'
import type { Page } from '../api/client'
import { api, activeSession } from '../state/auth'
import { getSeenTop } from '../state/readState'
import { replaceEmojis } from '../components/StatusCard'
import { Icon } from '../components/Icon'
import { useTitle, navigate } from '../router'
import { formatCompact, formatAbsolute, formatCount } from '../util/time'
import './CatchUp.css'

type Filter = 'all' | 'originals' | 'boosts' | 'replies'
type Sort = 'newest' | 'oldest' | 'liked'
type LoadState = 'loading' | 'error' | 'ready'

const FILTERS: { id: Filter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'originals', label: 'Originals' },
  { id: 'boosts', label: 'Boosts' },
  { id: 'replies', label: 'Replies' },
]

const RANGES: { hours: number; label: string }[] = [
  { hours: 1, label: 'Last 1h' },
  { hours: 6, label: 'Last 6h' },
  { hours: 12, label: 'Last 12h' },
  { hours: 24, label: 'Last 24h' },
]

const PAGE_SIZE = 40
const MAX_POSTS = 800
const MAX_PAGES = 20

/** One digest row: a status, or several boosts collapsed onto the original. */
interface DigestEntry {
  /** The newest wrapper (boost) or the status itself. */
  outer: Status
  /** The underlying status (outer.reblog ?? outer). */
  status: Status
  /** Booster accounts, newest first; empty for non-boosts. */
  boosters: Status['account'][]
}

function stripHtml(html: string): string {
  const el = document.createElement('template')
  el.innerHTML = html
  return (el.content.textContent ?? '').replace(/\s+/g, ' ').trim()
}

function normalizeUrl(url: string): string {
  return url.replace(/\/+$/, '')
}

function domainOf(url: string): string {
  try {
    return new URL(url).host
  } catch {
    return url
  }
}

export function CatchUpView() {
  useTitle('Catch-up')

  const sess = activeSession.value
  const sessionId = sess?.id ?? ''
  const me = sess?.account.id ?? ''
  // Same key the home timeline writes its high-water mark under.
  const boundary = getSeenTop(sessionId, 'home::')

  const [rangeHours, setRangeHours] = useState(24)
  const [filter, setFilter] = useState<Filter>('all')
  const [sort, setSort] = useState<Sort>('newest')
  const [items, setItems] = useState<Status[]>([])
  const [loaded, setLoaded] = useState(0)
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const requestSeq = useRef(0)

  const useRange = boundary === ''

  async function load() {
    const seq = ++requestSeq.current
    setState('loading')
    setError(null)
    setItems([])
    setLoaded(0)
    const cutoff = useRange ? Date.now() - rangeHours * 3600_000 : 0
    const collected: Status[] = []
    try {
      let maxId: string | null = null
      for (let page = 0; page < MAX_PAGES; page++) {
        const res: Page<Status> = await api.value.page<Status>('/api/v1/timelines/home', {
          query: { limit: PAGE_SIZE, max_id: maxId ?? undefined },
        })
        if (seq !== requestSeq.current) return
        if (res.items.length === 0) break
        let passedBoundary = false
        for (const s of res.items) {
          if (useRange) {
            if (new Date(s.created_at).getTime() < cutoff) {
              passedBoundary = true
              break
            }
          } else if (s.id <= boundary) {
            passedBoundary = true
            break
          }
          collected.push(s)
        }
        setLoaded(collected.length)
        if (passedBoundary || collected.length >= MAX_POSTS || !res.nextMaxId) break
        maxId = res.nextMaxId
      }
      if (seq !== requestSeq.current) return
      // Reverse to chronological; the sort select controls display order.
      setItems([...collected].reverse())
      setState('ready')
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : 'The digest could not be loaded.')
      setState('error')
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, rangeHours, useRange])

  // Boost dedupe: collapse wrappers of the same original into one entry.
  const entries = useMemo<DigestEntry[]>(() => {
    const byOriginal = new Map<string, DigestEntry>()
    for (const outer of items) {
      const status = outer.reblog ?? outer
      const existing = byOriginal.get(status.id)
      if (existing) {
        // items are chronological here; unshift keeps boosters newest-first
        // and the later wrapper is the newer one.
        if (outer.reblog) {
          existing.boosters.unshift(outer.account)
          existing.outer = outer
        }
      } else {
        byOriginal.set(status.id, {
          outer,
          status,
          boosters: outer.reblog ? [outer.account] : [],
        })
      }
    }
    return [...byOriginal.values()]
  }, [items])

  const filtered = useMemo(() => {
    const out = entries.filter((e) => {
      const boosted = e.boosters.length > 0
      const isReply = Boolean(e.status.in_reply_to_id) && e.status.in_reply_to_account_id !== me
      switch (filter) {
        case 'all':
          return true
        case 'originals':
          return !boosted && !isReply
        case 'boosts':
          return boosted
        case 'replies':
          return !boosted && isReply
      }
    })
    switch (sort) {
      case 'newest':
        out.sort((a, b) => b.outer.id.localeCompare(a.outer.id))
        break
      case 'oldest':
        out.sort((a, b) => a.outer.id.localeCompare(b.outer.id))
        break
      case 'liked':
        out.sort((a, b) => b.status.favourites_count - a.status.favourites_count)
        break
    }
    return out
  }, [entries, filter, sort, me])

  // Top shared links: group link cards by normalized URL.
  const topLinks = useMemo(() => {
    interface LinkAgg {
      url: string
      title: string
      sharers: Set<string>
      score: number
    }
    const byUrl = new Map<string, LinkAgg>()
    for (const e of entries) {
      const card = e.status.card
      // No image requirement: the section never renders previews, and
      // image-less cards are the majority of real links.
      if (!card || card.type !== 'link') continue
      const key = normalizeUrl(card.url)
      let agg = byUrl.get(key)
      if (!agg) {
        agg = { url: card.url, title: card.title || domainOf(card.url), sharers: new Set(), score: 0 }
        byUrl.set(key, agg)
      }
      agg.sharers.add(e.status.account.id)
      for (const b of e.boosters) agg.sharers.add(b.id)
      agg.score += e.status.favourites_count + e.status.reblogs_count
    }
    const all = [...byUrl.values()]
    const shared = all.filter((l) => l.sharers.size >= 2)
    const pool = shared.length > 0 ? shared : all
    return pool
      .sort((a, b) => b.sharers.size - a.sharers.size || b.score - a.score)
      .slice(0, shared.length > 0 ? 10 : 5)
  }, [entries])

  const rangeLabel = RANGES.find((r) => r.hours === rangeHours)?.label ?? ''

  return (
    <div class="catchup">
      <header class="view-header">
        <h1>Catch-up</h1>
        <div class="view-header__actions">
          <button
            class="icon-btn"
            aria-label="Refresh digest"
            title="Refresh digest"
            onClick={() => void load()}
          >
            <Icon name="refresh" size={18} />
          </button>
        </div>
      </header>

      {state === 'ready' && items.length > 0 && (
        <p class="catchup__summary">
          {items.length === 1 ? '1 post' : `${items.length} posts`}{' '}
          {useRange ? `from the ${rangeLabel.toLowerCase()}` : 'since your last visit'}
        </p>
      )}

      {useRange && (
        <div class="catchup__filters" role="tablist" aria-label="Time range">
          {RANGES.map((r) => (
            <button
              key={r.hours}
              role="tab"
              aria-selected={rangeHours === r.hours}
              class={`catchup__filter${rangeHours === r.hours ? ' catchup__filter--active' : ''}`}
              onClick={() => setRangeHours(r.hours)}
            >
              {r.label}
            </button>
          ))}
        </div>
      )}

      <div class="catchup__controls">
        <div class="catchup__filters" role="tablist" aria-label="Filter digest">
          {FILTERS.map((f) => (
            <button
              key={f.id}
              role="tab"
              aria-selected={filter === f.id}
              class={`catchup__filter${filter === f.id ? ' catchup__filter--active' : ''}`}
              onClick={() => setFilter(f.id)}
            >
              {f.label}
            </button>
          ))}
        </div>
        <label class="catchup__sort">
          <span class="visually-hidden">Sort digest</span>
          <select
            class="select"
            value={sort}
            onChange={(e) => setSort((e.target as HTMLSelectElement).value as Sort)}
          >
            <option value="newest">Newest first</option>
            <option value="oldest">Oldest first</option>
            <option value="liked">Most liked</option>
          </select>
        </label>
      </div>

      {state === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <div class="spinner" aria-hidden="true" />
          <p>{loaded > 0 ? `Loading ${loaded} posts…` : 'Loading posts…'}</p>
        </div>
      )}

      {state === 'error' && (
        <div class="state-block" role="alert">
          <h2>Digest unavailable</h2>
          <p>{error ?? 'The digest could not be loaded.'}</p>
          <button class="btn" onClick={() => void load()}>
            Try again
          </button>
        </div>
      )}

      {state === 'ready' && items.length === 0 && (
        <div class="state-block">
          <h2>You're all caught up</h2>
          <p>
            {useRange
              ? `Nothing new in the ${rangeLabel.toLowerCase()}.`
              : 'Nothing new since your last visit to the home timeline.'}
          </p>
        </div>
      )}

      {state === 'ready' && items.length > 0 && (
        <>
          {topLinks.length > 0 && (
            <section class="catchup__toplinks" aria-labelledby="catchup-toplinks-h">
              <h2 id="catchup-toplinks-h" class="catchup__section-h">
                <Icon name="link" size={16} /> Top links
              </h2>
              <ul class="catchup__toplinks-list">
                {topLinks.map((l) => (
                  <li key={normalizeUrl(l.url)} class="catchup__toplink">
                    <a
                      class="catchup__toplink-title"
                      href={l.url}
                      target="_blank"
                      rel="noopener"
                    >
                      {l.title}
                    </a>
                    <span class="catchup__toplink-meta">
                      {domainOf(l.url)} · shared by {l.sharers.size}{' '}
                      {l.sharers.size === 1 ? 'person' : 'people'}
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {filtered.length === 0 ? (
            <div class="state-block">
              <h2>Nothing in this filter</h2>
              <p>Try a different filter above.</p>
            </div>
          ) : (
            <div class="catchup__list">
              {filtered.map((e) => (
                <DigestRow key={e.status.id} entry={e} />
              ))}
            </div>
          )}
        </>
      )}
    </div>
  )
}

function DigestRow({ entry }: { entry: DigestEntry }) {
  const { outer, status, boosters } = entry
  const name = status.account.display_name || status.account.username
  const text = status.spoiler_text
    ? `CW: ${status.spoiler_text}`
    : stripHtml(status.content)

  function open() {
    // Top-level id: for boosts this is the boost wrapper's own id.
    navigate(`/statuses/${outer.id}`)
  }

  function onKeyDown(e: KeyboardEvent) {
    // Links activate on Enter only (Space scrolls). Ignore key events that
    // bubbled up from the nested avatar/name links — they navigate themselves.
    if (e.key !== 'Enter' || e.target !== e.currentTarget) return
    e.preventDefault()
    open()
  }

  return (
    <article
      class="catchup__row"
      role="link"
      tabIndex={0}
      aria-label={`${boosters.length > 0 ? `${boosters.length} boosted: ` : ''}${name}: ${text.slice(0, 120)}`}
      onClick={open}
      onKeyDown={onKeyDown}
    >
      {boosters.length > 0 && (
        <div class="catchup__boost-line">
          <Icon name="repeat" size={13} />
          <span>
            {boosters.length === 1
              ? `${boosters[0].display_name || boosters[0].username} boosted`
              : boosters.length === 2
                ? `${boosters[0].display_name || boosters[0].username} and ${boosters[1].display_name || boosters[1].username} boosted`
                : `${boosters.length} people boosted — ${boosters[0].display_name || boosters[0].username}, ${boosters[1].display_name || boosters[1].username}`}
          </span>
        </div>
      )}
      <div class="catchup__row-main">
        <a
          class="catchup__avatar-link"
          href={`#/accounts/${status.account.id}`}
          aria-label={`${name} profile`}
          onClick={(e) => e.stopPropagation()}
        >
          <img class="catchup__avatar" src={status.account.avatar_static} alt="" width={36} height={36} loading="lazy" />
        </a>
        <div class="catchup__row-body">
          <div class="catchup__row-head">
            <a
              class="catchup__name"
              href={`#/accounts/${status.account.id}`}
              onClick={(e) => e.stopPropagation()}
            >
              {replaceEmojis(name, status.account.emojis)}
            </a>
            <span class="catchup__time" title={formatAbsolute(status.created_at)}>
              <time dateTime={status.created_at}>{formatCompact(status.created_at)}</time>
            </span>
          </div>
          {text && <p class="catchup__text">{replaceEmojis(text, status.emojis)}</p>}
          <div class="catchup__stats">
            <span class="catchup__stat" aria-label={`${status.favourites_count} favourites`}>
              <Icon name="heart" size={14} />
              <span class="num">{formatCount(status.favourites_count)}</span>
            </span>
            <span class="catchup__stat" aria-label={`${status.reblogs_count} boosts`}>
              <Icon name="repeat" size={14} />
              <span class="num">{formatCount(status.reblogs_count)}</span>
            </span>
            {status.replies_count > 0 && (
              <span class="catchup__stat" aria-label={`${status.replies_count} replies`}>
                <Icon name="reply" size={14} />
                <span class="num">{formatCount(status.replies_count)}</span>
              </span>
            )}
          </div>
        </div>
      </div>
    </article>
  )
}
