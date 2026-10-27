/**
 * Moderation view: the admin's work surface.
 *
 * Tabs: Signups (approve/deny + email confirm), Reports (resolve, optionally
 * suspend the reported account), Accounts (search + suspend), Domains
 * (blocklist CRUD). Everything here needs the admin OAuth scope; without it
 * the view renders a hint pointing at Settings instead of raw 403s.
 *
 * GTS 0.22 facts baked into this view:
 *  - Signup approve is two steps (API approve + the local email-confirm
 *    helper) because there is no confirm API. Handled by state/admin.ts.
 *  - The account action endpoint supports `type=suspend` only, and there is
 *    NO API to undo a suspend (that takes the server CLI). Every suspend
 *    button warns before firing and the UI says the undo path is the CLI.
 *  - Report resolve carries an optional action_taken_comment that is shown
 *    to the reporter.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import {
  fetchPendingSignups,
  signupReason,
  approveSignup,
  denySignup,
  fetchReports,
  resolveReport,
  suspendAccount,
  searchAdminAccounts,
  fetchDomainBlocks,
  createDomainBlock,
  deleteDomainBlock,
  hasAdminScope,
  type AdminAccount,
  type AdminReport,
  type AdminDomainBlock,
} from '../state/admin'
import { toast } from '../state/toast'
import { Icon } from '../components/Icon'
import { useTitle, navigate } from '../router'
import { formatRelative, formatAbsolute } from '../util/time'
import './Moderation.css'

type LoadState = 'loading' | 'error' | 'ready'
type Tab = 'signups' | 'reports' | 'accounts' | 'domains'
type AccountMode = 'all' | 'local' | 'remote' | 'suspended'

export function ModerationView() {
  useTitle('Moderation')
  const [tab, setTab] = useState<Tab>('signups')
  const canModerate = hasAdminScope()

  return (
    <div class="moderation">
      <header class="view-header">
        <h1>Moderation</h1>
      </header>

      {!canModerate ? (
        <div class="state-block" role="status">
          <h2>Moderation tools are not enabled for this session</h2>
          <p>
            Approving signups, handling reports, suspending accounts, and domain blocks all need
            an admin-scoped token. Enable them in{' '}
            <a
              href="#/settings"
              onClick={(e) => {
                e.preventDefault()
                navigate('/settings')
              }}
            >
              Settings
            </a>
            .
          </p>
        </div>
      ) : (
        <>
          <nav class="moderation__tabs" role="tablist" aria-label="Moderation sections">
            {(
              [
                ['signups', 'Signups'],
                ['reports', 'Reports'],
                ['accounts', 'Accounts'],
                ['domains', 'Domains'],
              ] as [Tab, string][]
            ).map(([key, label]) => (
              <button
                key={key}
                role="tab"
                class={`moderation__tab${tab === key ? ' is-active' : ''}`}
                aria-selected={tab === key}
                onClick={() => setTab(key)}
              >
                {label}
              </button>
            ))}
          </nav>
          {tab === 'signups' && <SignupsTab />}
          {tab === 'reports' && <ReportsTab />}
          {tab === 'accounts' && <AccountsTab />}
          {tab === 'domains' && <DomainsTab />}
        </>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Signups
// ---------------------------------------------------------------------------

function SignupsTab() {
  const [items, setItems] = useState<AdminAccount[]>([])
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)

  async function load() {
    setState('loading')
    setError(null)
    try {
      setItems(await fetchPendingSignups())
      setState('ready')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Signups could not be loaded.')
      setState('error')
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (state === 'loading') {
    return (
      <div class="state-block" role="status" aria-busy="true">
        <div class="spinner" aria-hidden="true" />
        <p>Loading pending signups…</p>
      </div>
    )
  }
  if (state === 'error') {
    return (
      <div class="state-block" role="alert">
        <h2>Signups unavailable</h2>
        <p>{error}</p>
        <button class="btn" onClick={() => void load()}>
          Try again
        </button>
      </div>
    )
  }
  if (items.length === 0) {
    return (
      <div class="state-block">
        <h2>No pending signups</h2>
        <p>New signups also arrive as notifications with Approve and Deny buttons.</p>
      </div>
    )
  }
  return (
    <div class="moderation__list" aria-busy="false">
      <p class="help-text">
        {items.length} pending signup{items.length === 1 ? '' : 's'}. Approve also confirms their
        email (the step GoToSocial has no API for — done via this instance's helper).
      </p>
      {items.map((a) => {
        const name = a.account.display_name || a.account.username
        const reason = signupReason(a)
        return (
          <article class="moderation__card card" aria-label={`Signup from @${a.account.acct}`}>
            <div class="moderation__card-head">
              <a class="moderation__avatar" href={`#/accounts/${a.account.id}`}>
                <img src={a.account.avatar_static} alt="" width={40} height={40} loading="lazy" />
              </a>
              <div class="moderation__card-names">
                <a class="moderation__card-name" href={`#/accounts/${a.account.id}`}>
                  {name}
                </a>
                <span class="moderation__card-handle">@{a.account.acct}</span>
              </div>
              <span class="moderation__when" title={formatAbsolute(a.created_at)}>
                <time dateTime={a.created_at}>{formatRelative(a.created_at)}</time>
              </span>
            </div>
            {reason ? (
              <blockquote class="moderation__reason">
                {reason}
              </blockquote>
            ) : (
              <p class="help-text">No signup reason was provided.</p>
            )}
            <SignupActions account={a} onDone={removeItem} />
          </article>
        )
      })}
      {error && <p class="error-note" role="alert">{error}</p>}
    </div>
  )

  function removeItem(id: string) {
    setItems((cur) => cur.filter((a) => a.id !== id))
  }
}

function SignupActions({ account, onDone }: { account: AdminAccount; onDone: (id: string) => void }) {
  const [busy, setBusy] = useState<'approve' | 'deny' | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function decide(decision: 'approve' | 'deny') {
    if (busy) return
    if (
      decision === 'deny' &&
      !window.confirm(`Deny @${account.account.acct}'s signup? Their pending account will be removed.`)
    ) {
      return
    }
    setBusy(decision)
    setError(null)
    try {
      if (decision === 'approve') {
        const result = await approveSignup(account)
        if (result.status === 'confirmed') {
          toast(`@${account.account.acct} approved — they can sign in now`)
        } else {
          toast(result.warning, 'error')
        }
      } else {
        await denySignup(account.id)
        toast(`@${account.account.acct}'s signup denied`)
      }
      onDone(account.id)
    } catch (e) {
      setError(e instanceof Error ? e.message : `Could not ${decision} the signup.`)
    } finally {
      setBusy(null)
    }
  }

  return (
    <div class="moderation__card-actions">
      <button class="btn btn--primary" disabled={busy !== null} onClick={() => void decide('approve')}>
        <Icon name="check" size={16} />
        {busy === 'approve' ? 'Approving…' : 'Approve'}
      </button>
      <button class="btn btn--danger" disabled={busy !== null} onClick={() => void decide('deny')}>
        <Icon name="x" size={16} />
        {busy === 'deny' ? 'Denying…' : 'Deny'}
      </button>
      {error && <p class="error-note" role="alert">{error}</p>}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Reports
// ---------------------------------------------------------------------------

function ReportsTab() {
  const [resolved, setResolved] = useState(false)
  const [items, setItems] = useState<AdminReport[]>([])
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const [nextMaxId, setNextMaxId] = useState<string | null>(null)
  const seq = useRef(0)

  async function load(showResolved: boolean) {
    const s = ++seq.current
    setState('loading')
    setError(null)
    setItems([])
    setNextMaxId(null)
    try {
      const page = await fetchReports(showResolved)
      if (s !== seq.current) return
      setItems(page.items)
      setNextMaxId(page.nextMaxId)
      setState('ready')
    } catch (e) {
      if (s !== seq.current) return
      setError(e instanceof Error ? e.message : 'Reports could not be loaded.')
      setState('error')
    }
  }

  useEffect(() => {
    void load(resolved)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resolved])

  function removeOrReplace(r: AdminReport) {
    setItems((cur) => {
      if (resolved) {
        // Updating the resolved list: swap in the fresh copy.
        const i = cur.findIndex((x) => x.id === r.id)
        if (i < 0) return cur
        const copy = cur.slice()
        copy[i] = r
        return copy
      }
      return cur.filter((x) => x.id !== r.id)
    })
  }

  async function loadMore() {
    if (!nextMaxId) return
    try {
      const page = await fetchReports(resolved, nextMaxId)
      setItems((cur) => {
        const seen = new Set(cur.map((r) => r.id))
        return [...cur, ...page.items.filter((r) => !seen.has(r.id))]
      })
      setNextMaxId(page.nextMaxId)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'More reports could not be loaded.')
    }
  }

  return (
    <div class="moderation__reports">
      <div class="moderation__filter" role="radiogroup" aria-label="Report state">
        <button
          class={`moderation__filter-btn${resolved ? '' : ' is-active'}`}
          aria-pressed={!resolved}
          onClick={() => setResolved(false)}
        >
          Open
        </button>
        <button
          class={`moderation__filter-btn${resolved ? ' is-active' : ''}`}
          aria-pressed={resolved}
          onClick={() => setResolved(true)}
        >
          Resolved
        </button>
        <button class="icon-btn" aria-label={`Refresh ${resolved ? 'resolved' : 'open'} reports`} title="Refresh" onClick={() => void load(resolved)}>
          <Icon name="refresh" size={16} />
        </button>
      </div>

      {state === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <div class="spinner" aria-hidden="true" />
          <p>Loading {resolved ? 'resolved' : 'open'} reports…</p>
        </div>
      )}
      {state === 'error' && (
        <div class="state-block" role="alert">
          <h2>Reports unavailable</h2>
          <p>{error}</p>
          <button class="btn" onClick={() => void load(resolved)}>
            Try again
          </button>
        </div>
      )}
      {state === 'ready' && items.length === 0 && (
        <div class="state-block">
          <h2>{resolved ? 'No resolved reports' : 'No open reports'}</h2>
          <p>
            Reports filed by your users arrive here and as notifications.{' '}
            {!resolved && 'New reports also push a notification if enabled in Settings.'}
          </p>
        </div>
      )}
      {state === 'ready' && items.length > 0 && (
        <div class="moderation__list">
          {items.map((r) => (
            <ReportRow key={r.id} report={r} onUpdate={removeOrReplace} />
          ))}
          {nextMaxId && (
            <button class="btn moderation__more" onClick={() => void loadMore()}>
              Load more
            </button>
          )}
        </div>
      )}
      {error && <p class="error-note" role="alert">{error}</p>}
    </div>
  )
}

function ReportRow({
  report,
  onUpdate,
}: {
  report: AdminReport
  onUpdate: (r: AdminReport) => void
}) {
  const [busy, setBusy] = useState<null | 'resolve' | 'suspend'>(null)
  const [comment, setComment] = useState('')
  const [error, setError] = useState<string | null>(null)
  const reporter = report.account
  const target = report.target_account
  const targetName = target.display_name || target.username

  async function doResolve(withSuspend?: boolean) {
    if (busy) return
    if (withSuspend) {
      const undoNote = target.domain
        ? `Suspend @${target.acct} and mark the report resolved?\n\nNOTE: GoToSocial has no API to undo a suspend. Lifting one takes the server CLI.`
        : `Suspend @${target.acct} and mark the report resolved?\n\nNOTE: GoToSocial has no API to undo a suspend. Lifting one takes the server CLI (admin account unsuspend).`
      if (!window.confirm(undoNote)) return
    }
    setBusy(withSuspend ? 'suspend' : 'resolve')
    setError(null)
    try {
      if (withSuspend) {
        await suspendAccount(target.id, `Report ${report.id}`)
      }
      await resolveReport(report.id, comment)
      toast(`Report resolved${withSuspend ? ' — @' + target.acct + ' suspended' : ''}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not resolve the report.')
    } finally {
      setBusy(null)
    }
  }

  return (
    <article class="moderation__card card" aria-label={`Report from @${reporter.acct}`}>
      <div class="moderation__card-head">
        <a class="moderation__avatar" href={`#/accounts/${reporter.id}`}>
          <img src={reporter.avatar_static} alt="" width={40} height={40} loading="lazy" />
        </a>
        <div class="moderation__card-names">
          <span class="moderation__card-text">
            <a class="moderation__card-name" href={`#/accounts/${reporter.id}`}>{reporter.display_name || reporter.username}</a>
            {' reported '}
            <a class="moderation__card-name" href={`#/accounts/${target.id}`}>{targetName}</a>
            {target.domain ? <span class="moderation__card-handle"> ({target.acct})</span> : null}
          </span>
        </div>
        <span class="moderation__when" title={formatAbsolute(report.created_at)}>
          <time dateTime={report.created_at}>{formatRelative(report.created_at)}</time>
        </span>
      </div>

      {report.comment.trim() && <blockquote class="moderation__reason">{report.comment}</blockquote>}

      {report.statuses.length > 0 && (
        <p class="help-text">
          {report.statuses.length} reported post{report.statuses.length === 1 ? '' : 's'}:{' '}
          {report.statuses.map((s, i) => (
            <span key={s.id}>
              {i > 0 && ', '}
              <a href={`#/statuses/${s.id}`}>#{i + 1}</a>
            </span>
          ))}
        </p>
      )}
      {report.action_taken && (
        <p class="help-text moderation__resolved-note">
          {report.action_taken_at && <time dateTime={report.action_taken_at}>{formatRelative(report.action_taken_at)} · </time>}
          {report.action_taken_comment?.trim() ?? 'Resolved (no comment recorded)'}
        </p>
      )}

      {!report.action_taken && (
        <div class="moderation__card-actions">
          <input
            class="input moderation__reason-input"
            type="text"
            placeholder="Action comment (visible to the reporter)"
            value={comment}
            onInput={(e) => setComment((e.target as HTMLInputElement).value)}
            maxLength={1000}
          />
          <div class="moderation__btn-row">
            <button class="btn" disabled={busy !== null} onClick={() => void doResolve()}>
              {busy === 'resolve' ? 'Resolving…' : 'Resolve'}
            </button>
            <button class="btn btn--danger" disabled={busy !== null} onClick={() => void doResolve(true)}>
              <Icon name="shield" size={16} />
              {busy === 'suspend' ? 'Suspending…' : 'Suspend + resolve'}
            </button>
          </div>
          <p class="help-text">
            Suspending needs the admin scope and cannot be undone from here — lifting a suspend
            takes the server CLI.
          </p>
        </div>
      )}
      {error && <p class="error-note" role="alert">{error}</p>}
    </article>
  )
}

// ---------------------------------------------------------------------------
// Accounts
// ---------------------------------------------------------------------------

function AccountsTab() {
  const [query, setQuery] = useState('')
  const [imap, setImap] = useState<AccountMode>('all')
  const [items, setItems] = useState<AdminAccount[]>([])
  const [state, setState] = useState<LoadState | 'idle'>('idle')
  const [error, setError] = useState<string | null>(null)
  const [nextMaxId, setNextMaxId] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const requestSeq = useRef(0)
  const criteria = useRef<{ query: string; mode: AccountMode }>({ query: '', mode: 'all' })

  async function run(q: string, mode: AccountMode) {
    const seq = ++requestSeq.current
    criteria.current = { query: q, mode }
    setState('loading')
    setError(null)
    setLoadingMore(false)
    setItems([])
    setNextMaxId(null)
    try {
      const page = await searchAdminAccounts({
        username: q.trim() || undefined,
        remote: mode === 'remote' ? true : undefined,
        suspended: mode === 'suspended' ? true : undefined,
      })
      if (seq !== requestSeq.current) return
      setItems(page.items)
      setNextMaxId(page.nextMaxId)
      setState('ready')
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : 'Account search failed.')
      setState('error')
    }
  }

  async function loadMore() {
    if (!nextMaxId || loadingMore) return
    const seq = ++requestSeq.current
    const cursor = nextMaxId
    const { query: q, mode } = criteria.current
    setLoadingMore(true)
    setError(null)
    try {
      const page = await searchAdminAccounts({
        username: q.trim() || undefined,
        remote: mode === 'remote' ? true : undefined,
        suspended: mode === 'suspended' ? true : undefined,
        maxId: cursor,
      })
      if (seq !== requestSeq.current) return
      setItems((cur) => {
        const seen = new Set(cur.map((x) => x.id))
        return [...cur, ...page.items.filter((x) => !seen.has(x.id))]
      })
      setNextMaxId(page.nextMaxId)
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : 'More accounts could not be loaded.')
    } finally {
      if (seq === requestSeq.current) setLoadingMore(false)
    }
  }

  return (
    <div class="moderation__accounts">
      <form
        class="moderation__search"
        onSubmit={(e) => {
          e.preventDefault()
          void run(query, imap)
        }}
      >
        <input
          class="input"
          type="search"
          placeholder="Search accounts by username…"
          aria-label="Search accounts by username"
          value={query}
          onInput={(e) => setQuery((e.target as HTMLInputElement).value)}
        />
        <select
          class="select"
          aria-label="Account scope"
          value={imap}
          onChange={(e) => {
            const v = (e.target as HTMLSelectElement).value as AccountMode
            setImap(v)
            void run(query, v)
          }}
        >
          <option value="all">All accounts</option>
          <option value="local">Local only</option>
          <option value="remote">Remote only</option>
          <option value="suspended">Suspended</option>
        </select>
        <button class="btn" type="submit">
          <Icon name="search" size={16} />
          Search
        </button>
      </form>

      {state === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <div class="spinner" aria-hidden="true" />
          <p>Searching…</p>
        </div>
      )}
      {state === 'error' && <p class="error-note" role="alert">{error}</p>}
      {state === 'ready' && items.length === 0 && (
        <div class="state-block">
          <h2>No matching accounts</h2>
          <p>Nothing matched that search.</p>
        </div>
      )}
      {state === 'ready' && items.length > 0 && (
        <div class="moderation__list" aria-busy={loadingMore}>
          {items.map((a) => (
            <AccountRow key={a.id} account={a} />
          ))}
          {error && <p class="error-note" role="alert">{error}</p>}
          {nextMaxId && (
            <button
              class="btn moderation__more"
              disabled={loadingMore}
              onClick={() => void loadMore()}
            >
              {loadingMore ? 'Loading…' : 'Load more accounts'}
            </button>
          )}
        </div>
      )}
    </div>
  )
}

function AccountRow({ account }: { account: AdminAccount }) {
  const [busy, setBusy] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  // GTS admin account rows carry `suspended` on the v2 model; AdminAccount
  // here is the v1 signup shape without it, so the row does not try to render
  // a suspended badge and the confirm dialog carries the guard instead.
  const acct = account.account.acct
  const isRemote = account.domain !== null

  async function doSuspend() {
    if (busy) return
    const note = isRemote
      ? `Suspend ${acct} (their instance still hosts the account; this instance stops ingesting their content)?\n\nNOTE: GoToSocial has no API to undo a suspend. Lifting one takes the server CLI.`
      : `Suspend @${acct}? Their profile becomes inaccessible and their content is dropped.\n\nNOTE: GoToSocial has no API to undo a suspend. Lifting one takes the server CLI (admin account unsuspend).`
    if (!window.confirm(note)) return
    setBusy(true)
    setError(null)
    try {
      await suspendAccount(account.id, reason)
      toast(`${acct} suspended`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not suspend the account.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <article class="moderation__card card" aria-label={`Account @${acct}`}>
      <div class="moderation__card-head">
        <a class="moderation__avatar" href={`#/accounts/${account.id}`}>
          <img src={account.account.avatar_static} alt="" width={40} height={40} loading="lazy" />
        </a>
        <div class="moderation__card-names">
          <a class="moderation__card-name" href={`#/accounts/${account.id}`}>
            {account.account.display_name || account.account.username}
          </a>
          <span class="moderation__card-handle">{acct}</span>
        </div>
        {account.created_at && (
          <span class="moderation__when" title={formatAbsolute(account.created_at)}>
            <time dateTime={account.created_at}>{formatRelative(account.created_at)}</time>
          </span>
        )}
      </div>
      <div class="moderation__card-actions">
        <input
          class="input moderation__reason-input"
          type="text"
          placeholder="Why (recorded with the action)"
          value={reason}
          onInput={(e) => setReason((e.target as HTMLInputElement).value)}
          maxLength={500}
        />
        <div class="moderation__btn-row">
          <button class="btn btn--danger" disabled={busy} onClick={() => void doSuspend()}>
            <Icon name="shield" size={16} />
            {busy ? 'Suspending…' : 'Suspend'}
          </button>
        </div>
        {error && <p class="error-note" role="alert">{error}</p>}
      </div>
    </article>
  )
}

// ---------------------------------------------------------------------------
// Domain blocks
// ---------------------------------------------------------------------------

function DomainsTab() {
  const [items, setItems] = useState<AdminDomainBlock[]>([])
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const [domain, setDomain] = useState('')
  const [publicComment, setPublicComment] = useState('')
  const [obfuscate, setObfuscate] = useState(false)
  const [adding, setAdding] = useState(false)
  const [nextMaxId, setNextMaxId] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const requestSeq = useRef(0)

  async function load() {
    const seq = ++requestSeq.current
    setState('loading')
    setError(null)
    setLoadingMore(false)
    setNextMaxId(null)
    try {
      const page = await fetchDomainBlocks()
      if (seq !== requestSeq.current) return
      setItems(page.items)
      setNextMaxId(page.nextMaxId)
      setState('ready')
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : 'Domain blocks could not be loaded.')
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
      const page = await fetchDomainBlocks(cursor)
      if (seq !== requestSeq.current) return
      setItems((cur) => {
        const seen = new Set(cur.map((block) => block.id))
        return [...cur, ...page.items.filter((block) => !seen.has(block.id))]
      })
      setNextMaxId(page.nextMaxId)
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : 'More domain blocks could not be loaded.')
    } finally {
      if (seq === requestSeq.current) setLoadingMore(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function add(e: Event) {
    e.preventDefault()
    const d = domain.trim()
    if (!d) return
    setAdding(true)
    setError(null)
    try {
      await createDomainBlock(d, { publicComment, obfuscate })
      toast(`${d} blocked`)
      setDomain('')
      setPublicComment('')
      await load()
    } catch (e2) {
      setError(e2 instanceof Error ? e2.message : `Could not block ${d}.`)
    } finally {
      setAdding(false)
    }
  }

  function remove(b: AdminDomainBlock) {
    if (!window.confirm(`Unblock ${b.domain}? Their posts and accounts can federate again immediately.`)) return
    void deleteDomainBlock(b.id)
      .then(() => {
        toast(`${b.domain} unblocked`)
        setItems((cur) => cur.filter((x) => x.id !== b.id))
      })
      .catch((e: unknown) => toast(e instanceof Error ? e.message : `Could not unblock ${b.domain}.`, 'error'))
  }

  return (
    <div class="moderation__domains">
      <form class="moderation__search" onSubmit={add}>
        <input
          class="input"
          type="text"
          placeholder="domain.example"
          aria-label="Domain to block"
          value={domain}
          onInput={(e) => setDomain((e.target as HTMLInputElement).value)}
          required
        />
        <input
          class="input"
          type="text"
          placeholder="Public comment (optional)"
          aria-label="Public comment"
          value={publicComment}
          onInput={(e) => setPublicComment((e.target as HTMLInputElement).value)}
          maxLength={1000}
        />
        <label class="moderation__obfuscate">
          <input
            type="checkbox"
            checked={obfuscate}
            onChange={(e) => setObfuscate((e.target as HTMLInputElement).checked)}
          />
          Obfuscate name publicly
        </label>
        <button class="btn btn--danger" type="submit" disabled={adding}>
          <Icon name="shield" size={16} />
          {adding ? 'Blocking…' : 'Block domain'}
        </button>
      </form>
      <p class="help-text">
        Blocking a domain stops fetching anything from it: profiles, posts, boosts, media. Undo is
        instant, from this list.
      </p>

      {state === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <div class="spinner" aria-hidden="true" />
          <p>Loading domain blocks…</p>
        </div>
      )}
      {state === 'error' && (
        <div class="state-block" role="alert">
          <h2>Domain blocks unavailable</h2>
          <p>{error}</p>
          <button class="btn" onClick={() => void load()}>
            Try again
          </button>
        </div>
      )}
      {state === 'ready' && items.length === 0 && (
        <div class="state-block">
          <h2>No domains blocked</h2>
          <p>The federated timeline will carry every domain that links to this instance.</p>
        </div>
      )}
      {state === 'ready' && items.length > 0 && (
        <>
          <ul class="moderation__domains-list" aria-busy={loadingMore}>
            {items.map((b) => (
              <li key={b.id} class="moderation__domains-row">
                <span class="moderation__domains-name">{b.domain}</span>
                {b.public_comment && <span class="moderation__domains-comment">{b.public_comment}</span>}
                <button class="icon-btn" aria-label={`Unblock ${b.domain}`} title="Unblock" onClick={() => remove(b)}>
                  <Icon name="trash" size={16} />
                </button>
              </li>
            ))}
          </ul>
          {nextMaxId && (
            <button class="btn moderation__more" disabled={loadingMore} onClick={() => void loadMore()}>
              {loadingMore ? 'Loading…' : 'Load more domain blocks'}
            </button>
          )}
        </>
      )}
      {error && state !== 'error' && <p class="error-note" role="alert">{error}</p>}
    </div>
  )
}
