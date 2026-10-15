/**
 * Import CSV model: classify, parse and normalise the files Mastodon and
 * GoToSocial export, and write the canonical CSV that GoToSocial's importer
 * accepts. Pure (no DOM, Preact or fetch) so scripts/test-import-csv.mjs can
 * run it under plain node. Rules and test vectors: IMPORT.md section 4.
 *
 * Erasable TypeScript only (no enums, namespaces or parameter properties).
 */

/** Values of the `type` field at POST /api/v1/import. */
export const IMPORT_TYPES = ['following', 'blocks', 'mutes'] as const
export type ImportType = (typeof IMPORT_TYPES)[number]

/** Everything an export file can turn out to be. */
export type ImportKind = ImportType | 'followers' | 'lists' | 'bookmarks' | 'domain_blocks' | 'unknown'

/** Kinds the server cannot import. */
export type UnsupportedKind = Exclude<ImportKind, ImportType | 'unknown'>

export const MAX_FILE_BYTES = 20 * 1024 * 1024
export const MAX_ROWS = 20_000

export const KIND_LABELS: Record<ImportKind, string> = {
  following: 'Following list',
  blocks: 'Blocked accounts',
  mutes: 'Muted accounts',
  followers: 'Followers list',
  lists: 'Lists',
  bookmarks: 'Bookmarks',
  domain_blocks: 'Blocked domains',
  unknown: 'Unknown file',
}

/** Copy for the three importable types. `noun` follows "your", `removed` follows "will be". */
export const IMPORT_TYPE_INFO: Record<ImportType, { title: string; hint: string; noun: string; removed: string }> = {
  following: { title: 'Following', hint: 'accounts you follow', noun: 'follows', removed: 'unfollowed' },
  blocks: { title: 'Blocks', hint: 'accounts you block', noun: 'blocks', removed: 'unblocked' },
  mutes: { title: 'Mutes', hint: 'accounts you mute', noun: 'mutes', removed: 'unmuted' },
}

/** Why each recognised-but-unsupported kind cannot be imported. */
export const UNSUPPORTED_REASON: Record<UnsupportedKind, string> = {
  followers:
    "Followers can't be imported: other accounts choose whether to follow you. To bring followers across, set up an alias in GoToSocial's settings (Migration) and use Move on your old account.",
  lists: "GoToSocial can't import lists yet. It can export them, but its importer only accepts follows, blocks and mutes.",
  bookmarks: "GoToSocial can't import bookmarks yet. Its importer only accepts follows, blocks and mutes.",
  domain_blocks:
    "GoToSocial blocks domains for the whole instance, and only admins manage that, so there is nothing to import into your account.",
}

export interface NonCsvInfo {
  label: string
  reason: string
}

const ARCHIVE_INFO: NonCsvInfo = {
  label: 'Account archive',
  reason:
    "The archive is a record of your posts, likes and media. Neither Mastodon nor GoToSocial can import posts, so there is nothing to upload. The CSV files from the export page are the importable part.",
}
const ARCHIVE_PART_INFO: NonCsvInfo = {
  label: 'Account archive file',
  reason: "This file comes from the account archive, which is a record of your posts, likes and media. It can't be imported.",
}
const FILTERS_INFO: NonCsvInfo = {
  label: 'Filters',
  reason: "GoToSocial can't import filters yet. Its importer only accepts follows, blocks and mutes.",
}
const OTHER_JSON_INFO: NonCsvInfo = {
  label: 'JSON file',
  reason: "Only CSV files can be imported. Pick the CSV files from the export page.",
}

/**
 * Files that are never read: archives and JSON are recognised by name only
 * (an archive can be gigabytes). Returns null for names that should be read as CSV.
 */
export function classifyNonCsv(fileName: string): NonCsvInfo | null {
  const name = fileName.toLowerCase()
  if (/\.(zip|tar|tgz|gz)$/.test(name)) return ARCHIVE_INFO
  if (name.endsWith('.json')) {
    if (name.includes('filters')) return FILTERS_INFO
    if (/(^|[\\/])(outbox|likes|bookmarks|actor)\.json$/.test(name)) return ARCHIVE_PART_INFO
    return OTHER_JSON_INFO
  }
  return null
}

/** One CSV record with the physical line (1-based) where it starts. */
export interface CsvRecord {
  line: number
  cells: string[]
}

export type DetectedBy = 'header' | 'shape' | 'filename' | 'none'

export interface ParsedFile {
  fileName: string
  /** Bytes, as given by the caller. */
  size: number
  /** Data records only: header row and blank lines removed. */
  records: CsvRecord[]
  /** Trimmed header cells when the first record was a header, else null. */
  header: string[] | null
  /** Widest record after trimming trailing empty cells. */
  columnCount: number
  /** Best guess at what the file is; 'unknown' when ambiguous. */
  kind: ImportKind
  detectedBy: DetectedBy
  /** For kind 'unknown': the importable types worth offering, best first. Empty otherwise. */
  candidates: ImportType[]
  /** Whole-file problem (empty, binary, too large, malformed quote). When set, nothing is importable. */
  error: string | null
}

export interface ImportRow {
  line: number
  /** `username@domain`, no leading '@', domain lowercased. */
  address: string
  /** following only; default true */
  showBoosts: boolean
  /** following only; default false */
  notify: boolean
  /** mutes only; default true */
  hideNotifications: boolean
}

export interface SkippedRow {
  line: number
  reason: string
  /** The record's cells joined with ',' and truncated to 80 chars. */
  raw: string
}

export interface PreparedRows {
  type: ImportType
  /** Valid, de-duplicated, in file order. */
  rows: ImportRow[]
  skipped: SkippedRow[]
  /** Rows dropped as duplicates (not listed in `skipped`). */
  duplicates: number
}

/** Selecting `selected` for a file detected as `kind` is suspicious. Unknown never mismatches. */
export function isMismatch(kind: ImportKind, selected: ImportType): boolean {
  return kind !== 'unknown' && kind !== selected
}

/** Tokenise, detect and describe a CSV file. Never throws: whole-file problems land in `error`. */
export function parseImportText(_text: string, _fileName: string, _size?: number): ParsedFile {
  throw new Error('importCsv.parseImportText: not implemented')
}

/** Apply the type's shape rules to a parsed file. Pure; cheap enough to run on every type change. */
export function prepareRows(_file: ParsedFile, _type: ImportType): PreparedRows {
  throw new Error('importCsv.prepareRows: not implemented')
}

/** The canonical CSV the server accepts for `type` containing exactly `rows`. */
export function serializeUpload(_type: ImportType, _rows: ImportRow[]): string {
  throw new Error('importCsv.serializeUpload: not implemented')
}
