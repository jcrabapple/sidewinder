/**
 * Confirm dialog for risky imports: overwrite (replaces what the user has) and
 * type mismatch (the file looks like a different kind than the chosen type).
 * Spec: IMPORT.md section 6 "ConfirmDialog". Stub: the DIALOG agent fills this in.
 */
import type { ImportKind, ImportType } from '../util/importCsv'
import type { ImportMode } from '../state/importer'

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

export function ImportConfirmDialog(_props: ImportConfirmDialogProps) {
  return null
}
