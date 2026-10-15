/**
 * Import state: upload a CSV to GoToSocial's importer, then watch the account's
 * counts (the importer is asynchronous and reports nothing back), persist the
 * watched jobs, and download a backup before an overwrite.
 * Rules: IMPORT.md section 5. Exported names and signatures are frozen contracts.
 */
import { computed, signal, type ReadonlySignal } from '@preact/signals'
import { Api, ApiError, NetworkError } from '../api/client'
import { activeSession, sessions } from './auth'
import type { ImportRow, ImportType } from '../util/importCsv'
import { serializeUpload } from '../util/importCsv'

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
export type EndedReason = 'stopped' | 'timeout' | 'error' | 'session' | 'superseded'

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

// ---------------------------------------------------------------------------
// Error mapping
// ---------------------------------------------------------------------------

const UNSUPPORTED_MESSAGE =
  "This server does not have GoToSocial's importer, so Sidewinder cannot import files into it."
const SCOPE_MESSAGE =
  'Your session cannot make changes. Sign out and back in to grant write access.'
const MOVED_MESSAGE = 'Accounts that have moved cannot import data.'
const NETWORK_MESSAGE = 'Could not reach the server. Check your connection and try again.'
const UNKNOWN_MESSAGE = 'Something went wrong while importing. Try again.'

/** ApiError/NetworkError -> an ImportError carrying user-safe copy. */
function toImportError(e: unknown): ImportError {
  if (e instanceof ImportError) return e
  if (e instanceof NetworkError) return new ImportError('network', NETWORK_MESSAGE)
  if (e instanceof ApiError) {
    const msg = e.message ?? ''
    if (e.status === 400 || e.status === 422) {
      return new ImportError('rejected', `The server rejected the file: ${msg}`, e.status)
    }
    if (e.status === 401 || e.status === 403) {
      return msg.toLowerCase().includes('move')
        ? new ImportError('moved', MOVED_MESSAGE, e.status)
        : new ImportError('scope', SCOPE_MESSAGE, e.status)
    }
    if (e.status === 404 || e.status === 405) {
      return new ImportError('unsupported', UNSUPPORTED_MESSAGE, e.status)
    }
    return new ImportError('unknown', UNKNOWN_MESSAGE, e.status)
  }
  return new ImportError('unknown', UNKNOWN_MESSAGE)
}

/** Any thrown value -> a sentence for the user. */
export function importErrorMessage(e: unknown): string {
  if (e instanceof ImportError) return e.message
  if (e instanceof NetworkError) return NETWORK_MESSAGE
  return UNKNOWN_MESSAGE
}

// ---------------------------------------------------------------------------
// Api for a captured session
// ---------------------------------------------------------------------------

/** An Api bound to the session with this id, or null when it is signed out. */
function apiForSession(sessionId: string): Api | null {
  const s = sessions.value.find((x) => x.id === sessionId)
  if (!s) return null
  const client = new Api(s.base)
  client.token = s.token
  return client
}

/** Read stats through an explicit Api (not the live active one). */
async function statsVia(client: Api): Promise<ExportStats> {
  try {
    return await client.get<ExportStats>('/api/v1/exports/stats')
  } catch (e) {
    throw toImportError(e)
  }
}

/** Read the active account's counts. Throws ImportError('unsupported') on 404/405. */
export async function fetchExportStats(): Promise<ExportStats> {
  const client = apiForSession(activeSession.value?.id ?? '')
  if (!client) throw new ImportError('unknown', UNKNOWN_MESSAGE)
  return statsVia(client)
}

// ---------------------------------------------------------------------------
// Job store: module-level signal Record<sessionId, ImportJob[]> + persistence
// ---------------------------------------------------------------------------

interface StoredJob {
  id?: unknown
  sessionId?: unknown
  type?: unknown
  mode?: unknown
  fileName?: unknown
  rows?: unknown
  startedAt?: unknown
  baseline?: unknown
  current?: unknown
  lastChangeAt?: unknown
  phase?: unknown
  endedReason?: unknown
}

function isImportType(v: unknown): v is ImportType {
  return v === 'following' || v === 'blocks' || v === 'mutes'
}

function isImportMode(v: unknown): v is ImportMode {
  return v === 'merge' || v === 'overwrite'
}

/** Defensive parse: drop malformed entries individually, never throw. */
function parseStored(raw: string | null): Record<string, ImportJob[]> {
  if (!raw) return {}
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return {}
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return {}
  const out: Record<string, ImportJob[]> = {}
  for (const [sessionId, list] of Object.entries(parsed as Record<string, unknown>)) {
    if (!Array.isArray(list)) continue
    const kept: ImportJob[] = []
    for (const entry of list as unknown[]) {
      if (typeof entry !== 'object' || entry === null) continue
      const j = entry as StoredJob
      if (
        typeof j.id !== 'string' ||
        typeof j.sessionId !== 'string' ||
        !isImportType(j.type) ||
        !isImportMode(j.mode) ||
        typeof j.fileName !== 'string' ||
        typeof j.rows !== 'number' ||
        typeof j.startedAt !== 'number' ||
        typeof j.baseline !== 'number' ||
        typeof j.current !== 'number' ||
        typeof j.lastChangeAt !== 'number' ||
        (j.phase !== 'watching' && j.phase !== 'quiet' && j.phase !== 'ended')
      ) {
        continue
      }
      const endedReason =
        j.endedReason === 'stopped' ||
        j.endedReason === 'timeout' ||
        j.endedReason === 'error' ||
        j.endedReason === 'session' ||
        j.endedReason === 'superseded'
          ? j.endedReason
          : undefined
      if (j.phase === 'ended' && !endedReason) continue
      kept.push({
        id: j.id,
        sessionId: j.sessionId,
        type: j.type,
        mode: j.mode,
        fileName: j.fileName,
        rows: j.rows,
        startedAt: j.startedAt,
        baseline: j.baseline,
        current: j.current,
        lastChangeAt: j.lastChangeAt,
        phase: j.phase,
        endedReason,
      })
    }
    if (kept.length > 0) out[sessionId] = kept
  }
  return out
}

/** Trim one session's list: drop ended jobs older than 24 h, cap at MAX_JOBS_PER_SESSION, newest first. */
function trimList(list: ImportJob[]): ImportJob[] {
  const now = Date.now()
  return list
    .filter((j) => j.phase !== 'ended' || now - j.startedAt < 24 * 60 * 60_000)
    .sort((a, b) => b.startedAt - a.startedAt)
    .slice(0, MAX_JOBS_PER_SESSION)
}

function persistJobs(store: Record<string, ImportJob[]>): void {
  try {
    localStorage.setItem(JOBS_STORE_KEY, JSON.stringify(store))
  } catch {
    // Blocked storage: jobs stay session-local.
  }
}

const jobStore = signal<Record<string, ImportJob[]>>(
  (() => {
    const store = parseStored(loadRawSafe())
    for (const key of Object.keys(store)) store[key] = trimList(store[key])
    persistJobs(store)
    return store
  })(),
)

function loadRawSafe(): string | null {
  try {
    return localStorage.getItem(JOBS_STORE_KEY)
  } catch {
    return null
  }
}

/** The active session's jobs, newest first. Empty when signed out. */
export const jobs: ReadonlySignal<ImportJob[]> = computed<ImportJob[]>(() => {
  const id = activeSession.value?.id
  if (!id) return []
  return jobStore.value[id] ?? []
})

/** Replace one job immutably (and only if it still exists). */
function updateJob(id: string, patch: (j: ImportJob) => ImportJob): void {
  const sessionId = currentSessionOf(id)
  if (!sessionId) return
  const list = jobStore.value[sessionId]
  if (!list) return
  const idx = list.findIndex((j) => j.id === id)
  if (idx === -1) return
  const nextList = list.map((j, i) => (i === idx ? patch(j) : j))
  jobStore.value = { ...jobStore.value, [sessionId]: trimList(nextList) }
  persistJobs(jobStore.value)
}

/** Which session currently holds this job id (it should never move between sessions). */
function currentSessionOf(id: string): string | null {
  for (const [sessionId, list] of Object.entries(jobStore.value)) {
    if (list.some((j) => j.id === id)) return sessionId
  }
  return null
}

function prependJob(job: ImportJob): void {
  const list = jobStore.value[job.sessionId] ?? []
  const merged = trimList([job, ...list])
  jobStore.value = { ...jobStore.value, [job.sessionId]: merged }
  persistJobs(jobStore.value)
}

// ---------------------------------------------------------------------------
// Polling: one setTimeout chain per job, never overlapping
// ---------------------------------------------------------------------------

/** One timer handle per job id (the invariant is "at most one timer per job"). */
const timers = new Map<string, ReturnType<typeof setTimeout>>()

/**
 * Poll generation per job. Every (re)schedule and every stop bumps it, so a tick
 * that was already awaiting the network when it was superseded sees a stale
 * generation on return and ends its chain instead of re-arming a second timer.
 */
const generations = new Map<string, number>()

function clearTimer(id: string): void {
  generations.set(id, (generations.get(id) ?? 0) + 1)
  const t = timers.get(id)
  if (t !== undefined) {
    clearTimeout(t)
    timers.delete(id)
  }
}

function jobId(): string {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID()
    }
  } catch {
    // Fall through to the manual fallback.
  }
  return `job-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function nextDelay(startedAt: number, now: number): number {
  return now - startedAt < FAST_WINDOW_MS ? POLL_FAST_MS : POLL_SLOW_MS
}

/**
 * Schedule the next poll. The closure holds only the immutable job identity;
 * counts and phase are read from the store each tick, so no stale values.
 */
function schedulePoll(job: ImportJob, state: { failures: number }): void {
  clearTimer(job.id)
  const gen = generations.get(job.id) ?? 0
  const isCurrent = (): boolean => generations.get(job.id) === gen
  const tick = async (): Promise<void> => {
    if (!isCurrent()) return
    timers.delete(job.id)
    const client = apiForSession(job.sessionId)
    if (!client) {
      // The session was signed out or removed: stop, never retry.
      updateJob(job.id, (j) => ({ ...j, phase: 'ended', endedReason: 'session' }))
      return
    }
    try {
      const stats = await statsVia(client)
      if (!isCurrent()) return
      state.failures = 0
      const now = Date.now()
      if (now - job.startedAt >= WATCH_LIMIT_MS) {
        updateJob(job.id, (j) => ({ ...j, current: countFor(stats, j.type), phase: 'ended', endedReason: 'timeout' }))
        return
      }
      const count = countFor(stats, job.type)
      updateJob(job.id, (j) => {
        if (j.phase === 'ended') return j // stopped or ended between ticks
        const changed = count !== j.current
        const lastChangeAt = changed ? now : j.lastChangeAt
        const phase: JobPhase =
          changed || now - lastChangeAt < QUIET_AFTER_MS ? 'watching' : 'quiet'
        return { ...j, current: count, lastChangeAt, phase }
      })
    } catch {
      if (!isCurrent()) return
      state.failures += 1
      if (state.failures >= 5) {
        updateJob(job.id, (j) =>
          j.phase === 'ended' ? j : { ...j, phase: 'ended', endedReason: 'error' },
        )
        return
      }
    }
    // Schedule the next tick only after this one fully settled (no overlap).
    const live = findJob(job.id)
    if (!live || live.phase === 'ended' || !isCurrent()) return
    const t = setTimeout(() => {
      void tick()
    }, nextDelay(job.startedAt, Date.now()))
    timers.set(job.id, t)
  }
  const t = setTimeout(() => {
    void tick()
  }, nextDelay(job.startedAt, Date.now()))
  timers.set(job.id, t)
}

function findJob(id: string): ImportJob | null {
  const sessionId = currentSessionOf(id)
  if (!sessionId) return null
  return jobStore.value[sessionId]?.find((j) => j.id === id) ?? null
}

// ---------------------------------------------------------------------------
// startImport
// ---------------------------------------------------------------------------

const IMPORT_TIMEOUT_MS = 60_000

/** Upload `rows` as the canonical CSV and start watching. Throws ImportError (or NetworkError mapped to one). */
export async function startImport(args: StartImportArgs): Promise<ImportJob> {
  // Capture the ACTIVE session ONCE; every request for this job uses it.
  const session = activeSession.value
  if (!session) throw new ImportError('unknown', UNKNOWN_MESSAGE)
  if (args.rows.length === 0) {
    throw new ImportError('rejected', 'There is nothing valid to import.')
  }
  const client = apiForSession(session.id)
  if (!client) throw new ImportError('unknown', UNKNOWN_MESSAGE)

  // Baseline BEFORE the upload.
  const stats = await statsVia(client)
  const baseline = countFor(stats, args.type)

  const csv = serializeUpload(args.type, args.rows)
  const form = new FormData()
  form.append('data', new Blob([csv], { type: 'text/csv' }), `${args.type}.csv`)
  form.append('type', args.type)
  form.append('mode', args.mode)

  try {
    await client.request<void>('/api/v1/import', {
      method: 'POST',
      body: form,
      timeoutMs: IMPORT_TIMEOUT_MS,
    })
  } catch (e) {
    throw toImportError(e)
  }

  const now = Date.now()
  const job: ImportJob = {
    id: jobId(),
    sessionId: session.id,
    type: args.type,
    mode: args.mode,
    fileName: args.fileName,
    rows: args.rows.length,
    startedAt: now,
    baseline,
    current: baseline,
    lastChangeAt: now,
    phase: 'watching',
  }
  // Counts are account-wide, so two live jobs of one type would both claim the same change.
  // The newest import owns the counter; older live ones are frozen at their last reading.
  for (const other of jobStore.value[session.id] ?? []) {
    if (other.type === args.type && other.phase !== 'ended') {
      clearTimer(other.id)
      updateJob(other.id, (j) => ({ ...j, phase: 'ended', endedReason: 'superseded' }))
    }
  }
  prependJob(job)
  schedulePoll(job, { failures: 0 })
  return job
}

// ---------------------------------------------------------------------------
// Lifecycle: resume, stop, dismiss, clear
// ---------------------------------------------------------------------------

/** Restart polling for stored, unfinished jobs of the active session. Safe to call repeatedly. */
export function resumeJobs(): void {
  const sessionId = activeSession.value?.id
  if (!sessionId) return
  const list = jobStore.value[sessionId]
  if (!list || list.length === 0) return
  const now = Date.now()
  let changed = false
  const next: ImportJob[] = []
  for (const job of list) {
    if (job.phase === 'ended') {
      next.push(job)
      continue
    }
    if (now - job.startedAt >= WATCH_LIMIT_MS) {
      changed = true
      next.push({ ...job, phase: 'ended', endedReason: 'timeout' })
      continue
    }
    // Not ended and young: ensure it is polling (exactly once: clearTimer first).
    changed = true
    next.push(job)
    schedulePoll(job, { failures: 0 })
  }
  if (changed) {
    jobStore.value = { ...jobStore.value, [sessionId]: trimList(next) }
    persistJobs(jobStore.value)
  }
}

/** Stop polling a job (phase ended / stopped); the record stays visible. */
export function stopJob(id: string): void {
  clearTimer(id)
  updateJob(id, (j) =>
    j.phase === 'ended' ? j : { ...j, phase: 'ended', endedReason: 'stopped' },
  )
}

/** Stop polling and delete the record. */
export function dismissJob(id: string): void {
  clearTimer(id)
  const sessionId = currentSessionOf(id)
  if (!sessionId) return
  const list = jobStore.value[sessionId]
  if (!list) return
  const next = list.filter((j) => j.id !== id)
  jobStore.value = { ...jobStore.value, [sessionId]: trimList(next) }
  persistJobs(jobStore.value)
}

/** Remove one session's jobs and timers (called from logout). */
export function clearJobsFor(sessionId: string): void {
  const list = jobStore.value[sessionId]
  if (list) {
    for (const job of list) clearTimer(job.id)
  }
  if (!(sessionId in jobStore.value)) return
  const next = { ...jobStore.value }
  delete next[sessionId]
  jobStore.value = next
  try {
    localStorage.setItem(JOBS_STORE_KEY, JSON.stringify(next))
  } catch {
    // Blocked storage.
  }
}

// ---------------------------------------------------------------------------
// Backup download
// ---------------------------------------------------------------------------

/** Download the account's current list as a CSV file (GoToSocial's own export). */
export async function downloadBackup(type: ImportType): Promise<void> {
  const session = activeSession.value
  if (!session) throw new ImportError('unknown', UNKNOWN_MESSAGE)
  const res = await Api.fetchWithTimeout(
    `${session.base.replace(/\/+$/, '')}/api/v1/exports/${type}.csv`,
    { headers: { Authorization: `Bearer ${session.token}` } },
  )
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const p = (await res.json()) as { error?: string }
      if (typeof p?.error === 'string') message = p.error
    } catch {
      // keep default message
    }
    throw toImportError(new ApiError(res.status, message))
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `sidewinder-backup-${type}-${new Date().toISOString().slice(0, 10)}.csv`
  document.body.appendChild(a)
  a.click()
  a.remove()
  // Revoking synchronously can cancel the download in some browsers.
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}
