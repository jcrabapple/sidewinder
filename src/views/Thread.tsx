/**
 * Threadview: the focused status in 'detailed' form, its ancestor chain
 * above, and its descendant replies below, depth-first with connectors.
 * Includes engagement drawers (who boosted / liked / edit history) and
 * collapsible sub-threads.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import type { Account, Status, StatusEdit } from '../api/types'
import { api } from '../state/auth'
import { lastPosted } from '../state/compose'
import { StatusCard, replaceEmojis } from '../components/StatusCard'
import { Icon } from '../components/Icon'
import { sanitize } from '../util/sanitize'
import { formatCount, formatAbsolute } from '../util/time'
import { useTitle, navigate } from '../router'
import './Thread.css'

interface Context {
  ancestors: Status[]
  descendants: Status[]
}

type LoadState = 'loading' | 'error' | 'ready'
type DrawerKind = 'reblogged_by' | 'favourited_by' | 'history' | null

/** Max visual nesting depth for replies; deeper threads flatten. */
const MAX_DEPTH = 4

interface OrderedReply {
  status: Status
  depth: number
  childCount: number
}

export function ThreadView({ id }: { id: string }) {
  useTitle('Thread')

  const [status, setStatus] = useState<Status | null>(null)
  const [ancestors, setAncestors] = useState<Status[]>([])
  const [descendants, setDescendants] = useState<Status[]>([])
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const [drawer, setDrawer] = useState<DrawerKind>(null)
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())
  const requestSeq = useRef(0)

  async function load() {
    const seq = ++requestSeq.current
    setState('loading')
    setError(null)
    setDrawer(null)
    setCollapsed(new Set())
    try {
      const [s, ctx] = await Promise.all([
        api.value.get<Status>(`/api/v1/statuses/${encodeURIComponent(id)}`),
        api.value.get<Context>(`/api/v1/statuses/${encodeURIComponent(id)}/context`),
      ])
      if (seq !== requestSeq.current) return
      setStatus(s)
      setAncestors(ctx.ancestors)
      setDescendants(ctx.descendants)
      setState('ready')
    } catch (e) {
      if (seq !== requestSeq.current) return
      setError(e instanceof Error ? e.message : 'The thread could not be loaded.')
      setState('error')
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  function onStatusUpdated(s: Status) {
    const swap = (list: Status[]) => list.map((it) => (it.id === s.id ? s : it))
    setStatus((cur) => (cur && cur.id === s.id ? s : cur))
    setAncestors(swap)
    setDescendants(swap)
  }

  function onStatusDeleted(deletedId: string) {
    setAncestors((cur) => cur.filter((it) => it.id !== deletedId))
    setDescendants((cur) => cur.filter((it) => it.id !== deletedId))
    if (status?.id === deletedId) navigate('/', { replace: true })
  }

  // A reply posted from the composer appears immediately: when the new
  // status's parent is part of this thread, append it to the descendants.
  useEffect(() => {
    const created = lastPosted.value
    if (!created || state !== 'ready' || !status) return
    const inner = created.reblog ?? created
    const parentId = inner.in_reply_to_id
    if (!parentId) return
    const inThread =
      parentId === status.id ||
      ancestors.some((a) => a.id === parentId) ||
      descendants.some((d) => d.id === parentId)
    if (!inThread) return
    setDescendants((cur) =>
      cur.some((d) => d.id === inner.id) ? cur.map((d) => (d.id === inner.id ? inner : d)) : [...cur, inner],
    )
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lastPosted.value])

  function toggleCollapse(replyId: string) {
    setCollapsed((cur) => {
      const next = new Set(cur)
      if (next.has(replyId)) next.delete(replyId)
      else next.add(replyId)
      return next
    })
  }

  /** Depth-first ordered descendants with nesting depth and subtree counts. */
  function orderedReplies(): OrderedReply[] {
    const children = new Map<string, Status[]>()
    for (const d of descendants) {
      const parent = d.in_reply_to_id
      if (!parent) continue
      const list = children.get(parent) ?? []
      list.push(d)
      children.set(parent, list)
    }

    function countSubtree(parentId: string): number {
      const kids = children.get(parentId) ?? []
      let total = kids.length
      for (const k of kids) total += countSubtree(k.id)
      return total
    }

    const out: OrderedReply[] = []
    const walk = (parentId: string, depth: number) => {
      const kids = children.get(parentId) ?? []
      kids.sort((a, b) => a.created_at.localeCompare(b.created_at))
      for (const kid of kids) {
        const childCount = countSubtree(kid.id)
        out.push({ status: kid, depth, childCount })
        if (!collapsed.has(kid.id)) {
          walk(kid.id, Math.min(depth + 1, MAX_DEPTH))
        }
      }
    }
    if (status) walk(status.id, 0)
    // Orphans (reply parent not in context — e.g. deleted): append flat unless
    // hidden inside a collapsed ancestor.
    const seen = new Set(out.map((o) => o.status.id))
    const hiddenIds = new Set<string>()
    const markHidden = (pid: string) => {
      for (const k of children.get(pid) ?? []) {
        hiddenIds.add(k.id)
        markHidden(k.id)
      }
    }
    for (const cid of collapsed) markHidden(cid)
    for (const d of descendants) {
      if (!seen.has(d.id) && !hiddenIds.has(d.id)) {
        out.push({ status: d, depth: 0, childCount: countSubtree(d.id) })
      }
    }
    return out
  }

  const focusInner = status ? (status.reblog ?? status) : null
  const hasStatsBar =
    focusInner &&
    (focusInner.reblogs_count > 0 || focusInner.favourites_count > 0 || Boolean(focusInner.edited_at))

  return (
    <div class="thread">
      <header class="view-header">
        <button class="icon-btn" aria-label="Back" title="Back" onClick={() => history.back()}>
          <Icon name="chevron-left" size={18} />
        </button>
        <h1>Thread</h1>
        <div class="view-header__actions">
          <button class="icon-btn" aria-label="Refresh thread" title="Refresh thread" onClick={() => void load()}>
            <Icon name="refresh" size={18} />
          </button>
        </div>
      </header>

      {state === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <div class="spinner" aria-hidden="true" />
          <p>Loading thread…</p>
        </div>
      )}

      {state === 'error' && (
        <div class="state-block" role="alert">
          <h2>Thread unavailable</h2>
          <p>{error ?? 'The thread could not be loaded.'}</p>
          <button class="btn" onClick={() => void load()}>
            Try again
          </button>
        </div>
      )}

      {state === 'ready' && status && focusInner && (
        <div class="thread__list">
          {ancestors.map((s) => (
            <div class="thread__node" key={s.id}>
              <StatusCard
                status={s}
                variant="thread"
                onStatusUpdated={onStatusUpdated}
                onStatusDeleted={onStatusDeleted}
              />
              <div class="thread-connector" aria-hidden="true" />
            </div>
          ))}

          <div class="thread__focus">
            <StatusCard
              status={status}
              variant="detailed"
              onStatusUpdated={onStatusUpdated}
              onStatusDeleted={onStatusDeleted}
            />
            {hasStatsBar && (
              <div class="thread__stats-bar">
                {focusInner.reblogs_count > 0 && (
                  <button
                    class={`thread__stat-btn${drawer === 'reblogged_by' ? ' is-active' : ''}`}
                    aria-expanded={drawer === 'reblogged_by'}
                    onClick={() => setDrawer((d) => (d === 'reblogged_by' ? null : 'reblogged_by'))}
                  >
                    <strong class="num">{formatCount(focusInner.reblogs_count)}</strong>{' '}
                    {focusInner.reblogs_count === 1 ? 'boost' : 'boosts'}
                  </button>
                )}
                {focusInner.favourites_count > 0 && (
                  <button
                    class={`thread__stat-btn${drawer === 'favourited_by' ? ' is-active' : ''}`}
                    aria-expanded={drawer === 'favourited_by'}
                    onClick={() => setDrawer((d) => (d === 'favourited_by' ? null : 'favourited_by'))}
                  >
                    <strong class="num">{formatCount(focusInner.favourites_count)}</strong>{' '}
                    {focusInner.favourites_count === 1 ? 'favourite' : 'favourites'}
                  </button>
                )}
                {focusInner.edited_at && (
                  <button
                    class={`thread__stat-btn${drawer === 'history' ? ' is-active' : ''}`}
                    aria-expanded={drawer === 'history'}
                    onClick={() => setDrawer((d) => (d === 'history' ? null : 'history'))}
                  >
                    <Icon name="clock" size={14} /> Edit history
                  </button>
                )}
              </div>
            )}
            {drawer && (
              <StatusDetailDrawer
                statusId={focusInner.id}
                kind={drawer}
                onClose={() => setDrawer(null)}
              />
            )}
          </div>

          {orderedReplies().map(({ status: reply, depth, childCount }) => {
            const isCollapsed = collapsed.has(reply.id)
            return (
              <div class="thread__node" key={reply.id}>
                <div class="thread-connector" aria-hidden="true" />
                <div class="thread__reply" style={depth > 0 ? { marginLeft: `calc(${depth} * var(--sp-5))` } : undefined}>
                  <StatusCard
                    status={reply}
                    variant="thread"
                    onStatusUpdated={onStatusUpdated}
                    onStatusDeleted={onStatusDeleted}
                  />
                  {childCount > 0 && (
                    <button
                      class="btn thread__collapse-btn"
                      aria-expanded={!isCollapsed}
                      onClick={() => toggleCollapse(reply.id)}
                    >
                      <Icon name={isCollapsed ? 'chevron-down' : 'chevron-up'} size={14} />
                      {isCollapsed
                        ? `Show ${childCount} ${childCount === 1 ? 'reply' : 'replies'}`
                        : `Hide ${childCount} ${childCount === 1 ? 'reply' : 'replies'}`}
                    </button>
                  )}
                </div>
              </div>
            )
          })}

          {descendants.length === 0 && (
            <p class="thread__no-replies">No replies yet.</p>
          )}
        </div>
      )}
    </div>
  )
}

function StatusDetailDrawer({
  statusId,
  kind,
  onClose,
}: {
  statusId: string
  kind: Exclude<DrawerKind, null>
  onClose: () => void
}) {
  const [accounts, setAccounts] = useState<Account[]>([])
  const [edits, setEdits] = useState<StatusEdit[]>([])
  const [state, setState] = useState<LoadState>('loading')

  useEffect(() => {
    let alive = true
    setState('loading')
    if (kind === 'history') {
      api.value
        .get<StatusEdit[]>(`/api/v1/statuses/${encodeURIComponent(statusId)}/history`)
        .then((res) => {
          if (alive) {
            setEdits(Array.isArray(res) ? [...res].reverse() : [])
            setState('ready')
          }
        })
        .catch(() => {
          if (alive) setState('error')
        })
    } else {
      api.value
        .get<Account[]>(`/api/v1/statuses/${encodeURIComponent(statusId)}/${kind}`)
        .then((res) => {
          if (alive) {
            setAccounts(Array.isArray(res) ? res : [])
            setState('ready')
          }
        })
        .catch(() => {
          if (alive) setState('error')
        })
    }
    return () => {
      alive = false
    }
  }, [statusId, kind])

  const title =
    kind === 'reblogged_by'
      ? 'Boosted by'
      : kind === 'favourited_by'
        ? 'Favourited by'
        : 'Edit history'

  return (
    <section class="thread__drawer card" aria-label={title}>
      <div class="thread__drawer-head">
        <h3>{title}</h3>
        <button class="icon-btn" aria-label="Close" title="Close" onClick={onClose}>
          <Icon name="x" size={16} />
        </button>
      </div>
      {state === 'loading' && (
        <p class="help-text" role="status" aria-busy="true">
          Loading…
        </p>
      )}
      {state === 'error' && (
        <p class="help-text" role="alert">
          Could not load {title.toLowerCase()}.
        </p>
      )}
      {state === 'ready' && kind !== 'history' && (
        accounts.length === 0 ? (
          <p class="help-text">No accounts to show.</p>
        ) : (
          <ul class="thread__drawer-accounts">
            {accounts.map((a) => (
              <li key={a.id}>
                <a class="thread__drawer-account" href={`#/accounts/${encodeURIComponent(a.id)}`}>
                  <img src={a.avatar_static || a.avatar} alt="" width={32} height={32} loading="lazy" />
                  <span class="thread__drawer-names">
                    <strong>{replaceEmojis(a.display_name || a.username, a.emojis)}</strong>
                    <span>@{a.acct}</span>
                  </span>
                </a>
              </li>
            ))}
          </ul>
        )
      )}
      {state === 'ready' && kind === 'history' && (
        edits.length === 0 ? (
          <p class="help-text">No edit history recorded.</p>
        ) : (
          <ol class="thread__drawer-edits">
            {edits.map((ed, idx) => (
              <li key={ed.created_at + idx} class="thread__drawer-edit">
                <div class="thread__drawer-edit-meta">
                  <strong>{idx === 0 ? 'Latest version' : `Revision ${edits.length - idx}`}</strong>
                  <time dateTime={ed.created_at}>{formatAbsolute(ed.created_at)}</time>
                </div>
                {ed.spoiler_text && (
                  <p class="thread__drawer-edit-cw">CW: {ed.spoiler_text}</p>
                )}
                <div
                  class="status-content thread__drawer-edit-body"
                  dangerouslySetInnerHTML={{ __html: sanitize(ed.content, api.value.base) }}
                />
              </li>
            ))}
          </ol>
        )
      )}
    </section>
  )
}
