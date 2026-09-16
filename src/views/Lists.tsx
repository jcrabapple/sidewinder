/**
 * Lists management: browse, create, rename, and delete lists, plus a members
 * dialog (search accounts to add, remove current members). The list timeline
 * itself lives in Timelines.tsx at #/lists/:id.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import type { List } from '../api/types'
import { api } from '../state/auth'
import { toast } from '../state/toast'
import { Icon } from '../components/Icon'
import { useTitle } from '../router'
import { ListMembersDialog } from './ListMembers'
import './Lists.css'

type LoadState = 'loading' | 'error' | 'ready'

export function ListsView() {
  useTitle('Lists')
  const [lists, setLists] = useState<List[]>([])
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<List | 'new' | null>(null)
  const [membersOf, setMembersOf] = useState<List | null>(null)

  async function load() {
    setState('loading')
    setError(null)
    try {
      const res = await api.value.get<List[]>('/api/v1/lists')
      setLists(res)
      setState('ready')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Your lists could not be loaded.')
      setState('error')
    }
  }

  useEffect(() => {
    void load()
  }, [])

  async function removeList(list: List) {
    if (!window.confirm(`Delete the list “${list.title}”? This cannot be undone.`)) return
    try {
      await api.value.delete(`/api/v1/lists/${encodeURIComponent(list.id)}`)
      setLists((cur) => cur.filter((l) => l.id !== list.id))
      toast(`Deleted “${list.title}”`)
    } catch (e) {
      toast(e instanceof Error ? e.message : 'The list could not be deleted.', 'error')
    }
  }

  return (
    <div class="lists">
      <header class="view-header">
        <h1>Lists</h1>
        <div class="view-header__actions">
          <button class="btn btn--primary" onClick={() => setEditing('new')}>
            <Icon name="plus" size={16} /> New list
          </button>
        </div>
      </header>

      {state === 'loading' && (
        <div class="state-block" role="status" aria-busy="true">
          <div class="spinner" aria-hidden="true" />
          <p>Loading lists…</p>
        </div>
      )}

      {state === 'error' && (
        <div class="state-block" role="alert">
          <h2>Lists unavailable</h2>
          <p>{error ?? 'Your lists could not be loaded.'}</p>
          <button class="btn" onClick={() => void load()}>Try again</button>
        </div>
      )}

      {state === 'ready' && lists.length === 0 && (
        <div class="state-block">
          <h2>No lists yet</h2>
          <p>Lists are timelines made of accounts you choose. Create one to follow a topic or group without the noise.</p>
        </div>
      )}

      {state === 'ready' && lists.length > 0 && (
        <ul class="lists__rows">
          {lists.map((list) => (
            <li class="lists__row card" key={list.id}>
              <a class="lists__title" href={`#/lists/${encodeURIComponent(list.id)}`}>
                <Icon name="list" size={18} />
                <span>{list.title}</span>
              </a>
              <div class="lists__row-actions">
                <button
                  class="icon-btn"
                  aria-label={`Manage members of ${list.title}`}
                  title="Manage members"
                  onClick={() => setMembersOf(list)}
                >
                  <Icon name="users" size={18} />
                </button>
                <button
                  class="icon-btn"
                  aria-label={`Rename ${list.title}`}
                  title="Rename list"
                  onClick={() => setEditing(list)}
                >
                  <Icon name="pencil" size={18} />
                </button>
                <button
                  class="icon-btn"
                  aria-label={`Delete ${list.title}`}
                  title="Delete list"
                  onClick={() => void removeList(list)}
                >
                  <Icon name="trash" size={18} />
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {editing !== null && (
        <ListEditDialog
          list={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(saved, isNew) => {
            setEditing(null)
            setLists((cur) =>
              isNew ? [...cur, saved] : cur.map((l) => (l.id === saved.id ? saved : l)),
            )
          }}
        />
      )}

      {membersOf && <ListMembersDialog list={membersOf} onClose={() => setMembersOf(null)} />}
    </div>
  )
}

/** Create / rename dialog. Native <dialog>, focus returns on close. */
function ListEditDialog({
  list,
  onClose,
  onSaved,
}: {
  list: List | null
  onClose: () => void
  onSaved: (list: List, isNew: boolean) => void
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const [title, setTitle] = useState(list?.title ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const dlg = ref.current
    if (!dlg) return
    const prevFocus = document.activeElement as HTMLElement | null
    dlg.showModal()
    const input = dlg.querySelector('input')
    input?.focus()
    const onCancel = () => onClose()
    dlg.addEventListener('cancel', onCancel)
    return () => {
      dlg.removeEventListener('cancel', onCancel)
      prevFocus?.focus()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function save(e: Event) {
    e.preventDefault()
    const trimmed = title.trim()
    if (!trimmed || busy) return
    setBusy(true)
    setError(null)
    try {
      const saved = list
        ? await api.value.put<List>(`/api/v1/lists/${encodeURIComponent(list.id)}`, { title: trimmed })
        : await api.value.post<List>('/api/v1/lists', { title: trimmed })
      toast(list ? 'List renamed' : `Created “${saved.title}”`)
      onSaved(saved, !list)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'The list could not be saved.')
      setBusy(false)
    }
  }

  return (
    <dialog class="sw-dialog lists__dialog" ref={ref} aria-labelledby="list-edit-title">
      <form method="dialog" onSubmit={save}>
        <div class="sw-dialog__head">
          <h2 id="list-edit-title">{list ? 'Rename list' : 'New list'}</h2>
        </div>
        <div class="sw-dialog__body">
          <label class="label" for="list-title-input">List name</label>
          <input
            id="list-title-input"
            class="input"
            value={title}
            onInput={(e) => setTitle((e.target as HTMLInputElement).value)}
            maxLength={100}
            required
            placeholder="e.g. Local news"
          />
          {error && <p class="field-error" role="alert">{error}</p>}
          <div class="lists__dialog-actions">
            <button type="button" class="btn" onClick={onClose} disabled={busy}>Cancel</button>
            <button type="submit" class="btn btn--primary" disabled={busy || !title.trim()}>
              {busy ? 'Saving…' : list ? 'Rename' : 'Create list'}
            </button>
          </div>
        </div>
      </form>
    </dialog>
  )
}
