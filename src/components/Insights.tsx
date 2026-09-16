/**
 * Insights panel for the viewer's own profile: locally tracked follower
 * history plus the performance of recent posts against their own median.
 * Expandable so the extra API calls only happen on demand.
 */
import { useMemo, useState } from 'preact/hooks'
import type { Status } from '../api/types'
import { api } from '../state/auth'
import { followerSnapshots, engagement, median } from '../state/insights'
import { formatCount, formatRelative } from '../util/time'
import { Icon } from './Icon'

const POST_SAMPLE = 100

export function InsightsPanel({ accountId }: { accountId: string }) {
  const [open, setOpen] = useState(false)

  return (
    <div class="insights card">
      <button
        class="insights__toggle"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <Icon name="sparkle" size={16} />
        Post insights
        <Icon name="chevron-right" size={16} />
      </button>
      {open && <InsightsBody accountId={accountId} />}
    </div>
  )
}

function InsightsBody({ accountId }: { accountId: string }) {
  return (
    <div class="insights__body">
      <FollowerTrend />
      <PostPerformance accountId={accountId} />
    </div>
  )
}

function FollowerTrend() {
  const snaps = followerSnapshots()
  if (snaps.length === 0) {
    return (
      <p class="insights__hint">
        Follower history builds up as you use Sidewinder — check back after a few days.
      </p>
    )
  }
  const latest = snaps[snaps.length - 1]
  const weekAgo = snaps.find((s) => s.d >= isoDaysAgo(7)) ?? snaps[0]
  const delta7 = latest.n - weekAgo.n
  return (
    <div class="insights__block">
      <h3 class="insights__subhead">Followers</h3>
      <p class="insights__figures">
        <strong class="num">{formatCount(latest.n)}</strong>
        {snaps.length >= 2 && (
          <span class={`num insights__delta${delta7 > 0 ? ' insights__delta--up' : delta7 < 0 ? ' insights__delta--down' : ''}`}>
            {delta7 > 0 ? '+' : ''}{delta7} this week
          </span>
        )}
        <span class="insights__hint">tracked {snaps.length} {snaps.length === 1 ? 'day' : 'days'} locally</span>
      </p>
      {snaps.length >= 2 && <Sparkline values={snaps.map((s) => s.n)} />}
    </div>
  )
}

function PostPerformance({ accountId }: { accountId: string }) {
  const [state, setState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const [posts, setPosts] = useState<Status[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  function load() {
    setState('loading')
    setError(null)
    api.value
      .page<Status>(`/api/v1/accounts/${encodeURIComponent(accountId)}/statuses`, {
        query: { limit: 40, exclude_replies: true },
      })
      .then((page) => {
        setPosts(page.items.slice(0, POST_SAMPLE))
        setState('ready')
      })
      .catch((e: unknown) => {
        setError(e instanceof Error ? e.message : 'Could not load posts.')
        setState('error')
      })
  }

  const ranked = useMemo(() => {
    if (!posts || posts.length === 0) return null
    const withEngagement = posts.map((s) => ({ s, e: engagement(s) }))
    const med = median(withEngagement.map((x) => x.e))
    const over = withEngagement
      .filter((x) => x.e > med && x.e > 0)
      .sort((a, b) => b.e - a.e)
      .slice(0, 3)
    return { med, over, count: posts.length }
  }, [posts])

  if (state === 'idle') {
    return (
      <div class="insights__block">
        <h3 class="insights__subhead">Post performance</h3>
        <p class="insights__hint">How your latest posts compare to your own typical engagement.</p>
        <button class="btn" onClick={load}>Analyse recent posts</button>
      </div>
    )
  }
  if (state === 'loading') {
    return (
      <div class="insights__block" role="status" aria-busy="true">
        <h3 class="insights__subhead">Post performance</h3>
        <span class="spinner" aria-hidden="true" /> <span class="insights__hint">Crunching…</span>
      </div>
    )
  }
  if (state === 'error') {
    return (
      <div class="insights__block">
        <h3 class="insights__subhead">Post performance</h3>
        <p class="insights__hint" role="alert">{error ?? 'Could not load posts.'}</p>
        <button class="btn" onClick={load}>Try again</button>
      </div>
    )
  }
  if (!ranked || ranked.count === 0) {
    return (
      <div class="insights__block">
        <h3 class="insights__subhead">Post performance</h3>
        <p class="insights__hint">No posts to analyse yet.</p>
      </div>
    )
  }
  return (
    <div class="insights__block">
      <h3 class="insights__subhead">Post performance</h3>
      <p class="insights__hint">
        Median engagement across your latest {ranked.count} posts:{' '}
        <strong class="num">{formatCount(ranked.med)}</strong>
      </p>
      {ranked.over.length === 0 ? (
        <p class="insights__hint">Everything landed near your median — a remarkably consistent feed.</p>
      ) : (
        <ol class="insights__toplist">
          {ranked.over.map(({ s, e }) => (
            <li key={s.id}>
              <a class="insights__toplink" href={`#/statuses/${s.id}`}>
                <span class="insights__toplink-text">
                  {s.spoiler_text ? `CW: ${s.spoiler_text}` : plainText(s.content).slice(0, 120) || '(media post)'}
                </span>
                <span class="insights__toplink-stats num">
                  {formatCount(s.favourites_count ?? 0)} ♥ · {formatCount(s.reblogs_count ?? 0)} ↻ ·{' '}
                  <strong>{e > ranked.med * 2 ? `${Math.round(e / Math.max(1, ranked.med))}× median` : 'above median'}</strong>
                </span>
                <time dateTime={s.created_at} class="insights__toplink-time">{formatRelative(s.created_at)}</time>
              </a>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

function Sparkline({ values }: { values: number[] }) {
  const w = 220
  const h = 36
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  const points = values
    .map((v, i) => `${(i / (values.length - 1)) * w},${h - 2 - ((v - min) / span) * (h - 4)}`)
    .join(' ')
  return (
    <svg class="insights__spark" width={w} height={h} viewBox={`0 0 ${w} ${h}`} role="img" aria-label="Follower count trend">
      <polyline points={points} fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" />
    </svg>
  )
}

function plainText(html: string): string {
  const el = document.createElement('template')
  el.innerHTML = html
  return (el.content.textContent ?? '').replace(/\s+/g, ' ').trim()
}

function isoDaysAgo(days: number): string {
  const d = new Date(Date.now() - days * 86400_000)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
