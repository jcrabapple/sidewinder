/**
 * Import view (#/import): pick Mastodon / GoToSocial export files, check them
 * locally, hand them to the server's importer. Spec: IMPORT.md section 6.
 * Stub: the VIEW agent fills this in.
 */
import { useTitle } from '../router'

export function ImportView() {
  useTitle('Import')
  return (
    <div class="import">
      <header class="view-header">
        <h1>Import</h1>
      </header>
      <div class="state-block">
        <p>The importer is being built.</p>
      </div>
    </div>
  )
}
