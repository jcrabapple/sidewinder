/**
 * "Recent imports" panel: progress of watched import jobs (before -> now).
 * Spec: IMPORT.md section 6 "JobsPanel".
 */
import { useEffect, useState } from 'preact/hooks'
import { IMPORT_TYPE_INFO } from '../util/importCsv'
import { dismissJob, jobs, stopJob, type ImportJob } from '../state/importer'
import { formatExact, formatRelative } from '../util/time'
import './ImportJobs.css'

const TICK_MS = 5_000

/** Stable per phase: this text sits in a live region, so it must not change on every tick. */
function phaseSentence(job: ImportJob): string {
  if (job.phase === 'watching') return 'Watching your counts. They update every few seconds.'
  if (job.phase === 'quiet') return 'No change for a while. The server may be done, or still working through slow lookups.'
  switch (job.endedReason) {
    case 'stopped':
      return 'Stopped watching.'
    case 'timeout':
      return 'Stopped watching after 15 minutes.'
    case 'error':
      return 'Stopped watching because the server could not be reached.'
    case 'session':
      return 'Stopped watching because this account was signed out.'
    case 'superseded':
      return 'Stopped watching because a newer import of the same kind started. This is the last count it saw.'
    default:
      return 'Stopped watching.'
  }
}

/** How long the count has been still. Not in a live region: it changes every tick. */
function quietFor(job: ImportJob, now: number): string {
  const seconds = Math.max(0, Math.floor((now - job.lastChangeAt) / 1000))
  if (seconds < 60) return `${Math.max(10, Math.round(seconds / 10) * 10)} seconds`
  const minutes = Math.floor(seconds / 60)
  return `${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`
}

/** Signed delta: +N for gains, a real minus for losses, (no change) at zero. */
function deltaLabel(delta: number): string {
  if (delta === 0) return '(no change)'
  const n = formatExact(Math.abs(delta))
  return delta > 0 ? `(+${n})` : `(\u2212${n})`
}

function JobCard({ job, now }: { job: ImportJob; now: number }) {
  const info = IMPORT_TYPE_INFO[job.type]
  const ended = job.phase === 'ended'
  return (
    <article
      class="import-job"
      data-job-id={job.id}
      data-phase={job.phase}
      data-baseline={job.baseline}
      data-current={job.current}
    >
      <h3 class="import-job__title">
        {info.title} · {job.mode === 'merge' ? 'Merge' : 'Overwrite'}
      </h3>
      <p class="import-job__meta">
        {job.fileName} · {formatExact(job.rows)} {job.rows === 1 ? 'account' : 'accounts'} sent · started{' '}
        {formatRelative(new Date(job.startedAt).toISOString())}
      </p>
      <p class="import-job__counts" aria-live="off">
        <span class="import-job__count">{info.title}:</span>{' '}
        <span class="num">{formatExact(job.baseline)}</span> → <span class="num">{formatExact(job.current)}</span>{' '}
        <span class="import-job__delta num">{deltaLabel(job.current - job.baseline)}</span>
      </p>
      <p class="import-job__phase" role="status">
        {phaseSentence(job)}
      </p>
      {job.phase === 'quiet' && <p class="help-text import-job__since">Count unchanged for {quietFor(job, now)}.</p>}
      <p class="help-text import-job__caveat">
        The server skips accounts it cannot reach, accounts on blocked domains, and locked accounts that have
        not approved you yet, so the final count can be lower than the file.
        {job.type === 'blocks' && ' Blocking an account also removes any follow between you and them.'}
      </p>
      <div class="import-job__actions">
        {!ended && (
          <button type="button" class="btn import-job__stop" onClick={() => stopJob(job.id)}>
            Stop watching
          </button>
        )}
        <button type="button" class="btn btn--ghost import-job__dismiss" onClick={() => dismissJob(job.id)}>
          Dismiss
        </button>
      </div>
    </article>
  )
}

export function ImportJobsPanel() {
  const list = jobs
  const [now, setNow] = useState(() => Date.now())
  const anyRunning = list.value.some((j) => j.phase !== 'ended')

  useEffect(() => {
    if (!anyRunning) return
    const t = setInterval(() => setNow(Date.now()), TICK_MS)
    return () => clearInterval(t)
  }, [anyRunning])

  if (list.value.length === 0) return null

  return (
    <section class="import-jobs card" aria-labelledby="import-jobs-title">
      <h2 id="import-jobs-title">Recent imports</h2>
      <div class="import-jobs__list">
        {list.value.map((job) => (
          <JobCard key={job.id} job={job} now={now} />
        ))}
      </div>
    </section>
  )
}
