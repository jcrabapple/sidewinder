/**
 * MediaGrid: responsive grid for 1–6 attachments, sensitive blur, alt badge,
 * video/audio playback, and a lightbox for full-size viewing.
 */
import { useState } from 'preact/hooks'
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

export function MediaGrid({ media, sensitive, revealed }: MediaGridProps) {
  const [lightbox, setLightbox] = useState<MediaAttachment | null>(null)
  const [blurred, setBlurred] = useState(sensitive && !revealed)
  const n = Math.min(media.length, 6)
  const cls = `media-grid media-grid--${n}`

  return (
    <div class={cls}>
      {blurred && (
        <button class="media-grid__sensitive" onClick={() => setBlurred(false)}>
          <Icon name="eye-off" size={20} />
          <span>Sensitive media — click to view</span>
        </button>
      )}
      <div class="media-grid__tiles" aria-hidden={blurred || undefined} data-hidden={blurred || undefined}>
        {media.slice(0, 6).map((m) => (
          <MediaTile key={m.id} media={m} onOpen={() => setLightbox(m)} />
        ))}
      </div>
      {lightbox && <Lightbox media={lightbox} onClose={() => setLightbox(null)} />}
    </div>
  )
}

function MediaTile({ media, onOpen }: { media: MediaAttachment; onOpen: () => void }) {
  const autoplay = settings.value.autoplayGifs
  const hasAlt = Boolean(media.description)

  return (
    <div class={`media-tile media-tile--${media.type}`}>
      {media.type === 'image' || media.type === 'unknown' ? (
        <button class="media-tile__btn" onClick={onOpen} aria-label={media.description ?? 'Open image in viewer'}>
          <img src={media.preview_url ?? media.url ?? ''} alt={media.description ?? ''} loading="lazy" />
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

function Lightbox({ media, onClose }: { media: MediaAttachment; onClose: () => void }) {
  return (
    <div class="lightbox" role="dialog" aria-modal="true" aria-label="Media viewer" onClick={onClose}>
      <div class="lightbox__inner" onClick={(e) => e.stopPropagation()}>
        <div class="lightbox__bar">
          {media.description ? (
            <p class="lightbox__alt">{media.description}</p>
          ) : (
            <p class="lightbox__alt lightbox__alt--none">No description provided</p>
          )}
          <button class="icon-btn lightbox__close" onClick={onClose} aria-label="Close viewer">
            <Icon name="x" size={20} />
          </button>
        </div>
        <img src={media.url ?? media.preview_url ?? ''} alt={media.description ?? ''} />
      </div>
    </div>
  )
}
