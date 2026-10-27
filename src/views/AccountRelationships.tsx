/**
 * Account relationships view: lists accounts that a user follows or is followed by.
 * Supports cursor pagination, relationship mutations, and custom emoji parsing.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import type { Account } from '../api/types'
import { api, activeSession } from '../state/auth'
import { Icon } from '../components/Icon'
import { replaceEmojis } from '../components/StatusCard'
import { sanitize } from '../util/sanitize'
import { useTitle, navigate } from '../router'
import {
  relationshipFor,
  ensureRelationships,
  followAccount,
  unfollowAccount,
} from '../state/relationships'
import './AccountRelationships.css'

type LoadState = 'loading' | 'error' | 'ready'

interface AccountRelationshipsProps {
  id: string
  kind: 'followers' | 'following'
}

const PAGE_SIZE = 40

export function AccountRelationshipsView({ id, kind }: AccountRelationshipsProps) {
  const [account, setAccount] = useState<Account | null>(null)
  const [items, setItems] = useState<Account[]>([])
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const [nextMaxId, setNextMaxId] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const requestSeq = useRef(0)

  const label = kind === 'followers' ? 'Followers' : 'Following'
  const me = activeSession.value?.account.id

  useTitle(account ? `${label} · ${account.display_name || account.username}` : label)

  async function loadFirst() {
    const seq = ++requestSeq.current
    setState('loading')
    setError(null)
    setLoadingMore(false)
    setItems([])
    setNextMaxId(null)
    try {
      const [acct, page] = await Promise.all([
        account
          ? Promise.resolve(account)
          : api.value.get<Account>(`/api/v1/accounts/${encodeURIComponent(id)}`).catch(() => null),
        api.value.page<Account>(`/api/v1/accounts/${encodeURIComponent(id)}/${kind}`, {
          query: { limit: PAGE_SIZE },
        }),
      ])
      if (seq !== requestSeq.current) return
      if (acct) setAccount(acct)
      setItems(page.items)
      setNextMaxId(page.nextMaxId)
      setState('ready')
      if (page.items.length > 0) {
        void ensureRelationships(page.items.map((a) => a.id))
      }
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : `Could not load ${kind}.`)
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
      const page = await api.value.page<Account>(`/api/v1/accounts/${encodeURIComponent(id)}/${kind}`, {
        query: { limit: PAGE_SIZE, max_id: cursor },
      })
      if (seq !== requestSeq.current) return
      setItems((cur) => {
        const seen = new Set(cur.map((a) => a.id))
        return [...cur, ...page.items.filter((a) => !seen.has(a.id))]
      })
      setNextMaxId(page.nextMaxId)
      if (page.items.length > 0) {
        void ensureRelationships(page.items.map((a) => a.id))
      }
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : 'Could not load more accounts.')
    } finally {
      if (seq === requestSeq.current) setLoadingMore(false)
    }
  }

  useEffect(() => {
    void loadFirst()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, kind])

  return (
    <div class="account-rel rise-in">
      <header class="view-header">
        <button
          class="icon-btn"
          aria-label="Back"
          title="Back"
          onClick={() => history.back()}
        >
          <Icon name="chevron-left" size={18} />
        </button>
        <div class="account-rel__title-wrap">
          <h1>{label}</h1>
          {account && (
            <span class="account-rel__sub">
              @{account.acct}
            </span>
          )}
        </div>
        <div class="view-header__actions">
          <button
            class="icon-btn"
            aria-label={`Refresh ${kind}`}
            title={`Refresh ${kind}`}
            onClick={() => void loadFirst()}
          >
            <Icon name="refresh" size={18} />
          </button>
        </div>
      </header>

      {state === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <span class="spinner" aria-hidden="true" />
          <p>Loading {kind}…</p>
        </div>
      )}

      {state === 'error' && (
        <div class="state-block" role="alert">
          <h2>{label} unavailable</h2>
          <p>{error ?? `Could not load ${kind}.`}</p>
          <button class="btn" onClick={() => void loadFirst()}>
            Try again
          </button>
        </div>
      )}

      {state === 'ready' && items.length === 0 && (
        <div class="state-block">
          <h2>No {kind} yet</h2>
          <p>
            {kind === 'followers'
              ? 'Nobody follows this account yet.'
              : 'This account is not following anyone yet.'}
          </p>
        </div>
      )}

      {state === 'ready' && items.length > 0 && (
        <div class="account-rel__list">
          {items.map((acct) => (
            <AccountCard key={acct.id} account={acct} isMe={acct.id === me} />
          ))}
          {error && <p class="error-note" role="alert">{error}</p>}
          {nextMaxId && (
            <button
              class="btn account-rel__more"
              disabled={loadingMore}
              onClick={() => void loadMore()}
            >
              {loadingMore ? 'Loading…' : 'Load more'}
            </button>
          )}
        </div>
      )}
    </div>
  )
}

function AccountCard({ account, isMe }: { account: Account; isMe: boolean }) {
  const rel = relationshipFor(account.id)
  const [busy, setBusy] = useState(false)

  async function toggleFollow(e: MouseEvent) {
    e.preventDefault()
    e.stopPropagation()
    if (!rel || busy) return
    setBusy(true)
    try {
      if (rel.following || rel.requested) {
        await unfollowAccount(account.id)
      } else {
        await followAccount(account.id)
      }
    } finally {
      setBusy(false)
    }
  }

  const isFollowing = rel?.following ?? false
  const isRequested = rel?.requested ?? false
  const btnLabel = isFollowing ? 'Following' : isRequested ? 'Requested' : 'Follow'

  return (
    <article
      class="account-card card"
      onClick={(e: MouseEvent) => {
        if ((e.target as Element).closest('a, button, [role="button"]')) return
        navigate(`/accounts/${account.id}`)
      }}
    >
      <div class="account-card__top">
        <a
          class="account-card__avatar-link"
          href={`#/accounts/${account.id}`}
          aria-label={`${account.display_name || account.username} profile`}
        >
          <img
            class="account-card__avatar"
            src={account.avatar_static || account.avatar}
            alt=""
            width={46}
            height={46}
            loading="lazy"
          />
        </a>

        <div class="account-card__names">
          <a class="account-card__name" href={`#/accounts/${account.id}`}>
            {replaceEmojis(account.display_name || account.username, account.emojis)}
          </a>
          <span class="account-card__handle">
            @{account.acct}
            {account.bot && <span class="account-card__badge">Bot</span>}
            {account.locked && (
              <span class="account-card__badge" title="Locked account">
                <Icon name="lock" size={12} />
              </span>
            )}
            {rel?.followed_by && <span class="account-card__badge">Follows you</span>}
          </span>
        </div>

        {!isMe && rel && (
          <button
            class={`btn account-card__follow-btn${isFollowing ? ' is-following' : isRequested ? ' is-requested' : ''}`}
            disabled={busy}
            aria-label={`${btnLabel} @${account.acct}`}
            title={`${btnLabel} @${account.acct}`}
            onClick={toggleFollow}
          >
            {busy ? '…' : btnLabel}
          </button>
        )}
      </div>

      {account.note && sanitize(account.note, api.value.base).trim() !== '' && (
        <div
          class="account-card__bio status-content"
          dangerouslySetInnerHTML={{ __html: sanitize(account.note, api.value.base) }}
        />
      )}
    </article>
  )
}
