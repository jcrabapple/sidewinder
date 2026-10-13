/**
 * MediaGrid: responsive grid for 1–6 attachments, sensitive blur, alt badge,
 * focal-point cropping (meta.focus), video/audio playback, and a multi-item
 * lightbox carousel for full-size viewing.
 */
import { useState, useRef, useEffect } from 'preact/hooks'
import type { MediaAttachment } from '../api/types'
import { settings } from '../state/settings'
import { Icon } from './Icon'
import './MediaGrid.css'

interface MediaGridProps {
  media: MediaAttachment[]
  sensitive: boolean
  /** CW already revealed? When false and sensitive, keep blurred. */
  revealed: boolean
}

function focalPosition(media: MediaAttachment): string | undefined {
  const focus = media.meta?.focus as { x?: number; y?: number } | undefined
  if (!focus || typeof focus.x !== 'number' || typeof focus.y !== 'number') return undefined
  const x = Math.round(((focus.x + 1) / 2) * 100)
  const y = Math.round(((1 - focus.y) / 2) * 100)
  return `${x}% ${y}%`
}

export function MediaGrid({ media, sensitive, revealed }: MediaGridProps) {
  const [lightboxIdx, setLightboxIdx] = useState<number | null>(null)
  const [dismissed, setDismissed] = useState(false)
  const lastTrigger = useRef<HTMLElement | null>(null)
  // Derived, not stored: when the CW is revealed the blur clears too.
  const blurred = sensitive && !revealed && !dismissed
  const items = media.slice(0, 6)
  const n = items.length
  const cls = `media-grid media-grid--${n}`

  const viewable = items.filter((m) => m.type === 'image' || m.type === 'unknown')

  function openLightbox(m: MediaAttachment, btn: HTMLElement) {
    const idx = viewable.findIndex((v) => v.id === m.id)
    if (idx === -1) return
    lastTrigger.current = btn
    setLightboxIdx(idx)
  }

  function closeLightbox() {
    setLightboxIdx(null)
    lastTrigger.current?.focus()
  }

  return (
    <div class={cls}>
      {blurred && (
        <button class="media-grid__sensitive" onClick={() => setDismissed(true)}>
          <Icon name="eye-off" size={20} />
          <span>Sensitive media — click to view</span>
        </button>
      )}
      <div class="media-grid__tiles" aria-hidden={blurred || undefined} data-hidden={blurred || undefined}>
        {items.map((m) => (
          <MediaTile
            key={m.id}
            media={m}
            single={n === 1}
            onOpen={(btn) => openLightbox(m, btn)}
          />
        ))}
      </div>
      {lightboxIdx !== null && viewable[lightboxIdx] && (
        <Lightbox
          items={viewable}
          index={lightboxIdx}
          onIndexChange={setLightboxIdx}
          onClose={closeLightbox}
        />
      )}
    </div>
  )
}

function MediaTile({
  media,
  single,
  onOpen,
}: {
  media: MediaAttachment
  single: boolean
  onOpen: (btn: HTMLElement) => void
}) {
  const autoplay = settings.value.autoplayGifs
  const hasAlt = Boolean(media.description)
  const pos = !single ? focalPosition(media) : undefined
  const imgStyle = pos ? { objectPosition: pos } : undefined

  return (
    <div class={`media-tile media-tile--${media.type}`}>
      {media.type === 'image' || media.type === 'unknown' ? (
        <button
          class="media-tile__btn"
          onClick={(e) => onOpen(e.currentTarget)}
          aria-label={media.description ?? 'Open image in viewer'}
        >
          <img
            src={media.preview_url ?? media.url ?? ''}
            alt={media.description ?? ''}
            style={imgStyle}
            loading="lazy"
          />
        </button>
      ) : media.type === 'gifv' || media.type === 'video' ? (
        <video
          src={media.url ?? undefined}
          poster={media.preview_url ?? undefined}
          controls
          muted={media.type === 'gifv' ? true : undefined}
          loop={media.type === 'gifv' ? true : undefined}
          autoplay={media.type === 'gifv' && autoplay ? true : undefined}
          playsinline
          preload="metadata"
          aria-label={media.description ?? undefined}
        />
      ) : media.type === 'audio' ? (
        <audio src={media.url ?? undefined} controls preload="metadata" aria-label={media.description ?? undefined} />
      ) : null}
      {settings.value.altBadge && hasAlt && (
        <span class="media-tile__alt-badge" title={media.description ?? ''}>alt</span>
      )}
      {!hasAlt && (
        <span class="media-tile__alt-badge media-tile__alt-badge--missing" title="No image description provided">
          <Icon name="alert" size={12} />
          <span class="visually-hidden">Missing image description</span>
        </span>
      )}
    </div>
  )
}

function Lightbox({
  items,
  index,
  onIndexChange,
  onClose,
}: {
  items: MediaAttachment[]
  index: number
  onIndexChange: (idx: number) => void
  onClose: () => void
}) {
  const dlg = useRef<HTMLDialogElement>(null)
  const media = items[index]
  const total = items.length
  const hasMultiple = total > 1

  useEffect(() => {
    dlg.current?.showModal()
  }, [])

  function prev() {
    onIndexChange((index - 1 + total) % total)
  }

  function next() {
    onIndexChange((index + 1) % total)
  }

  useEffect(() => {
    if (!hasMultiple) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft') {
        e.preventDefault()
        prev()
      } else if (e.key === 'ArrowRight') {
        e.preventDefault()
        next()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [index, total, hasMultiple])

  return (
    <dialog
      ref={dlg}
      class="lightbox"
      aria-label="Media viewer"
      onClick={(e) => { if (e.target === dlg.current) onClose() }}
      onClose={onClose}
    >
      <div class="lightbox__inner">
        <div class="lightbox__bar">
          {media.description ? (
            <p class="lightbox__alt">{media.description}</p>
          ) : (
            <p class="lightbox__alt lightbox__alt--none">No description provided</p>
          )}
          <div class="lightbox__controls">
            {hasMultiple && (
              <>
                <span class="lightbox__counter num" aria-live="polite">
                  {index + 1} / {total}
                </span>
                <button
                  class="icon-btn lightbox__nav"
                  onClick={prev}
                  aria-label="Previous image"
                  title="Previous image"
                >
                  <Icon name="chevron-left" size={20} />
                </button>
                <button
                  class="icon-btn lightbox__nav"
                  onClick={next}
                  aria-label="Next image"
                  title="Next image"
                >
                  <Icon name="chevron-right" size={20} />
                </button>
              </>
            )}
            <button class="icon-btn lightbox__close" onClick={onClose} aria-label="Close viewer" title="Close viewer">
              <Icon name="x" size={20} />
            </button>
          </div>
        </div>
        <img src={media.url ?? media.preview_url ?? ''} alt={media.description ?? ''} />
      </div>
    </dialog>
  )
}
