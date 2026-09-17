/**
 * Thread view: the focused status in 'detailed' form, its ancestor chain
 * above, and its descendant replies below, depth-first with connectors.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import type { Status } from '../api/types'
import { api } from '../state/auth'
import { lastPosted } from '../state/compose'
import { StatusCard } from '../components/StatusCard'
import { Icon } from '../components/Icon'
import { useTitle, navigate } from '../router'
import './Thread.css'

interface Context {
  ancestors: Status[]
  descendants: Status[]
}

type LoadState = 'loading' | 'error' | 'ready'

/** Max visual nesting depth for replies; deeper threads flatten. */
const MAX_DEPTH = 4

export function ThreadView({ id }: { id: string }) {
  useTitle('Thread')

  const [status, setStatus] = useState<Status | null>(null)
  const [ancestors, setAncestors] = useState<Status[]>([])
  const [descendants, setDescendants] = useState<Status[]>([])
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const requestSeq = useRef(0)

  async function load() {
    const seq = ++requestSeq.current
    setState('loading')
    setError(null)
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

  /** Depth-first ordered descendants with nesting depth per status. */
  function orderedReplies(): { status: Status; depth: number }[] {
    const children = new Map<string, Status[]>()
    for (const d of descendants) {
      const parent = d.in_reply_to_id
      if (!parent) continue
      const list = children.get(parent) ?? []
      list.push(d)
      children.set(parent, list)
    }
    const out: { status: Status; depth: number }[] = []
    const walk = (parentId: string, depth: number) => {
      const kids = children.get(parentId) ?? []
      kids.sort((a, b) => a.created_at.localeCompare(b.created_at))
      for (const kid of kids) {
        out.push({ status: kid, depth })
        walk(kid.id, Math.min(depth + 1, MAX_DEPTH))
      }
    }
    if (status) walk(status.id, 0)
    // Orphans (reply parent not in context — e.g. deleted): append flat.
    const seen = new Set(out.map((o) => o.status.id))
    for (const d of descendants) {
      if (!seen.has(d.id)) out.push({ status: d, depth: 0 })
    }
    return out
  }

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

      {state === 'ready' && status && (
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
          </div>

          {orderedReplies().map(({ status: reply, depth }) => (
            <div class="thread__node" key={reply.id}>
              <div class="thread-connector" aria-hidden="true" />
              <div class="thread__reply" style={depth > 0 ? { marginLeft: `calc(${depth} * var(--sp-5))` } : undefined}>
                <StatusCard
                  status={reply}
                  variant="thread"
                  onStatusUpdated={onStatusUpdated}
                  onStatusDeleted={onStatusDeleted}
                />
              </div>
            </div>
          ))}

          {descendants.length === 0 && (
            <p class="thread__no-replies">No replies yet.</p>
          )}
        </div>
      )}
    </div>
  )
}
