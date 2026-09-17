/** Compose dialog state: opened by the FAB, nav button, or reply/boost actions. */
import { signal } from '@preact/signals'
import type { Status } from '../api/types'

export interface ComposeContext {
  /** Reply target, if any. */
  replyTo?: Status
  /** Existing status being edited (draft loaded from .text). */
  editing?: Status
  /** Pre-fill text (e.g. share a link). */
  presetText?: string
}

export const composeOpen = signal(false)
export const composeContext = signal<ComposeContext | null>(null)
/** The most recently created status — views (e.g. Thread) react to this to
 *  show new replies immediately without a refetch. */
export const lastPosted = signal<Status | null>(null)

export function openCompose(ctx: ComposeContext | null = null) {
  composeContext.value = ctx
  composeOpen.value = true
}

export function closeCompose() {
  composeOpen.value = false
  composeContext.value = null
}
