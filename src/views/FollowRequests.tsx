/**
 * Follow Requests view: pending follow approvals for a locked account.
 * Authorize or reject per account, paginated, with the shared count signal
 * kept in sync for nav badges.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import type { Account } from '../api/types'
import { api } from '../state/auth'
import { toast } from '../state/toast'
import { refreshFollowRequestCount, decrementFollowRequestCount } from '../state/notifications'
import { onNotification } from '../state/streaming'
import { replaceEmojis } from '../components/StatusCard'
import { Icon } from '../components/Icon'
import { sanitize } from '../util/sanitize'
import { useTitle } from '../router'
import { formatRelative, formatAbsolute } from '../util/time'
import './FollowRequests.css'

type LoadState = 'loading' | 'error' | 'ready'

const PAGE_SIZE = 30

export function FollowRequestsView() {
  useTitle('Follow requests')
  return (
    <div class="follow-requests">
      <header class="view-header">
        <h1>Follow requests</h1>
      </header>
      <FollowRequestsList />
    </div>
  )
}

/** The pending-request list without a page header. Standalone page and the
 *  Follow requests tab on Notifications both render this. */
export function FollowRequestsList() {
  const [items, setItems] = useState<Account[]>([])
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const [nextMaxId, setNextMaxId] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const requestSeq = useRef(0)

  async function loadFirst() {
    const seq = ++requestSeq.current
    setState('loading')
    setError(null)
    setLoadingMore(false)
    setItems([])
    setNextMaxId(null)
    try {
      const page = await api.value.page<Account>('/api/v1/follow_requests', {
        query: { limit: PAGE_SIZE },
      })
      if (seq !== requestSeq.current) return
      setItems(page.items)
      setNextMaxId(page.nextMaxId)
      setState('ready')
      void refreshFollowRequestCount()
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : 'Follow requests could not be loaded.')
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
      const page = await api.value.page<Account>('/api/v1/follow_requests', {
        query: { limit: PAGE_SIZE, max_id: cursor },
      })
      if (seq !== requestSeq.current) return
      setItems((cur) => {
        const seen = new Set(cur.map((a) => a.id))
        return [...cur, ...page.items.filter((a) => !seen.has(a.id))]
      })
      setNextMaxId(page.nextMaxId)
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : 'More requests could not be loaded.')
    } finally {
      if (seq === requestSeq.current) setLoadingMore(false)
    }
  }

  useEffect(() => {
    void loadFirst()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // A new follow_request notification while this view is open: refresh the list.
  useEffect(() => {
    const unsub = onNotification((n) => {
      if (n.type === 'follow_request') void loadFirst()
    })
    return unsub
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function removeAccount(id: string) {
    setItems((cur) => cur.filter((a) => a.id !== id))
    decrementFollowRequestCount()
  }

  return (
    <div class="follow-requests__panel">
      <div class="follow-requests__toolbar">
        <button
          class="icon-btn"
          aria-label="Refresh follow requests"
          title="Refresh follow requests"
          onClick={() => void loadFirst()}
        >
          <Icon name="refresh" size={18} />
        </button>
      </div>

      {state === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <div class="spinner" aria-hidden="true" />
          <p>Loading follow requests…</p>
        </div>
      )}

      {state === 'error' && (
        <div class="state-block" role="alert">
          <h2>Follow requests unavailable</h2>
          <p>{error ?? 'Follow requests could not be loaded.'}</p>
          <button class="btn" onClick={() => void loadFirst()}>
            Try again
          </button>
        </div>
      )}

      {state === 'ready' && items.length === 0 && (
        <div class="state-block">
          <h2>No pending requests</h2>
          <p>When someone asks to follow you, their request will show up here for you to approve or reject.</p>
        </div>
      )}

      {state === 'ready' && items.length > 0 && (
        <div class="follow-requests__list" aria-busy={loadingMore}>
          {items.map((a) => (
            <RequestRow key={a.id} account={a} onDone={removeAccount} />
          ))}
          {error && <p class="error-note" role="alert">{error}</p>}
          {nextMaxId && (
            <button class="btn follow-requests__more" disabled={loadingMore} onClick={() => void loadMore()}>
              {loadingMore ? 'Loading…' : 'Load more requests'}
            </button>
          )}
        </div>
      )}
    </div>
  )
}

function RequestRow({ account: a, onDone }: { account: Account; onDone: (id: string) => void }) {
  const [busy, setBusy] = useState<'accept' | 'reject' | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const name = a.display_name || a.username
  const noteHtml = a.note.trim() ? sanitize(a.note, api.value.base) : null

  async function decide(action: 'accept' | 'reject') {
    if (busy) return
    setBusy(action)
    setActionError(null)
    const path =
      action === 'accept'
        ? `/api/v1/follow_requests/${encodeURIComponent(a.id)}/authorize`
        : `/api/v1/follow_requests/${encodeURIComponent(a.id)}/reject`
    try {
      await api.value.post(path)
      toast(action === 'accept' ? `${name} can now follow you.` : `Follow request from ${name} rejected.`)
      onDone(a.id)
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'The request could not be completed.')
    } finally {
      setBusy(null)
    }
  }

  return (
    <article class="follow-requests__item" aria-label={`Follow request from ${name}`}>
      <a class="follow-requests__avatar-link" href={`#/accounts/${a.id}`} aria-label={`${name} profile`}>
        <img class="follow-requests__avatar" src={a.avatar_static} alt="" width={46} height={46} loading="lazy" />
      </a>
      <div class="follow-requests__body">
        <div class="follow-requests__names">
          <a class="follow-requests__name" href={`#/accounts/${a.id}`}>
            {replaceEmojis(name, a.emojis)}
          </a>
          <span class="follow-requests__handle">@{a.acct}</span>
          <span class="follow-requests__when" title={formatAbsolute(a.created_at)}>
            joined {formatRelative(a.created_at)}
          </span>
        </div>
        {noteHtml && (
          <div
            class="follow-requests__note status-content"
            // Sanitized with DOMPurify above.
            dangerouslySetInnerHTML={{ __html: noteHtml }}
          />
        )}
        <div class="follow-requests__actions">
          <button
            class="btn btn--primary"
            disabled={busy !== null}
            aria-label={`Approve follow request from ${name}`}
            onClick={() => void decide('accept')}
          >
            <Icon name="check" size={16} />
            {busy === 'accept' ? 'Approving…' : 'Approve'}
          </button>
          <button
            class="btn btn--danger"
            disabled={busy !== null}
            aria-label={`Reject follow request from ${name}`}
            onClick={() => void decide('reject')}
          >
            <Icon name="x" size={16} />
            {busy === 'reject' ? 'Rejecting…' : 'Reject'}
          </button>
        </div>
        {actionError && (
          <p class="error-note" role="alert">
            {actionError}
          </p>
        )}
      </div>
    </article>
  )
}
