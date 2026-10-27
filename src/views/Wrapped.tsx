/**
 * Fediverse Wrapped: client-side analytics over your own account for a
 * calendar year. Everything is computed in the browser from your statuses
 * and notifications; nothing is sent anywhere.
 */
import { useEffect, useMemo, useState } from 'preact/hooks'
import type { Notification, Status } from '../api/types'
import { api, activeSession } from '../state/auth'
import { useTitle } from '../router'
import { formatCount } from '../util/time'
import { StatusCard } from '../components/StatusCard'
import { Icon } from '../components/Icon'
import { toast } from '../state/toast'
import './Wrapped.css'

const MAX_STATUS_PAGES = 12
const MAX_NOTIFICATION_PAGES = 10
const PAGE_SIZE = 40

interface WrappedData {
  year: number
  posts: Status[]
  notifications: Notification[]
  boostsGiven: number
  fetchedAt: Date
}

export function WrappedView() {
  useTitle('Wrapped')
  const me = activeSession.value?.account
  const [data, setData] = useState<WrappedData | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [error, setError] = useState<string | null>(null)
  const [year, setYear] = useState(new Date().getFullYear())
  const [retrySequence, setRetrySequence] = useState(0)

  useEffect(() => {
    let alive = true
    setState('loading')
    setError(null)
    setData(null)
    const yearStart = new Date(Date.UTC(year, 0, 1))
    const withinYear = (iso: string) => new Date(iso).getTime() >= yearStart.getTime()

      async function fetchAll<T>(path: string, maxPages: number, stop: (items: T[]) => boolean): Promise<T[]> {
        const out: T[] = []
        let maxId: string | null = null
        for (let page = 0; page < maxPages; page++) {
          const res: { items: T[]; nextMaxId: string | null } = await api.value.page<T>(path, { query: { limit: PAGE_SIZE, max_id: maxId ?? undefined } })
          out.push(...res.items)
          maxId = res.nextMaxId
          if (!maxId || stop(out)) break
        }
        return out
      }

    ;(async () => {
      try {
        const meId = activeSession.value!.account.id
        const statuses = await fetchAll<Status>(
          `/api/v1/accounts/${meId}/statuses`,
          MAX_STATUS_PAGES,
          (items) => items.length > 0 && !withinYear(items[items.length - 1].created_at),
        )
        const notifications = await fetchAll<Notification>(
          '/api/v1/notifications',
          MAX_NOTIFICATION_PAGES,
          (items) => items.length > 0 && !withinYear(items[items.length - 1].created_at),
        )
        if (!alive) return
        setData({
          year,
          posts: statuses.filter((s) => !s.reblog && withinYear(s.created_at)),
          notifications: notifications.filter((n) => withinYear(n.created_at)),
          boostsGiven: statuses.filter((s) => s.reblog && withinYear(s.created_at)).length,
          fetchedAt: new Date(),
        })
        setState('ready')
      } catch (e) {
        if (!alive) return
        setError(e instanceof Error ? e.message : 'Could not load your data.')
        setState('error')
      }
    })()

    return () => {
      alive = false
    }
  }, [year, retrySequence])

  const stats = useMemo(() => (data ? computeStats(data) : null), [data])

  const years = useMemo(() => {
    const created = activeSession.value?.account.created_at
    const first = created ? new Date(created).getFullYear() : 2026
    const now = new Date().getFullYear()
    const list: number[] = []
    for (let y = now; y >= first && y >= now - 5; y--) list.push(y)
    return list
  }, [])

  function copySummary() {
    if (!stats || !data) return
    const text = [
      `My ${data.year} on the fediverse:`,
      `${stats.postCount} posts · ${stats.replies} replies · ${data.boostsGiven} boosts given`,
      `Received ${stats.favsReceived} favourites and ${stats.boostsReceived} boosts`,
      stats.topHashtags[0] ? `Top hashtag: #${stats.topHashtags[0][0]}` : '',
      stats.busiestDay ? `Busiest day: ${stats.busiestDay}` : '',
      stats.mostBoosted ? `Most-boosted post: ${stats.mostBoosted.reblogs_count} boosts` : '',
    ].filter(Boolean).join('\n')
    navigator.clipboard.writeText(text)
      .then(() => toast('Summary copied to the clipboard.'))
      .catch(() => toast('Could not copy to the clipboard.', 'error'))
  }

  return (
    <div class="wrapped">
      <header class="view-header">
        <h1>Wrapped</h1>
        <div class="view-header__actions">
          <label class="visually-hidden" for="wrapped-year">Year</label>
          <select id="wrapped-year" class="select wrapped__year" value={year} onChange={(e) => setYear(Number((e.target as HTMLSelectElement).value))}>
            {years.map((y) => (
              <option key={y} value={y}>{y}</option>
            ))}
          </select>
          {stats && (
            <button class="icon-btn" aria-label="Copy summary" title="Copy summary" onClick={copySummary}>
              <Icon name="link" size={18} />
            </button>
          )}
        </div>
      </header>

      {state === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <div class="spinner" aria-hidden="true" />
          <p>Reading back through your year…</p>
        </div>
      )}

      {state === 'error' && (
        <div class="state-block" role="alert">
          <h2>Wrapped is unavailable</h2>
          <p>{error ?? 'Your data could not be loaded.'}</p>
          <button class="btn" onClick={() => setRetrySequence((seq) => seq + 1)}>Try again</button>
        </div>
      )}

      {state === 'ready' && stats && data && me && (
        <div class="wrapped__body">
          <p class="wrapped__intro rise-in">
            {data.year} with @{me.username}, {me.acct.includes('@') ? `on ${me.acct.split('@')[1]}` : 'at home'}.
            Everything below was computed in your browser from {formatCount(data.posts.length)} posts and{' '}
            {formatCount(data.notifications.length)} notifications.
          </p>

          <section class="wrapped__hero" aria-label="Top-line numbers">
            <div class="wrapped__bignum">
              <span class="wrapped__number num">{formatCount(stats.postCount)}</span>
              <span class="wrapped__caption">posts</span>
            </div>
            <dl class="wrapped__stats">
              <div><dt>Replies</dt><dd class="num">{formatCount(stats.replies)}</dd></div>
              <div><dt>Boosts given</dt><dd class="num">{formatCount(data.boostsGiven)}</dd></div>
              <div><dt>Favourites received</dt><dd class="num">{formatCount(stats.favsReceived)}</dd></div>
              <div><dt>Boosts received</dt><dd class="num">{formatCount(stats.boostsReceived)}</dd></div>
            </dl>
          </section>

          <section aria-label="When you post">
            <h2>Your posting rhythm</h2>
            <BarChart values={stats.byHour} labels={hourLabels()} unit="h" caption="posts by hour of day (local time)" />
            <BarChart values={stats.byWeekday} labels={['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']} caption="posts by weekday" />
            {stats.busiestDay && <p class="wrapped__fact">Busiest day: <strong>{stats.busiestDay}</strong> with {stats.busiestDayCount} {stats.busiestDayCount === 1 ? 'post' : 'posts'}.</p>}
            {stats.streak > 1 && <p class="wrapped__fact">Longest daily streak: <strong>{stats.streak} days</strong>.</p>}
          </section>

          {stats.topHashtags.length > 0 && (
            <section aria-label="Top hashtags">
              <h2>Hashtags you wore out</h2>
              <ul class="wrapped__ranked">
                {stats.topHashtags.map(([tag, count]) => (
                  <li key={tag}>
                    <a href={`#/tags/${encodeURIComponent(tag)}`}>#{tag}</a>
                    <span class="num">{count}</span>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {stats.topInteractions.length > 0 && (
            <section aria-label="People who interacted with you most">
              <h2>People in your corner</h2>
              <p class="help-text">Who boosted and favourited you the most in {data.year}.</p>
              <ul class="wrapped__people">
                {stats.topInteractions.map(([acct, id, avatar, count]) => (
                  <li key={id}>
                    <a href={`#/accounts/${id}`} class="wrapped__person">
                      <img src={avatar} alt="" width={36} height={36} loading="lazy" />
                      <span>{acct}</span>
                    </a>
                    <span class="num">{count}</span>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {stats.mostBoosted && (
            <section aria-label="Most boosted post">
              <h2>Your loudest moment</h2>
              <p class="help-text">
                {formatCount(stats.mostBoosted.reblogs_count)} boosts, {formatCount(stats.mostBoosted.favourites_count)} favourites.
              </p>
              <div class="wrapped__post card">
                <StatusCard status={stats.mostBoosted} variant="detailed" showActions={false} />
              </div>
            </section>
          )}

          <p class="wrapped__footnote">
            Computed locally in your browser on {data.fetchedAt.toLocaleString('en')}. Reads the most recent{' '}
            {MAX_STATUS_PAGES * PAGE_SIZE} posts and {MAX_NOTIFICATION_PAGES * PAGE_SIZE} notifications. Nothing left this device.
          </p>
        </div>
      )}
    </div>
  )
}

function hourLabels(): string[] {
  return Array.from({ length: 24 }, (_, h) => (h % 3 === 0 ? String(h) : ''))
}

interface Stats {
  postCount: number
  replies: number
  favsReceived: number
  boostsReceived: number
  byHour: number[]
  byWeekday: number[]
  busiestDay: string | null
  busiestDayCount: number
  streak: number
  topHashtags: [string, number][]
  topInteractions: [string, string, string, number][]
  mostBoosted: Status | null
}

function computeStats(data: WrappedData): Stats {
  const posts = data.posts
  const replies = posts.filter((s) => s.in_reply_to_id).length

  const byHour = Array.from({ length: 24 }, () => 0)
  const byWeekday = Array.from({ length: 7 }, () => 0)
  const byDay = new Map<string, number>()
  for (const s of posts) {
    const d = new Date(s.created_at)
    byHour[d.getHours()]++
    byWeekday[(d.getDay() + 6) % 7]++ // Monday first
    const day = d.toISOString().slice(0, 10)
    byDay.set(day, (byDay.get(day) ?? 0) + 1)
  }

  let busiestDay: string | null = null
  let busiestDayCount = 0
  for (const [day, count] of byDay) {
    if (count > busiestDayCount) {
      busiestDay = new Date(day + 'T12:00:00').toLocaleDateString('en', { dateStyle: 'long' })
      busiestDayCount = count
    }
  }

  const days = [...byDay.keys()].sort()
  let streak = 0
  let run = 0
  let prev: number | null = null
  for (const day of days) {
    const t = new Date(day + 'T12:00:00').getTime()
    if (prev !== null && t - prev === 86400000) run++
    else run = 1
    prev = t
    streak = Math.max(streak, run)
  }

  const tagCounts = new Map<string, number>()
  for (const s of posts) for (const t of s.tags) tagCounts.set(t.name, (tagCounts.get(t.name) ?? 0) + 1)
  const topHashtags = [...tagCounts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5)

  const interaction = new Map<string, { acct: string; id: string; avatar: string; count: number; }>()
  for (const n of data.notifications) {
    if ((n.type === 'favourite' || n.type === 'reblog') && n.account) {
      const cur = interaction.get(n.account.id) ?? { acct: n.account.acct, id: n.account.id, avatar: n.account.avatar_static, count: 0 }
      cur.count++
      interaction.set(n.account.id, cur)
    }
  }
  const topInteractions = [...interaction.values()]
    .sort((a, b) => b.count - a.count)
    .slice(0, 5)
    .map((v) => [v.acct, v.id, v.avatar, v.count] as [string, string, string, number])

  const favsReceived = data.notifications.filter((n) => n.type === 'favourite').length
  const boostsReceived = data.notifications.filter((n) => n.type === 'reblog').length

  const mostBoosted = posts.reduce<Status | null>(
    (best, s) => (s.reblogs_count > (best?.reblogs_count ?? 0) ? s : best),
    null,
  )

  return {
    postCount: posts.length,
    replies,
    favsReceived,
    boostsReceived,
    byHour,
    byWeekday,
    busiestDay,
    busiestDayCount,
    streak,
    topHashtags,
    topInteractions,
    mostBoosted: mostBoosted && mostBoosted.reblogs_count > 0 ? mostBoosted : null,
  }
}

function BarChart({ values, labels, caption, unit }: { values: number[]; labels: string[]; caption: string; unit?: string }) {
  const max = Math.max(1, ...values)
  return (
    <figure class="wrapped__chart">
      <div class="wrapped__bars" role="img" aria-label={`${caption}: ${values.join(', ')}`}>
        {values.map((v, i) => (
          <div class="wrapped__bar-col" key={i} title={`${labels[i] || i}${unit ? unit : ''}: ${v}`}>
            <div class="wrapped__bar" style={`height:${Math.max(2, Math.round((v / max) * 100))}%`} data-empty={v === 0 || undefined} />
            <span class="wrapped__bar-label">{labels[i]}</span>
          </div>
        ))}
      </div>
      <figcaption>{caption}</figcaption>
    </figure>
  )
}
