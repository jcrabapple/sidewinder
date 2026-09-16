/**
 * Wrapper that hides a post when it matches a client-side keyword mute,
 * leaving a one-line placeholder with a reveal button. Mutes are private
 * (browser-only) and notifications are exempt — this is for timelines and
 * digests only.
 */
import { useState } from 'preact/hooks'
import type { Status } from '../api/types'
import { muteFor, type KeywordMute } from '../state/mutes'
import { Icon } from './Icon'
import './MutedPost.css'

export function MutedPost({ status, children }: { status: Status; children: preact.ComponentChildren }) {
  const mute = muteFor(status)
  const [revealed, setRevealed] = useState(false)
  if (!mute || revealed) return <>{children}</>
  return <MutedPlaceholder mute={mute} onReveal={() => setRevealed(true)} />
}

function MutedPlaceholder({ mute, onReveal }: { mute: KeywordMute; onReveal: () => void }) {
  return (
    <div class="muted-post">
      <Icon name="eye" size={14} />
      <span class="muted-post__reason">
        Filtered · matches <strong>{mute.phrase}</strong>
      </span>
      <button class="btn" onClick={onReveal}>
        Show anyway
      </button>
    </div>
  )
}
