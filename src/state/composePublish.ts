import type { DraftMedia } from './draft'

export interface PublicationProgress {
  publishedCount: number
  publishedParentId?: string
}

/** Fill only missing keys so a retry always reuses the original segment key. */
export function ensureIdempotencyKeys(
  current: readonly string[] | undefined,
  segmentCount: number,
  createKey: () => string = () => crypto.randomUUID(),
): string[] {
  return Array.from({ length: segmentCount }, (_, index) => current?.[index] || createKey())
}

/** Return the first unpublished segment, never an earlier published one. */
export function nextPublicationIndex(progress: PublicationProgress, segmentCount: number): number | null {
  return progress.publishedCount < segmentCount ? progress.publishedCount : null
}

/** Advance only after the server confirms a segment. */
export function advancePublication(progress: PublicationProgress, publishedId: string): PublicationProgress {
  return { publishedCount: progress.publishedCount + 1, publishedParentId: publishedId }
}

/** Edits must explicitly clear server media when the last attachment is removed. */
export function withMediaIds(
  body: Record<string, unknown>,
  media: readonly DraftMedia[],
  editing: boolean,
): Record<string, unknown> {
  const mediaIds = media
    .filter((item) => !item.uploading && !item.attachment.id.startsWith('pending-'))
    .map((item) => item.attachment.id)
  return editing || mediaIds.length > 0 ? { ...body, media_ids: mediaIds } : body
}

/** A new share-target payload is additional content, not a draft replacement. */
export function mergePresetText(draftText: string, presetText: string | undefined): string {
  if (!presetText?.trim() || draftText === presetText || draftText.startsWith(`${presetText}\n`)) {
    return draftText || presetText || ''
  }
  if (!draftText.trim()) return presetText
  return `${presetText}\n\n${draftText}`
}
