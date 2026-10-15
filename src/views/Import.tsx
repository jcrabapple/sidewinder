/**
 * Import view (#/import): pick Mastodon / GoToSocial export files, check them
 * locally, hand them to the server's importer. Spec: IMPORT.md section 6.
 * The confirm dialog body (ImportConfirm) and the jobs panel (ImportJobs)
 * belong to other agents; this file only mounts them.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import {
  KIND_LABELS,
  MAX_FILE_BYTES,
  UNSUPPORTED_REASON,
  classifyNonCsv,
  isMismatch,
  parseImportText,
  prepareRows,
  type ImportKind,
  type ImportRow,
  type ImportType,
  type ParsedFile,
} from '../util/importCsv'
import {
  ImportError,
  fetchExportStats,
  importErrorMessage,
  resumeJobs,
  startImport,
  type ExportStats,
  type ImportMode,
} from '../state/importer'
import { toast } from '../state/toast'
import { Icon } from '../components/Icon'
import { formatExact } from '../util/time'
import { useTitle } from '../router'
import { ImportConfirmDialog, type ConfirmRequest } from './ImportConfirm'
import { ImportJobsPanel } from './ImportJobs'
import { ImportEntry, type ImportEntryState } from './ImportEntry'
import { ImportHelp } from './ImportHelp'
import './Import.css'

/** A send the user has decided on, waiting for a confirm dialog or in flight. */
interface PendingSend {
  key: number
  type: ImportType
  mode: ImportMode
  fileName: string
  rows: ImportRow[]
}

/** Same copy parseImportText produces for an oversize file, without reading it. */
const FILE_TOO_LARGE = 'That file is larger than 20 MB.'

const UNSUPPORTED_KINDS = ['followers', 'lists', 'bookmarks', 'domain_blocks'] as const

/** Reason copy for a recognised but non-importable kind, or null. */
function unsupportedReason(kind: ImportKind): string | null {
  if (kind === 'followers') return UNSUPPORTED_REASON.followers
  if (kind === 'lists') return UNSUPPORTED_REASON.lists
  if (kind === 'bookmarks') return UNSUPPORTED_REASON.bookmarks
  if (kind === 'domain_blocks') return UNSUPPORTED_REASON.domain_blocks
  return null
}

export function ImportView() {
  useTitle('Import')
  const [entries, setEntries] = useState<ImportEntryState[]>([])
  const [stats, setStats] = useState<ExportStats | null>(null)
  const [unsupported, setUnsupported] = useState<string | null>(null)
  const [dragOver, setDragOver] = useState(false)
  const [sendingKeys, setSendingKeys] = useState<number[]>([])
  /** Keys with a request in flight: a double click or a second path into doSend must not send twice. */
  const inflight = useRef(new Set<number>())
  const [sentKeys, setSentKeys] = useState<number[]>([])
  const [sendErrors, setSendErrors] = useState<Map<number, string>>(new Map())
  const [confirming, setConfirming] = useState<{ request: ConfirmRequest; send: PendingSend } | null>(null)
  const nextKey = useRef(1)
  /** Keys still in the list; a file removed mid-parse must not apply its result. */
  const liveKeys = useRef(new Set<number>())

  useEffect(() => {
    resumeJobs()
    let cancelled = false
    fetchExportStats()
      .then((s) => {
        if (!cancelled) setStats(s)
      })
      .catch((e) => {
        if (!cancelled && e instanceof ImportError && e.kind === 'unsupported') {
          setUnsupported(importErrorMessage(e))
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  function addFiles(files: File[]) {
    for (const file of files) {
      const key = nextKey.current++
      liveKeys.current.add(key)
      setEntries((cur) => [...cur, { key, name: file.name, size: file.size, phase: 'parsing' }])
      void processFile(key, file)
    }
  }

  function replaceEntry(key: number, next: ImportEntryState) {
    if (!liveKeys.current.has(key)) return
    setEntries((cur) => cur.map((e) => (e.key === key ? next : e)))
  }

  async function processFile(key: number, file: File) {
    // Archives and JSON are recognised by name and never read: they can be gigabytes.
    const non = classifyNonCsv(file.name)
    if (non) {
      replaceEntry(key, { key, name: file.name, size: file.size, phase: 'blocked', label: non.label, reason: non.reason })
      return
    }
    // Oversize files are rejected without reading them either.
    if (file.size > MAX_FILE_BYTES) {
      replaceEntry(key, { key, name: file.name, size: file.size, phase: 'error', message: FILE_TOO_LARGE })
      return
    }
    try {
      const text = await file.text()
      if (!liveKeys.current.has(key)) return
      const parsed = parseImportText(text, file.name, file.size)
      if (!liveKeys.current.has(key)) return
      const reason = unsupportedReason(parsed.kind)
      if (parsed.error) {
        replaceEntry(key, {
          key,
          name: file.name,
          size: file.size,
          phase: 'error',
          message: parsed.error,
        })
      } else if (reason) {
        replaceEntry(key, {
          key,
          name: file.name,
          size: file.size,
          phase: 'blocked',
          label: KIND_LABELS[parsed.kind],
          reason,
        })
      } else {
        replaceEntry(key, { key, name: file.name, size: file.size, phase: 'ready', parsed })
      }
    } catch {
      replaceEntry(key, {
        key,
        name: file.name,
        size: file.size,
        phase: 'error',
        message: 'The file could not be read. Try picking it again.',
      })
    }
  }

  function removeEntry(key: number) {
    liveKeys.current.delete(key)
    setEntries((cur) => cur.filter((e) => e.key !== key))
    setSendErrors((cur) => {
      if (!cur.has(key)) return cur
      const next = new Map(cur)
      next.delete(key)
      return next
    })
  }

  function handleSubmit(args: { key: number; type: ImportType; mode: ImportMode; parsed: ParsedFile }) {
    const prepared = prepareRows(args.parsed, args.type)
    const mismatch = isMismatch(args.parsed.kind, args.type)
    const send: PendingSend = {
      key: args.key,
      type: args.type,
      mode: args.mode,
      fileName: args.parsed.fileName,
      rows: prepared.rows,
    }
    if (args.mode === 'overwrite' || mismatch) {
      setConfirming({
        request: {
          type: args.type,
          mode: args.mode,
          rowCount: prepared.rows.length,
          fileName: args.parsed.fileName,
          mismatchKind: mismatch ? args.parsed.kind : null,
        },
        send,
      })
    } else {
      void doSend(send)
    }
  }

  async function doSend(send: PendingSend) {
    if (inflight.current.has(send.key) || sentKeys.includes(send.key)) return
    inflight.current.add(send.key)
    setSendingKeys((cur) => [...cur, send.key])
    setSendErrors((cur) => {
      if (!cur.has(send.key)) return cur
      const next = new Map(cur)
      next.delete(send.key)
      return next
    })
    try {
      await startImport({ type: send.type, mode: send.mode, fileName: send.fileName, rows: send.rows })
      toast('Import sent. Watching your counts below.')
      setSentKeys((cur) => (cur.includes(send.key) ? cur : [...cur, send.key]))
    } catch (e) {
      setSendErrors((cur) => new Map(cur).set(send.key, importErrorMessage(e)))
    } finally {
      inflight.current.delete(send.key)
      setSendingKeys((cur) => cur.filter((k) => k !== send.key))
    }
  }

  return (
    <div
      class="import"
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => e.preventDefault()}
    >
      <header class="view-header">
        <a class="icon-btn" href="#/settings" aria-label="Back to Settings" title="Back to Settings">
          <Icon name="chevron-left" size={18} />
        </a>
        <h1>Import</h1>
      </header>

      <section class="import__intro card" aria-label="About importing">
        <p>
          Bring follows, blocks and mutes over from a Mastodon or GoToSocial export. Sidewinder
          checks each file here first, then hands it to your server's importer.
        </p>
        {stats && (
          <p class="import__now">
            Your account now: Following <span class="num">{formatExact(stats.following_count)}</span>
            {' · '}Blocks <span class="num">{formatExact(stats.blocks_count)}</span>
            {' · '}Mutes <span class="num">{formatExact(stats.mutes_count)}</span>
          </p>
        )}
        {unsupported && (
          <p class="error-note" role="alert">
            {unsupported}
          </p>
        )}
      </section>

      <ImportHelp />

      <label
        class={`import__drop${dragOver ? ' is-dragover' : ''}`}
        aria-disabled={unsupported ? 'true' : undefined}
        onDragOver={(e) => {
          e.preventDefault()
          if (!unsupported) setDragOver(true)
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => {
          e.preventDefault()
          setDragOver(false)
          if (unsupported) return
          const files = e.dataTransfer?.files
          if (files && files.length > 0) addFiles(Array.from(files))
        }}
      >
        <input
          class="import__file-input visually-hidden"
          type="file"
          multiple
          accept=".csv,.txt,.json,.zip,text/csv"
          disabled={unsupported !== null}
          onChange={(e) => {
            const input = e.currentTarget as HTMLInputElement
            if (input.files) addFiles(Array.from(input.files))
            // Reset so picking the same file again re-runs the check.
            input.value = ''
          }}
        />
        <span class="import__drop-title">
          <Icon name="upload" size={18} /> Choose CSV files
        </span>
        <span class="import__drop-secondary">or drop them here</span>
        <span class="help-text">Up to 20 MB and 20,000 rows per file.</span>
      </label>

      <div class="import__entries">
        {entries.map((entry) => (
          <ImportEntry
            key={entry.key}
            entry={entry}
            sending={sendingKeys.includes(entry.key)}
            sent={sentKeys.includes(entry.key)}
            sendError={sendErrors.get(entry.key) ?? null}
            onRemove={removeEntry}
            onSubmit={handleSubmit}
          />
        ))}
      </div>

      <ImportJobsPanel />

      <section class="import__limits card" aria-labelledby="import-limits-title">
        <h2 id="import-limits-title">What can't be imported</h2>
        <dl>
          {UNSUPPORTED_KINDS.map((kind) => (
            <div class="import__limit" key={kind}>
              <dt>{KIND_LABELS[kind]}</dt>
              <dd>{UNSUPPORTED_REASON[kind]}</dd>
            </div>
          ))}
          {[
            { name: 'custom_filters.json', info: classifyNonCsv('custom_filters.json') },
            { name: 'archive.zip', info: classifyNonCsv('archive.zip') },
          ].map(
            (row) =>
              row.info && (
                <div class="import__limit" key={row.name}>
                  <dt>{row.info.label}</dt>
                  <dd>{row.info.reason}</dd>
                </div>
              ),
          )}
        </dl>
      </section>

      {confirming && (
        <ImportConfirmDialog
          request={confirming.request}
          onConfirm={() => {
            const send = confirming.send
            setConfirming(null)
            void doSend(send)
          }}
          onCancel={() => setConfirming(null)}
        />
      )}
    </div>
  )
}
