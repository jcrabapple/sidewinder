/** Interactive poll: vote, live results, expiry. */
import { useState } from 'preact/hooks'
import type { Poll as PollType } from '../api/types'
import { votePoll } from '../state/statusActions'
import { formatAbsolute, formatCount } from '../util/time'
import { replaceEmojis } from './StatusCard'
import './Poll.css'

interface PollProps {
  poll: PollType
  /** Called with the fresh poll once the vote lands (or fails silently). */
  onPollUpdated: (poll: PollType) => void
}

export function Poll({ poll, onPollUpdated }: PollProps) {
  const [selected, setSelected] = useState<number[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const canVote = !poll.expired && !poll.voted && selected.length > 0

  function toggle(idx: number) {
    if (poll.voted || poll.expired) return
    setSelected(
      poll.multiple
        ? selected.includes(idx)
          ? selected.filter((i) => i !== idx)
          : [...selected, idx]
        : [idx],
    )
  }

  async function submit() {
    setBusy(true)
    setError(null)
    try {
      onPollUpdated(await votePoll(poll.id, selected))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Vote failed')
    } finally {
      setBusy(false)
    }
  }

  const total = poll.voters_count ?? poll.votes_count
  const leading = poll.options.reduce(
    (best, o, i) => ((o.votes_count ?? 0) > (poll.options[best]?.votes_count ?? -1) ? i : best),
    0,
  )

  return (
    <div class="poll">
      <ul class="poll__options">
        {poll.options.map((opt, i) => {
          const votes = opt.votes_count ?? 0
          const pct = total > 0 ? Math.round((votes / total) * 100) : 0
          const isOwnChoice = poll.own_votes?.includes(i)
          return (
            <li key={i} class="poll__option">
              {poll.voted || poll.expired ? (
                <div class={i === leading ? 'poll__row poll__row--lead' : 'poll__row'}>
                  <div class="poll__track" style={`--fill:${pct / 100}`}>
                    <span class="poll__label">
                      {isOwnChoice && <span class="visually-hidden">Your choice: </span>}
                      {replaceEmojis(opt.title, poll.emojis)}
                    </span>
                    <span class="poll__pct num">{pct}%</span>
                  </div>
                </div>
              ) : (
                <label class="poll__choice">
                  <input
                    type={poll.multiple ? 'checkbox' : 'radio'}
                    name={`poll-${poll.id}`}
                    checked={selected.includes(i)}
                    onChange={() => toggle(i)}
                  />
                  <span>{replaceEmojis(opt.title, poll.emojis)}</span>
                </label>
              )}
            </li>
          )
        })}
      </ul>
      {!poll.voted && !poll.expired && (
        <button class="btn poll__vote" disabled={!canVote || busy} onClick={submit}>
          {busy ? 'Voting…' : 'Vote'}
        </button>
      )}
      {error && <p class="field-error" role="alert">{error}</p>}
      <p class="poll__meta">
        {formatCount(total)} {poll.voters_count != null ? 'people voted' : 'votes'} ·{' '}
        {poll.expired ? 'Closed' : poll.expires_at ? `Closes ${formatAbsolute(poll.expires_at)}` : 'No end date'}
      </p>
    </div>
  )
}
