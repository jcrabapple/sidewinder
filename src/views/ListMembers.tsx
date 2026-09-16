/**
 * List members dialog: view current members, remove them, and search for
 * accounts to add. GTS accepts repeated account_ids[] params on add/remove.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import type { Account, List } from '../api/types'
import { api } from '../state/auth'
import { toast } from '../state/toast'
import { Icon } from '../components/Icon'
import './Lists.css'

export function ListMembersDialog({ list, onClose }: { list: List; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  const [members, setMembers] = useState<Account[] | null>(null)
  const [membersError, setMembersError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<Account[]>([])
  const [searching, setSearching] = useState(false)
  const searchSeq = useRef(0)

  useEffect(() => {
    const dlg = ref.current
    if (!dlg) return
    dlg.showModal()
    const onCancel = () => onClose()
    dlg.addEventListener('cancel', onCancel)
    return () => dlg.removeEventListener('cancel', onCancel)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function loadMembers() {
    setMembersError(null)
    try {
      const res = await api.value.get<Account[]>(
        `/api/v1/lists/${encodeURIComponent(list.id)}/accounts`,
        { limit: 0 },
      )
      setMembers(res)
    } catch (e) {
      setMembersError(e instanceof Error ? e.message : 'Members could not be loaded.')
      setMembers([])
    }
  }

  useEffect(() => {
    void loadMembers()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [list.id])

  // Debounced account search for adding members.
  useEffect(() => {
    const q = query.trim()
    if (q.length < 2) {
      setResults([])
      setSearching(false)
      return
    }
    setSearching(true)
    const seq = ++searchSeq.current
    const t = setTimeout(() => {
      api.value
        .get<{ accounts: Account[] }>('/api/v1/search', { q, type: 'accounts', limit: 5 })
        .then((res) => {
          if (seq === searchSeq.current) {
            setResults(res.accounts)
            setSearching(false)
          }
        })
        .catch(() => {
          if (seq === searchSeq.current) {
            setResults([])
            setSearching(false)
          }
        })
    }, 300)
    return () => clearTimeout(t)
  }, [query])

  async function add(account: Account) {
    try {
      await api.value.post(
        `/api/v1/lists/${encodeURIComponent(list.id)}/accounts?account_ids[]=${encodeURIComponent(account.id)}`,
      )
      toast(`Added @${account.acct} to “${list.title}”`)
      setQuery('')
      setResults([])
      void loadMembers()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'That account could not be added.', 'error')
    }
  }

  async function remove(account: Account) {
    try {
      await api.value.delete(
        `/api/v1/lists/${encodeURIComponent(list.id)}/accounts?account_ids[]=${encodeURIComponent(account.id)}`,
      )
      setMembers((cur) => (cur ? cur.filter((a) => a.id !== account.id) : cur))
      toast(`Removed @${account.acct} from “${list.title}”`)
    } catch (e) {
      toast(e instanceof Error ? e.message : 'That account could not be removed.', 'error')
    }
  }

  const memberIds = new Set((members ?? []).map((a) => a.id))

  return (
    <dialog class="sw-dialog" ref={ref} aria-labelledby="list-members-title">
      <div class="sw-dialog__head">
        <h2 id="list-members-title">Members of {list.title}</h2>
      </div>
      <div class="sw-dialog__body">
        <label class="label" for="list-member-search">Add an account</label>
        <input
          id="list-member-search"
          class="input"
          type="search"
          value={query}
          onInput={(e) => setQuery((e.target as HTMLInputElement).value)}
          placeholder="Search by name or @handle"
          aria-describedby="list-member-search-help"
        />
        <p class="help-text" id="list-member-search-help">
          {searching ? 'Searching…' : 'Type at least two characters to search.'}
        </p>

        {results.length > 0 && (
          <ul class="lists__search-results">
            {results.map((a) => (
              <li class="lists__member" key={a.id}>
                <img class="lists__member-avatar" src={a.avatar_static} alt="" width={36} height={36} loading="lazy" />
                <div class="lists__member-names">
                  <span class="name">{a.display_name || a.username}</span>
                  <span class="handle">@{a.acct}</span>
                </div>
                <button
                  class="icon-btn"
                  aria-label={memberIds.has(a.id) ? `@${a.acct} is already in this list` : `Add @${a.acct} to ${list.title}`}
                  title={memberIds.has(a.id) ? 'Already in list' : 'Add to list'}
                  disabled={memberIds.has(a.id)}
                  onClick={() => void add(a)}
                >
                  <Icon name={memberIds.has(a.id) ? 'check' : 'plus'} size={18} />
                </button>
              </li>
            ))}
          </ul>
        )}

        <h3 class="lists__section-label">Current members</h3>
        {membersError && <p class="error-note" role="alert">{membersError}</p>}
        {members === null && !membersError && (
          <div class="spinner-block" role="status" aria-busy="true">
            <div class="spinner" aria-hidden="true" />
          </div>
        )}
        {members !== null && members.length === 0 && !membersError && (
          <p class="help-text">No members yet. Search above to add accounts.</p>
        )}
        {members !== null && members.length > 0 && (
          <ul class="lists__members">
            {members.map((a) => (
              <li class="lists__member" key={a.id}>
                <img class="lists__member-avatar" src={a.avatar_static} alt="" width={36} height={36} loading="lazy" />
                <div class="lists__member-names">
                  <span class="name">{a.display_name || a.username}</span>
                  <span class="handle">@{a.acct}</span>
                </div>
                <button
                  class="icon-btn"
                  aria-label={`Remove @${a.acct} from ${list.title}`}
                  title="Remove from list"
                  onClick={() => void remove(a)}
                >
                  <Icon name="user-minus" size={18} />
                </button>
              </li>
            ))}
          </ul>
        )}

        <div class="lists__dialog-actions">
          <button class="btn" onClick={onClose}>Done</button>
        </div>
      </div>
    </dialog>
  )
}
