/**
 * Search view: debounced query against /api/v2/search with tabs for posts,
 * people, and hashtags. Queries that look like URLs resolve remote objects.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import type { Account, SearchResults } from '../api/types'
import { api } from '../state/auth'
import { StatusCard, replaceEmojis } from '../components/StatusCard'
import { Icon } from '../components/Icon'
import { formatCount } from '../util/time'
import { navigate, useTitle } from '../router'
import './Search.css'

type Tab = 'statuses' | 'accounts' | 'hashtags'
type LoadState = 'idle' | 'loading' | 'error' | 'ready'

export function SearchView() {
  useTitle('Search')
  const [query, setQuery] = useState('')
  const [submitted, setSubmitted] = useState('')
  const [tab, setTab] = useState<Tab>('statuses')
  const [results, setResults] = useState<SearchResults | null>(null)
  const [state, setState] = useState<LoadState>('idle')
  const [error, setError] = useState<string | null>(null)
  const requestSeq = useRef(0)

  // Debounce typing into a submitted query.
  useEffect(() => {
    const q = query.trim()
    if (!q) {
      setSubmitted('')
      setResults(null)
      setState('idle')
      return
    }
    const t = setTimeout(() => setSubmitted(q), 350)
    return () => clearTimeout(t)
  }, [query])

  useEffect(() => {
    if (!submitted) return
    const seq = ++requestSeq.current
    setState('loading')
    setError(null)
    const resolve = /^https?:\/\//.test(submitted)
    api.value
      // GTS cold searches can take well over 30s (instance warms on first
      // query) — give it a 2-minute leash like the timelines get.
      .request<SearchResults>('/api/v2/search', { query: { q: submitted, resolve, limit: 20 }, timeoutMs: 120_000 })
      .then((res) => {
        if (seq !== requestSeq.current) return
        setResults(res)
        // Land on the first non-empty tab: GTS status search only covers
        // your own/interacted posts, so Posts is often legitimately 0 while
        // People/Hashtags have plenty.
        if (res.statuses.length === 0) {
          if (res.accounts.length > 0) setTab('accounts')
          else if (res.hashtags.length > 0) setTab('hashtags')
        }
        setState('ready')
      })
      .catch((e: unknown) => {
        if (seq !== requestSeq.current) return
        const msg = e instanceof Error ? e.message : 'Search failed.'
        setError(
          /abort|network/i.test(msg)
            ? 'Search timed out — the instance is still warming up. Give it a moment and try again.'
            : msg,
        )
        setState('error')
      })
  }, [submitted])

  const counts = {
    statuses: results?.statuses.length ?? 0,
    accounts: results?.accounts.length ?? 0,
    hashtags: results?.hashtags.length ?? 0,
  }

  return (
    <div class="search rise-in">
      <div class="view-header">
        <h1>Search</h1>
      </div>

      <div class="search__box" role="search">
        <Icon name="search" size={18} />
        <input
          class="search__input"
          type="search"
          value={query}
          placeholder="Search posts, people, hashtags, or paste a URL"
          aria-label="Search"
          onInput={(e) => setQuery((e.target as HTMLInputElement).value)}
        />
        {query && (
          <button class="icon-btn search__clear" aria-label="Clear search" onClick={() => setQuery('')}>
            <Icon name="x" size={16} />
          </button>
        )}
      </div>

      {state === 'idle' && (
        <div class="state-block">
          <h2>Search the fediverse</h2>
          <p>Find posts, people, and hashtags on your server. Paste a status or profile URL to open it here.</p>
        </div>
      )}

      {state === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <span class="spinner" aria-hidden="true" />
          <p>Searching…</p>
        </div>
      )}

      {state === 'error' && (
        <div class="state-block" role="alert">
          <h2>Search failed</h2>
          <p>{error ?? 'The search could not be completed.'}</p>
          <button class="btn" onClick={() => setSubmitted((q) => q)}>Try again</button>
        </div>
      )}

      {state === 'ready' && results && (
        <>
          <div class="search__tabs" role="tablist" aria-label="Search result types">
            <TabButton active={tab === 'statuses'} onClick={() => setTab('statuses')} count={counts.statuses}>Posts</TabButton>
            <TabButton active={tab === 'accounts'} onClick={() => setTab('accounts')} count={counts.accounts}>People</TabButton>
            <TabButton active={tab === 'hashtags'} onClick={() => setTab('hashtags')} count={counts.hashtags}>Hashtags</TabButton>
          </div>

          {tab === 'statuses' &&
            (results.statuses.length === 0 ? (
              <NoResults label="posts" />
            ) : (
              results.statuses.map((s) => (
                <StatusCard
                  key={s.id}
                  status={s}
                  onStatusUpdated={(u) =>
                    setResults((cur) =>
                      cur ? { ...cur, statuses: cur.statuses.map((it) => (it.id === u.id ? u : it)) } : cur,
                    )
                  }
                  onStatusDeleted={(id) =>
                    setResults((cur) =>
                      cur ? { ...cur, statuses: cur.statuses.filter((it) => it.id !== id) } : cur,
                    )
                  }
                />
              ))
            ))}

          {tab === 'accounts' &&
            (results.accounts.length === 0 ? (
              <NoResults label="people" />
            ) : (
              results.accounts.map((a) => <AccountRow key={a.id} account={a} />)
            ))}

          {tab === 'hashtags' &&
            (results.hashtags.length === 0 ? (
              <NoResults label="hashtags" />
            ) : (
              results.hashtags.map((h) => (
                <button key={h.name} class="search__tag" onClick={() => navigate(`/tags/${encodeURIComponent(h.name)}`)}>
                  <Icon name="hash" size={18} />
                  <span class="search__tag-name">{h.name}</span>
                  <Icon name="chevron-right" size={16} />
                </button>
              ))
            ))}
        </>
      )}
    </div>
  )
}

function TabButton({ active, onClick, count, children }: { active: boolean; onClick: () => void; count: number; children: preact.ComponentChildren }) {
  return (
    <button
      role="tab"
      aria-selected={active}
      class={`search__tab${active ? ' search__tab--active' : ''}`}
      onClick={onClick}
    >
      {children} <span class="num">{formatCount(count)}</span>
    </button>
  )
}

function NoResults({ label }: { label: string }) {
  return (
    <div class="state-block">
      <h2>No {label} found</h2>
      <p>Try different words, or paste a full URL to fetch it directly.</p>
    </div>
  )
}

function AccountRow({ account }: { account: Account }) {
  return (
    <a class="search__account" href={`#/accounts/${account.id}`}>
      <img class="search__account-avatar" src={account.avatar_static || account.avatar} alt="" width={46} height={46} loading="lazy" />
      <span class="search__account-names">
        <span class="search__account-name">{replaceEmojis(account.display_name || account.username, account.emojis)}</span>
        <span class="search__account-acct">@{account.acct}</span>
      </span>
      <span class="search__account-meta num">{formatCount(account.followers_count)} followers</span>
    </a>
  )
}
