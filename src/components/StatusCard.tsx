/**
 * StatusCard: the atom of every timeline, thread, profile, and notification.
 * Handles boost wrappers, spoiler content warnings, sensitive media, emoji
 * shortcodes, in-app link routing, and the full action bar.
 */
import { useState } from 'preact/hooks'
import type { Status, Emoji, MediaAttachment } from '../api/types'
import { sanitize } from '../util/sanitize'
import { formatCompact, formatAbsolute } from '../util/time'
import { formatCount } from '../util/time'
import { api, activeSession } from '../state/auth'
import { settings } from '../state/settings'
import { openCompose } from '../state/compose'
import { navigate } from '../router'
import { toast } from '../state/toast'
import { toggleFav, toggleBoost, toggleBookmark, deleteStatus } from '../state/statusActions'
import { Icon } from '../components/Icon'
import { MediaGrid } from './MediaGrid'
import { Poll } from './Poll'
import './Status.css'

export type StatusVariant = 'timeline' | 'thread' | 'detailed' | 'compact'

interface StatusCardProps {
  status: Status
  variant?: StatusVariant
  /** Called when a mutation returns a fresh version of this status. */
  onStatusUpdated?: (s: Status) => void
  /** Called after this status was deleted server-side. */
  onStatusDeleted?: (id: string) => void
  /** Hide the action bar (e.g. media-only contexts). */
  showActions?: boolean
}

const VISIBILITY_LABEL: Record<string, { icon: string; label: string }> = {
  public: { icon: 'globe', label: 'Public' },
  unlisted: { icon: 'eye-off', label: 'Unlisted' },
  private: { icon: 'lock', label: 'Followers only' },
  direct: { icon: 'mail', label: 'Direct' },
  local: { icon: 'lock', label: 'Local only' },
}

export function StatusCard({ status: outer, variant = 'timeline', onStatusUpdated, onStatusDeleted, showActions = true }: StatusCardProps) {
  const boosted = Boolean(outer.reblog)
  const status = outer.reblog ?? outer
  const me = activeSession.value?.account.id

  const updated = (s: Status) => {
    const merged = boosted ? { ...outer, reblog: s } : s
    onStatusUpdated?.(merged)
  }

  return (
    <article
      class={`status status--${variant}`}
      aria-label={ariaLabel(status, outer.account, boosted)}
    >
      {boosted && (
        <div class="status__boost-line">
          <Icon name="repeat" size={14} />
          <button class="status__boost-by" onClick={() => navigate(`/accounts/${outer.account.id}`)}>
            {renderName(outer.account.display_name || outer.account.username, outer.account.emojis)}
          </button>
          <span>boosted</span>
        </div>
      )}
      <div class="status__row">
        <a
          class="status__avatar-link"
          href={`#/accounts/${status.account.id}`}
          aria-label={`${status.account.display_name || status.account.username} profile`}
        >
          <img class="status__avatar" src={variant === 'compact' ? status.account.avatar_static : status.account.avatar} alt="" width={variant === 'compact' ? 32 : 46} height={variant === 'compact' ? 32 : 46} loading="lazy" />
        </a>
        <div class="status__names">
          <a class="status__name" href={`#/accounts/${status.account.id}`}>
            {renderName(status.account.display_name || status.account.username, status.account.emojis)}
          </a>
          <span class="status__handle">@{status.account.acct}</span>
        </div>
        <div class="status__meta">
          <span class="status__visibility" title={VISIBILITY_LABEL[status.visibility]?.label ?? status.visibility}>
            <Icon name={VISIBILITY_LABEL[status.visibility]?.icon ?? 'globe'} size={14} />
            <span class="visually-hidden">{VISIBILITY_LABEL[status.visibility]?.label ?? status.visibility}</span>
          </span>
          <a class="status__time" href={`#/statuses/${status.id}`} title={formatAbsolute(status.created_at)}>
            <time dateTime={status.created_at}>{formatCompact(status.created_at)}</time>
          </a>
          {variant !== 'thread' && (
            <MoreMenu status={status} own={status.account.id === me} onUpdated={updated} onDeleted={onStatusDeleted} />
          )}
        </div>
      </div>

      {status.in_reply_to_id && variant !== 'thread' && (
        <div class="status__reply-line">
          <Icon name="reply" size={13} />
          <span>in thread</span>
        </div>
      )}

      <StatusBody status={status} variant={variant} onUpdated={updated} />

      {showActions && (
        <ActionBar status={status} own={status.account.id === me} onUpdated={updated} />
      )}
    </article>
  )
}

function ariaLabel(status: Status, booster: Status['account'], boosted: boolean): string {
  const who = status.account.display_name || status.account.username
  const text = status.spoiler_text || stripHtml(status.content).slice(0, 120)
  return boosted ? `${booster.display_name || booster.username} boosted: ${text}` : text
}

function stripHtml(html: string): string {
  const el = document.createElement('template')
  el.innerHTML = html
  return el.content.textContent ?? ''
}

function renderName(name: string, emojis: Emoji[]): preact.ComponentChildren[] {
  return replaceEmojis(name, emojis)
}

/** Replace :shortcode: with local custom-emoji images in a plain string. */
export function replaceEmojis(text: string, emojis: Emoji[]): preact.ComponentChildren[] {
  if (!emojis.length || !text.includes(':')) return [text]
  const map = new Map(emojis.map((e) => [`:${e.shortcode}:`, e.url]))
  const out: preact.ComponentChildren[] = []
  let buf = ''
  let i = 0
  while (i < text.length) {
    const colon = text.indexOf(':', i)
    if (colon === -1) { buf += text.slice(i); break }
    const next = text.indexOf(':', colon + 1)
    const token = next === -1 ? null : text.slice(colon, next + 1)
    if (token && map.has(token)) {
      if (buf) { out.push(buf); buf = '' }
      out.push(
        <img class="emoji" src={map.get(token)} alt={token} title={token} loading="lazy" />,
      )
      i = next + 1
    } else {
      buf += text.slice(i, next === -1 ? text.length : next + 1)
      i = next === -1 ? text.length : next + 1
    }
  }
  if (buf) out.push(buf)
  return out
}

/**
 * Body: spoiler/CW handling, sanitized content, media, poll.
 */
function StatusBody({ status, variant, onUpdated }: { status: Status; variant: StatusVariant; onUpdated: (s: Status) => void }) {
  const hasCw = Boolean(status.spoiler_text)
  const [revealed, setRevealed] = useState(false)
  const contentHtml = sanitize(status.content, api.value.base)
  const hasMedia = status.media_attachments.length > 0
  const hasPoll = Boolean(status.poll)

  return (
    <div class="status__body">
      {hasCw && (
        <div class="status__cw">
          <p class="status__cw-text">{replaceEmojis(status.spoiler_text, status.emojis)}</p>
          <button
            class="btn status__cw-btn"
            aria-expanded={revealed}
            onClick={() => setRevealed(!revealed)}
          >
            {revealed ? 'Show less' : 'Show more'}
          </button>
        </div>
      )}
      {(!hasCw || revealed) && (
        <>
          {contentHtml.trim() !== '' && (
            <div
              class="status-content"
              // Sanitized above with DOMPurify: no scripts, styles, or event handlers survive.
              dangerouslySetInnerHTML={{ __html: contentHtml }}
              onClick={handleContentClick}
            />
          )}
          {hasMedia && (
            <MediaGrid
              media={status.media_attachments}
              sensitive={status.sensitive}
              revealed={!hasCw || revealed}
            />
          )}
          {hasPoll && status.poll && (
            <Poll poll={status.poll} onPollUpdated={(p) => onUpdated({ ...status, poll: p })} />
          )}
        </>
      )}
    </div>
  )
}

/** Route in-app: mentions → profile, hashtags → tag timeline. */
function handleContentClick(e: MouseEvent) {
  const a = (e.target as HTMLElement).closest('a')
  if (!a) return
  const href = a.getAttribute('href') ?? ''
  if (a.classList.contains('mention')) {
    e.preventDefault()
    resolveMention(href)
  } else if (a.classList.contains('hashtag')) {
    e.preventDefault()
    const tag = decodeURIComponent(href.replace(/.*\/tags?\//, '')).replace(/[/?].*$/, '')
    if (tag) navigate(`/tags/${encodeURIComponent(tag)}`)
  }
}

/** Mentions link to remote profile URLs; resolve to a local account id via search. */
async function resolveMention(url: string) {
  try {
    const res = await api.value.get<{ accounts: { id: string }[] }>('/api/v1/search', {
      q: url,
      type: 'accounts',
      resolve: true,
      limit: 1,
    })
    if (res.accounts.length > 0) {
      navigate(`/accounts/${res.accounts[0].id}`)
      return
    }
  } catch {
    // fall through to opening the original URL
  }
  window.open(url, '_blank', 'noopener')
}

function ActionBar({ status, own, onUpdated }: { status: Status; own: boolean; onUpdated: (s: Status) => void }) {
  const canBoost = status.visibility === 'public' || status.visibility === 'unlisted' || status.visibility === 'local'

  return (
    <div class="status__actions">
      <button
        class="icon-btn action"
        aria-label={`Reply, ${formatCount(status.replies_count)} replies`}
        title="Reply"
        onClick={() => openCompose({ replyTo: status })}
      >
        <Icon name="reply" size={18} />
        {status.replies_count > 0 && <span class="action__count num">{formatCount(status.replies_count)}</span>}
      </button>
      <button
        class="icon-btn action action--boost"
        aria-pressed={status.reblogged}
        aria-label={status.reblogged ? 'Undo boost' : 'Boost'}
        title={status.reblogged ? 'Undo boost' : 'Boost'}
        disabled={!canBoost}
        onClick={() => toggleBoost(status, onUpdated).catch(() => {})}
      >
        <Icon name="repeat" size={18} />
        {status.reblogs_count > 0 && <span class="action__count num">{formatCount(status.reblogs_count)}</span>}
      </button>
      <button
        class="icon-btn action action--fav"
        aria-pressed={status.favourited}
        aria-label={status.favourited ? 'Unlike' : 'Favourite'}
        title={status.favourited ? 'Unlike' : 'Favourite'}
        onClick={() => toggleFav(status, onUpdated).catch(() => {})}
      >
        <Icon name="heart" size={18} />
        {status.favourites_count > 0 && <span class="action__count num">{formatCount(status.favourites_count)}</span>}
      </button>
      <button
        class="icon-btn action action--bookmark"
        aria-pressed={status.bookmarked}
        aria-label={status.bookmarked ? 'Remove bookmark' : 'Bookmark'}
        title={status.bookmarked ? 'Remove bookmark' : 'Bookmark'}
        onClick={() => toggleBookmark(status, onUpdated).catch(() => {})}
      >
        <Icon name="bookmark" size={18} />
      </button>
    </div>
  )
}

function MoreMenu({ status, own, onUpdated, onDeleted }: { status: Status; own: boolean; onUpdated: (s: Status) => void; onDeleted?: (id: string) => void }) {
  const [open, setOpen] = useState(false)
  const close = () => setOpen(false)

  function onMenuClick(e: Event) {
    const btn = (e.target as HTMLElement).closest('button')
    if (!btn) return
    const act = btn.dataset.act
    if (act === 'copy') {
      navigator.clipboard.writeText(status.url ?? status.uri).then(() => close()).catch(() => {
        close()
        toast('Could not copy the link to the clipboard.', 'error')
      })
    } else if (act === 'original') {
      window.open(status.url ?? status.uri, '_blank', 'noopener')
      close()
    } else if (act === 'edit') {
      close()
      openCompose({ editing: status })
    } else if (act === 'delete') {
      close()
      deleteStatus(status, () => onDeleted?.(status.id)).catch(() => {})
    }
  }

  return (
    <div class="status__more">
      <button class="icon-btn" aria-haspopup="menu" aria-expanded={open} aria-label="More options" onClick={() => setOpen(!open)}>
        <Icon name="more" size={18} />
      </button>
      {open && (
        <div class="menu" role="menu" onClick={onMenuClick} onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node)) close() }}>
          <button role="menuitem" data-act="copy">
            <Icon name="link" size={16} /> Copy link
          </button>
          <button role="menuitem" data-act="original">
            <Icon name="external-link" size={16} /> Open on server
          </button>
          {own && <div role="separator" />}
          {own && (
            <button role="menuitem" data-act="edit">
              <Icon name="pencil" size={16} /> Edit
            </button>
          )}
          {own && (
            <button role="menuitem" data-act="delete" class="danger">
              <Icon name="trash" size={16} /> Delete
            </button>
          )}
        </div>
      )}
    </div>
  )
}
