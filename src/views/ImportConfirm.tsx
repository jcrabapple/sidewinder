/**
 * Confirm dialog for risky imports: overwrite (replaces what the user has) and
 * type mismatch (the file looks like a different kind than the chosen type).
 * Spec: IMPORT.md section 6 "ConfirmDialog".
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import { IMPORT_TYPE_INFO, KIND_LABELS, type ImportKind, type ImportType } from '../util/importCsv'
import { downloadBackup, fetchExportStats, countFor, importErrorMessage, type ImportMode } from '../state/importer'
import { Icon } from '../components/Icon'
import { formatExact } from '../util/time'
import './ImportConfirm.css'

export interface ConfirmRequest {
  type: ImportType
  mode: ImportMode
  rowCount: number
  fileName: string
  /** Detected kind when it disagrees with `type`; null when the choice matches or nothing was detected. */
  mismatchKind: ImportKind | null
}

export interface ImportConfirmDialogProps {
  request: ConfirmRequest
  onConfirm: () => void
  onCancel: () => void
}

export function ImportConfirmDialog({ request, onConfirm, onCancel }: ImportConfirmDialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const info = IMPORT_TYPE_INFO[request.type]
  const overwrite = request.mode === 'overwrite'
  const mismatch = request.mismatchKind !== null

  // Overwrite: current server count, fetched once on open.
  const [current, setCurrent] = useState<number | null>(null)
  const [statsFailed, setStatsFailed] = useState(false)
  // Backup button state machine.
  const [backupBusy, setBackupBusy] = useState(false)
  const [backupDone, setBackupDone] = useState(false)
  const [backupError, setBackupError] = useState<string | null>(null)
  const [acked, setAcked] = useState(false)
  // One activation only: a double click or a held Enter must not fire onConfirm twice.
  const [confirmed, setConfirmed] = useState(false)

  useEffect(() => {
    if (!overwrite) return
    let live = true
    fetchExportStats()
      .then((stats) => {
        if (live) setCurrent(countFor(stats, request.type))
      })
      .catch(() => {
        if (live) setStatsFailed(true)
      })
    return () => {
      live = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const dlg = ref.current
    if (!dlg) return
    const prevFocus = document.activeElement as HTMLElement | null
    dlg.showModal()
    const cancelBtn = dlg.querySelector<HTMLButtonElement>('.import-confirm__cancel')
    cancelBtn?.focus()
    const onCancelEvent = () => onCancel()
    const onClick = (e: MouseEvent) => {
      if (e.target === dlg) onCancel()
    }
    dlg.addEventListener('cancel', onCancelEvent)
    dlg.addEventListener('click', onClick)
    return () => {
      dlg.removeEventListener('cancel', onCancelEvent)
      dlg.removeEventListener('click', onClick)
      // Content outside an open modal is inert, so close it before handing focus back.
      if (dlg.open) dlg.close()
      prevFocus?.focus()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function doBackup() {
    if (backupBusy) return
    setBackupBusy(true)
    setBackupError(null)
    try {
      await downloadBackup(request.type)
      setBackupDone(true)
    } catch (e) {
      setBackupError(importErrorMessage(e))
    } finally {
      setBackupBusy(false)
    }
  }

  const titleId = 'import-confirm-title'
  const goDanger = overwrite
  // The page never opens this dialog with zero rows; refuse here too, because the action is destructive.
  const goDisabled = confirmed || request.rowCount === 0 || (mismatch && !acked)

  return (
    <dialog class="sw-dialog import-confirm" ref={ref} aria-labelledby={titleId}>
      <div class="sw-dialog__head">
        <h2 id={titleId}>
          {overwrite ? `Replace your ${info.noun} with this file?` : 'Check the import type'}
        </h2>
      </div>
      <div class="sw-dialog__body" aria-busy={overwrite && current === null && !statsFailed}>
        <p class="import-confirm__file">
          <Icon name="file" size={16} /> <span class="import-confirm__file-name">{request.fileName}</span>
        </p>

        {overwrite && (
          <section class="import-confirm__section">
            {current === null && !statsFailed && (
              <p class="import-confirm__loading" role="status">
                <span class="spinner" aria-hidden="true" /> Loading your current count…
              </p>
            )}
            {current !== null && (
              <p>
                You currently have <span class="num">{formatExact(current)}</span> {info.noun}. This
                file has <span class="num">{formatExact(request.rowCount)}</span>. Everything not in
                the file will be {info.removed}.
              </p>
            )}
            {statsFailed && (
              <p>Could not load your current count, but the import will still replace everything not in the file.</p>
            )}
            <p>
              The server does this in the background and Sidewinder cannot undo it. Download a backup first if
              you might want the old list back.
            </p>
            <button
              type="button"
              class="btn import-confirm__backup"
              onClick={doBackup}
              disabled={backupBusy || backupDone}
            >
              {backupDone ? (
                <>
                  <Icon name="check" size={18} /> Backup downloaded
                </>
              ) : (
                <>
                  <Icon name="download" size={18} /> {backupBusy ? 'Downloading…' : 'Download a backup'}
                </>
              )}
            </button>
            {backupError && (
              <p class="field-error" role="alert">
                {backupError}
              </p>
            )}
            {request.type === 'blocks' && (
              <p class="help-text">Blocking an account also removes any follow between you and them.</p>
            )}
          </section>
        )}

        {mismatch && request.mismatchKind !== null && (
          <section class="import-confirm__section">
            <p>
              This file looks like {KIND_LABELS[request.mismatchKind]}. You chose {info.title}. Importing it
              this way is usually a mistake.
            </p>
            <label class="import-confirm__ack-label">
              <input
                type="checkbox"
                class="import-confirm__ack"
                checked={acked}
                onChange={(e) => setAcked((e.target as HTMLInputElement).checked)}
              />
              Import it as {info.title} anyway
            </label>
          </section>
        )}

        <div class="import-confirm__actions">
          <button type="button" class="btn import-confirm__cancel" onClick={onCancel}>
            Cancel
          </button>
          <button
            type="button"
            class={`btn import-confirm__go ${goDanger ? 'btn--danger' : 'btn--primary'}`}
            disabled={goDisabled}
            onClick={() => {
              if (confirmed) return
              setConfirmed(true)
              onConfirm()
            }}
          >
            {goDanger ? `Replace my ${info.noun}` : 'Import anyway'}
          </button>
        </div>
      </div>
    </dialog>
  )
}
