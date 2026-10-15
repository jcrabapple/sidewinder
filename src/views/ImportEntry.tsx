/**
 * One picked file as a card: parse status, error, blocked or the full ready
 * controls (type select, mode radios, preview, submit). Spec: IMPORT.md
 * section 6 "File entry states".
 */
import { useMemo, useState } from 'preact/hooks'
import {
  IMPORT_TYPES,
  IMPORT_TYPE_INFO,
  KIND_LABELS,
  isMismatch,
  prepareRows,
  type ImportType,
  type ParsedFile,
} from '../util/importCsv'
import type { ImportMode } from '../state/importer'
import { Icon } from '../components/Icon'
import { formatBytes, formatExact } from '../util/time'
import './ImportEntry.css'

export interface ImportEntryBase {
  key: number
  name: string
  size: number
}

export type ImportEntryState =
  | (ImportEntryBase & { phase: 'parsing' })
  | (ImportEntryBase & { phase: 'error'; message: string })
  | (ImportEntryBase & { phase: 'blocked'; label: string; reason: string })
  | (ImportEntryBase & { phase: 'ready'; parsed: ParsedFile })

export interface ImportEntryProps {
  entry: ImportEntryState
  sending: boolean
  sent: boolean
  sendError: string | null
  onRemove: (key: number) => void
  onSubmit: (args: { key: number; type: ImportType; mode: ImportMode; parsed: ParsedFile }) => void
}

function isImportable(kind: ParsedFile['kind']): kind is ImportType {
  return kind === 'following' || kind === 'blocks' || kind === 'mutes'
}

export function ImportEntry(props: ImportEntryProps) {
  const { entry } = props
  const head = (
    <div class="import__head">
      <span class="import__head-icon">
        <Icon name="file" size={18} />
      </span>
      <span class="import__name" title={entry.name}>
        {entry.name}
      </span>
      <span class="import__size num">{formatBytes(entry.size)}</span>
      <button
        class="icon-btn"
        aria-label={`Remove ${entry.name}`}
        title={`Remove ${entry.name}`}
        onClick={() => props.onRemove(entry.key)}
      >
        <Icon name="x" size={18} />
      </button>
    </div>
  )

  if (entry.phase === 'parsing') {
    return (
      <article class="import__entry card" aria-busy="true">
        {head}
        <p class="import__status" role="status">
          <span class="spinner" aria-hidden="true" /> Checking the file…
        </p>
      </article>
    )
  }
  if (entry.phase === 'error') {
    return (
      <article class="import__entry card">
        {head}
        <p class="error-note" role="alert">
          {entry.message}
        </p>
      </article>
    )
  }
  if (entry.phase === 'blocked') {
    return (
      <article class="import__entry card">
        {head}
        <p class="import__blocked-label">{entry.label}</p>
        <p class="import__blocked-reason help-text">{entry.reason}</p>
      </article>
    )
  }
  return <ReadyEntry {...props} entry={entry} />
}

/** The ready card. Hooks live here so the phase checks above stay hook-free. */
function ReadyEntry(props: ImportEntryProps & { entry: ImportEntryBase & { phase: 'ready'; parsed: ParsedFile } }) {
  const { entry, sending, sent, sendError, onSubmit } = props
  const parsed = entry.parsed
  const [type, setType] = useState<ImportType | ''>(isImportable(parsed.kind) ? parsed.kind : '')
  const [mode, setMode] = useState<ImportMode>('merge')

  // Recomputed whenever the chosen type changes.
  const prepared = useMemo(() => (type ? prepareRows(parsed, type) : null), [parsed, type])

  // Detected candidates first, then the rest in canonical order.
  const optionTypes = useMemo(() => {
    const rest = IMPORT_TYPES.filter((t) => !parsed.candidates.includes(t))
    return [...parsed.candidates, ...rest]
  }, [parsed])

  const mismatch = type ? isMismatch(parsed.kind, type) : false
  const rowCount = prepared ? prepared.rows.length : 0
  const canSend = type !== '' && rowCount > 0 && !sending && !sent
  const chosenInfo = type ? IMPORT_TYPE_INFO[type] : null

  let detect: string
  if (parsed.kind === 'unknown') {
    detect = "Can't tell what this file is. Choose the type below."
  } else {
    const how =
      parsed.detectedBy === 'header'
        ? 'the column headers'
        : parsed.detectedBy === 'shape'
          ? 'the contents'
          : 'the file name'
    detect = `Looks like: ${KIND_LABELS[parsed.kind]}. Detected from ${how}.`
  }

  function submit() {
    if (!type || !canSend) return
    onSubmit({ key: entry.key, type, mode, parsed })
  }

  return (
    <article class="import__entry card">
      <div class="import__head">
        <span class="import__head-icon">
          <Icon name="file" size={18} />
        </span>
        <span class="import__name" title={entry.name}>
          {entry.name}
        </span>
        <span class="import__size num">{formatBytes(entry.size)}</span>
        <button
          class="icon-btn"
          aria-label={`Remove ${entry.name}`}
          title={`Remove ${entry.name}`}
          onClick={() => props.onRemove(entry.key)}
        >
          <Icon name="x" size={18} />
        </button>
      </div>

      <p class="import__detect">{detect}</p>

      <div class="import__field">
        <label class="label" for={`import-type-${entry.key}`}>
          Import as
        </label>
        <select
          id={`import-type-${entry.key}`}
          class="select import__type"
          value={type}
          onChange={(e) => setType((e.currentTarget as HTMLSelectElement).value as ImportType | '')}
        >
          <option value="">Choose a type…</option>
          {optionTypes.map((t) => (
            <option key={t} value={t}>
              {IMPORT_TYPE_INFO[t].title}: {IMPORT_TYPE_INFO[t].hint}
            </option>
          ))}
        </select>
      </div>

      <fieldset class="import__modes">
        <legend class="label">If you already have some</legend>
        <label class="import__mode-option">
          <input
            class="import__mode"
            type="radio"
            name={`import-mode-${entry.key}`}
            value="merge"
            checked={mode === 'merge'}
            onChange={() => setMode('merge')}
          />
          <span>Merge: keep what you have and add these</span>
        </label>
        <label class="import__mode-option">
          <input
            class="import__mode"
            type="radio"
            name={`import-mode-${entry.key}`}
            value="overwrite"
            checked={mode === 'overwrite'}
            onChange={() => setMode('overwrite')}
          />
          <span>Overwrite: replace what you have with these</span>
        </label>
      </fieldset>

      {!prepared && <p class="help-text">Choose a type to see what would be imported.</p>}

      {prepared && (
        <div
          class="import__preview"
          data-ready={prepared.rows.length}
          data-skipped={prepared.skipped.length}
          data-duplicates={prepared.duplicates}
        >
          <p class="import__ready">
            {formatExact(prepared.rows.length)} {prepared.rows.length === 1 ? 'account' : 'accounts'} ready to import
          </p>
          {prepared.duplicates > 0 && (
            <p class="import__preview-line">{formatExact(prepared.duplicates)} duplicates removed</p>
          )}
          {prepared.rows.length > 0 && (
            <ul class="import__sample">
              {prepared.rows.slice(0, 5).map((row) => (
                <li key={row.line}>{row.address}</li>
              ))}
              {prepared.rows.length > 5 && <li>and {formatExact(prepared.rows.length - 5)} more</li>}
            </ul>
          )}
          {prepared.skipped.length > 0 && (
            <details class="import__skipped">
              <summary>{formatExact(prepared.skipped.length)} {prepared.skipped.length === 1 ? 'row' : 'rows'} skipped</summary>
              <ul>
                {prepared.skipped.slice(0, 50).map((row) => (
                  <li key={row.line}>Line {row.line}: {row.reason}</li>
                ))}
                {prepared.skipped.length > 50 && <li>and {formatExact(prepared.skipped.length - 50)} more</li>}
              </ul>
            </details>
          )}
        </div>
      )}

      {chosenInfo && prepared && prepared.rows.length === 0 && (
        <p class="error-note" role="alert">
          No valid accounts in this file for {chosenInfo.title}. Nothing will be imported.
        </p>
      )}

      {chosenInfo && mismatch && (
        <p class="error-note" role="alert">
          This file looks like {KIND_LABELS[parsed.kind]}, but you chose {chosenInfo.title}. Check the type before
          importing.
        </p>
      )}

      {sendError && (
        <p class="error-note" role="alert">
          {sendError}
        </p>
      )}

      {sent ? (
        <p class="import__sent">
          Sent to your server. It works through the file in the background and does not report back, so progress is
          under Recent imports.
        </p>
      ) : (
        <div class="import__submit-row">
          <button class="btn btn--primary import__submit" disabled={!canSend} onClick={submit}>
            <Icon name="upload" size={16} />
            {sending
              ? 'Sending…'
              : `Import ${formatExact(rowCount)} ${rowCount === 1 ? 'account' : 'accounts'}`}
          </button>
        </div>
      )}
    </article>
  )
}
