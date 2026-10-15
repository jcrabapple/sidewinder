/**
 * Help for the import page: where the export files come from.
 * Spec: IMPORT.md section 6 item 2.
 */
import './ImportHelp.css'

export function ImportHelp() {
  return (
    <details class="import__help">
      <summary>Where do I get these files?</summary>
      <ul class="import__help-list">
        <li>
          On Mastodon: Settings, Import and export, Export. Download the CSV for follows, blocks and
          mutes (following_accounts.csv, blocked_accounts.csv, muted_accounts.csv).
        </li>
        <li>On GoToSocial: Settings, Export &amp; Import, then use the export buttons.</li>
        <li>You can pick several files at once. Each one is checked on its own.</li>
      </ul>
    </details>
  )
}
