/**
 * Collections: the user's favourited and bookmarked statuses. Cursor
 * pagination via Api.page(); un-faving / un-bookmarking removes the row.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import type { Status } from '../api/types'
import { api } from '../state/auth'
import { StatusCard } from '../components/StatusCard'
import { Icon } from '../components/Icon'
import { useTitle } from '../router'
import './Collections.css'

type LoadState = 'loading' | 'error' | 'ready'

const PAGE_SIZE = 30

export function CollectionsView({ kind }: { kind: 'favourites' | 'bookmarks' }) {
  const title = kind === 'favourites' ? 'Favourites' : 'Bookmarks'
  useTitle(title)

  const [items, setItems] = useState<Status[]>([])
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const [nextMaxId, setNextMaxId] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const requestSeq = useRef(0)

  const endpoint = kind === 'favourites' ? '/api/v1/favourites' : '/api/v1/bookmarks'

  async function loadFirst() {
    const seq = ++requestSeq.current
    setState('loading')
    setError(null)
    setLoadingMore(false)
    setItems([])
    setNextMaxId(null)
    try {
      const page = await api.value.page<Status>(endpoint, { query: { limit: PAGE_SIZE } })
      if (seq !== requestSeq.current) return
      setItems(page.items)
      setNextMaxId(page.nextMaxId)
      setState('ready')
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : `${title} could not be loaded.`)
      setState('error')
    }
  }

  async function loadMore() {
    if (!nextMaxId || loadingMore) return
    const seq = ++requestSeq.current
    const cursor = nextMaxId
    setLoadingMore(true)
    setError(null)
    try {
      const page = await api.value.page<Status>(endpoint, {
        query: { limit: PAGE_SIZE, max_id: cursor },
      })
      if (seq !== requestSeq.current) return
      setItems((cur) => {
        const seen = new Set(cur.map((s) => s.id))
        return [...cur, ...page.items.filter((s) => !seen.has(s.id))]
      })
      setNextMaxId(page.nextMaxId)
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : 'More posts could not be loaded.')
    } finally {
      if (seq === requestSeq.current) setLoadingMore(false)
    }
  }

  useEffect(() => {
    void loadFirst()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind])

  function onStatusUpdated(s: Status) {
    // Un-faving / un-bookmarking from inside the collection drops the row.
    const stillHere = kind === 'favourites' ? s.favourited : s.bookmarked
    if (!stillHere) {
      setItems((cur) => cur.filter((it) => it.id !== s.id && it.reblog?.id !== s.id))
      return
    }
    setItems((cur) => cur.map((it) => (it.id === s.id ? s : it)))
  }

  function onStatusDeleted(id: string) {
    setItems((cur) => cur.filter((it) => it.id !== id && it.reblog?.id !== id))
  }

  return (
    <div class="collections">
      <header class="view-header">
        <h1>{title}</h1>
        <div class="view-header__actions">
          <button
            class="icon-btn"
            aria-label={`Refresh ${title.toLowerCase()}`}
            title="Refresh"
            onClick={() => void loadFirst()}
          >
            <Icon name="refresh" size={18} />
          </button>
        </div>
      </header>

      {state === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <div class="spinner" aria-hidden="true" />
          <p>Loading posts…</p>
        </div>
      )}

      {state === 'error' && (
        <div class="state-block" role="alert">
          <h2>{title} unavailable</h2>
          <p>{error ?? `${title} could not be loaded.`}</p>
          <button class="btn" onClick={() => void loadFirst()}>Try again</button>
        </div>
      )}

      {state === 'ready' && items.length === 0 && (
        <div class="state-block">
          <h2>{kind === 'favourites' ? 'No favourites yet' : 'No bookmarks yet'}</h2>
          <p>
            {kind === 'favourites'
              ? 'Posts you favourite show up here.'
              : 'Bookmark a post to save it here for later. Only you can see your bookmarks.'}
          </p>
        </div>
      )}

      {state === 'ready' && items.length > 0 && (
        <div class="collections__list" aria-busy={loadingMore}>
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
            <button class="btn collections__more" disabled={loadingMore} onClick={() => void loadMore()}>
              {loadingMore ? 'Loading…' : 'Load older posts'}
            </button>
          ) : (
            <p class="collections__end">That's everything.</p>
          )}
        </div>
      )}
    </div>
  )
}
