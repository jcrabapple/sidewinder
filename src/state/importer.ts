/**
 * Import state: upload a CSV to GoToSocial's importer, then watch the account's
 * counts (the importer is asynchronous and reports nothing back), persist the
 * watched jobs, and download a backup before an overwrite.
 * Rules: IMPORT.md section 5. Exported names and signatures are frozen contracts.
 */
import { computed, type ReadonlySignal } from '@preact/signals'
import type { ImportRow, ImportType } from '../util/importCsv'

export type ImportMode = 'merge' | 'overwrite'

/** GET /api/v1/exports/stats (GoToSocial only). */
export interface ExportStats {
  media_storage?: string
  followers_count: number
  following_count: number
  statuses_count: number
  lists_count: number
  blocks_count: number
  mutes_count: number
}

export const JOBS_STORE_KEY = 'sidewinder.importJobs'
export const POLL_FAST_MS = 3_000
export const POLL_SLOW_MS = 10_000
export const FAST_WINDOW_MS = 120_000
export const QUIET_AFTER_MS = 30_000
export const WATCH_LIMIT_MS = 15 * 60_000
export const MAX_JOBS_PER_SESSION = 5

/** watching: counts moved recently. quiet: no change for QUIET_AFTER_MS, still polling. ended: polling stopped. */
export type JobPhase = 'watching' | 'quiet' | 'ended'
export type EndedReason = 'stopped' | 'timeout' | 'error' | 'session'

export interface ImportJob {
  id: string
  sessionId: string
  type: ImportType
  mode: ImportMode
  fileName: string
  /** Rows sent to the server. */
  rows: number
  /** Epoch ms. */
  startedAt: number
  /** Server count for `type` just before the upload. */
  baseline: number
  /** Latest polled count for `type`. */
  current: number
  /** Epoch ms of the last poll where `current` changed (startedAt until then). */
  lastChangeAt: number
  phase: JobPhase
  endedReason?: EndedReason
}

export interface StartImportArgs {
  type: ImportType
  mode: ImportMode
  fileName: string
  rows: ImportRow[]
}

export type ImportErrorKind = 'unsupported' | 'scope' | 'rejected' | 'moved' | 'network' | 'unknown'

/** An import failure with copy that is safe to show the user as-is (`message`). */
export class ImportError extends Error {
  kind: ImportErrorKind
  status: number
  constructor(kind: ImportErrorKind, message: string, status = 0) {
    super(message)
    this.name = 'ImportError'
    this.kind = kind
    this.status = status
  }
}

export function countFor(stats: ExportStats, type: ImportType): number {
  if (type === 'following') return stats.following_count
  if (type === 'blocks') return stats.blocks_count
  return stats.mutes_count
}

/** The active session's jobs, newest first. Empty when signed out. */
export const jobs: ReadonlySignal<ImportJob[]> = computed<ImportJob[]>(() => [])

/** Read the active account's counts. Throws ImportError('unsupported') on 404/405. */
export async function fetchExportStats(): Promise<ExportStats> {
  throw new Error('importer.fetchExportStats: not implemented')
}

/** Upload `rows` as the canonical CSV and start watching. Throws ImportError (or NetworkError mapped to one). */
export async function startImport(_args: StartImportArgs): Promise<ImportJob> {
  throw new Error('importer.startImport: not implemented')
}

/** Restart polling for stored, unfinished jobs of the active session. Safe to call repeatedly. */
export function resumeJobs(): void {}

/** Stop polling a job (phase ended / stopped); the record stays visible. */
export function stopJob(_id: string): void {}

/** Stop polling and delete the record. */
export function dismissJob(_id: string): void {}

/** Remove one session's jobs and timers (called from logout). */
export function clearJobsFor(_sessionId: string): void {}

/** Download the account's current list as a CSV file (GoToSocial's own export). */
export async function downloadBackup(_type: ImportType): Promise<void> {
  throw new Error('importer.downloadBackup: not implemented')
}

/** Any thrown value -> a sentence for the user. */
export function importErrorMessage(e: unknown): string {
  return e instanceof Error ? e.message : 'Something went wrong while importing. Try again.'
}
