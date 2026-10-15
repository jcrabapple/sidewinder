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

// --- Internal helpers -------------------------------------------------------

/** First cells that mark a header row (Mastodon's importer accepts all four). */
const HEADER_FIRST_CELLS = new Set(['account address', '#domain', '#uri', 'list name'])

/** Section 4 address regex: client mirror of the server's, domain required. */
const ADDRESS_RE =
  /^([\p{L}\p{M}\p{N}._\-\p{Extended_Pictographic}]+)@([\p{L}\p{M}\p{N}.\-:\p{Extended_Pictographic}]+)$/u

const BOOLEAN_TRUE = new Set(['true', 't', '1'])
const BOOLEAN_FALSE = new Set(['false', 'f', '0'])

type BooleanCell = { kind: 'true' } | { kind: 'false' } | { kind: 'blank' } | { kind: 'bad' }

function parseBooleanCell(cell: string): BooleanCell {
  const value = cell.trim().toLowerCase()
  if (value === '') return { kind: 'blank' }
  if (BOOLEAN_TRUE.has(value)) return { kind: 'true' }
  if (BOOLEAN_FALSE.has(value)) return { kind: 'false' }
  return { kind: 'bad' }
}

/** Trim then drop one leading '@'. Returns the value the address regex is tested against. */
function stripAt(raw: string): string {
  const value = raw.trim()
  return value.startsWith('@') ? value.slice(1) : value
}

/**
 * Validate an account address the way prepareRows must: domain required.
 * Returns the value with one leading '@' removed, or a rejection reason.
 */
function checkAddress(cell: string): { ok: true; value: string } | { ok: false; value: string; missing: boolean } {
  const value = stripAt(cell)
  const at = value.indexOf('@')
  if (at === -1 || at === 0 || at === value.length - 1) {
    return { ok: false, value, missing: true }
  }
  return ADDRESS_RE.test(value) ? { ok: true, value } : { ok: false, value, missing: false }
}

function truncate(value: string, max: number): string {
  return value.length > max ? value.slice(0, max) : value
}

/** Drop trailing cells that are empty or whitespace-only. */
function trimTrailing(cells: string[]): string[] {
  let end = cells.length
  while (end > 0 && cells[end - 1].trim() === '') end -= 1
  return cells.slice(0, end)
}

/**
 * RFC 4180 tokenizer: quoted fields with `""` escapes, newlines inside quotes,
 * `\r\n` / `\n` / `\r` line breaks, literal quotes mid-field. Each record
 * carries the 1-based physical line where it starts. Throws only on an
 * unterminated quote (whole-file problem, caught by parseImportText).
 */
function tokenize(text: string): CsvRecord[] {
  const records: CsvRecord[] = []
  let row: string[] = []
  let field = ''
  let inQuotes = false
  let quoteLine = 0
  let line = 1
  let rowLine = 1
  let fieldStarted = false

  const endField = (): void => {
    row.push(field)
    field = ''
    fieldStarted = false
  }
  const endRecord = (): void => {
    endField()
    records.push({ line: rowLine, cells: row })
    row = []
  }

  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i]
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') {
          field += '"'
          i += 1
        } else {
          inQuotes = false
        }
      } else {
        field += ch
        if (ch === '\n' || ch === '\r') line += ch === '\r' && text[i + 1] === '\n' ? 0 : 1
      }
      continue
    }
    if (ch === '"') {
      // A quote only opens a quoted field at the start of the field; later in
      // an unquoted field it is literal.
      if (field === '' && !fieldStarted) {
        inQuotes = true
        quoteLine = line
        fieldStarted = true
      } else {
        field += '"'
        fieldStarted = true
      }
      continue
    }
    if (ch === ',') {
      endField()
      continue
    }
    if (ch === '\n' || ch === '\r') {
      endRecord()
      line += 1
      if (ch === '\r' && text[i + 1] === '\n') i += 1
      rowLine = line
      continue
    }
    field += ch
    fieldStarted = true
  }
  if (inQuotes) {
    throw new UnterminatedQuoteError(quoteLine)
  }
  if (row.length > 0 || field !== '' || fieldStarted) {
    endRecord()
  }
  return records
}

class UnterminatedQuoteError extends Error {
  line: number
  constructor(line: number) {
    super(`Line ${line}: a quoted value is never closed.`)
    this.line = line
  }
}

// --- parseImportText --------------------------------------------------------

function detectFromHeader(header: string[]): { kind: ImportKind } | null {
  const cells = header.map((cell) => cell.trim().toLowerCase())
  if (cells.some((cell) => cell.includes('hide notifications'))) return { kind: 'mutes' }
  if (
    cells.some((cell) => cell.includes('show boosts') || cell.includes('notify on new posts') || cell.includes('languages'))
  ) {
    return { kind: 'following' }
  }
  const first = cells[0]
  if (first === '#domain') return { kind: 'domain_blocks' }
  if (first === '#uri') return { kind: 'bookmarks' }
  if (first === 'list name') return { kind: 'lists' }
  return null
}

type Shape = 'urls' | 'domains' | 'title+address' | 'address' | 'address+bool' | 'address+flags' | 'other'

const DOMAIN_RE = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/

function shapeOf(records: CsvRecord[]): Shape {
  let shape: Shape | null = null
  for (const record of records.slice(0, 50)) {
    const recordShape = shapeOfRecord(record)
    if (recordShape === 'other') return 'other'
    if (shape === null) {
      shape = recordShape
    } else if (shape !== recordShape) {
      return 'other'
    }
  }
  return shape ?? 'other'
}

function shapeOfRecord(record: CsvRecord): Shape {
  const cells = trimTrailing(record.cells)
  const nonEmpty = cells.filter((cell) => cell.trim() !== '')
  if (nonEmpty.length === 1) {
    const cell = nonEmpty[0].trim()
    if (/^https?:\/\//.test(cell)) return 'urls'
    if (!cell.includes('@') && DOMAIN_RE.test(cell.toLowerCase())) return 'domains'
    if (checkAddress(cell).ok) return 'address'
    return 'other'
  }
  if (nonEmpty.length >= 2) {
    const first = nonEmpty[0].trim()
    const second = nonEmpty[1].trim()
    if (nonEmpty.length === cells.length && cells.length >= 2 && checkAddress(second).ok && !checkAddress(first).ok) {
      return 'title+address'
    }
    if (cells.length === 2 && checkAddress(cells[0]).ok) {
      return parseBooleanCell(cells[1]).kind === 'bad' ? 'other' : 'address+bool'
    }
    if (cells.length >= 3 && cells.length <= 4 && checkAddress(cells[0]).ok) {
      const bad =
        parseBooleanCell(cells[1]).kind === 'bad' || (cells.length >= 3 && parseBooleanCell(cells[2]).kind === 'bad')
      return bad ? 'other' : 'address+flags'
    }
  }
  return 'other'
}

function detectFromFileName(
  fileName: string,
  shape: 'address' | 'address+bool',
): { kind: ImportKind } | null {
  const base = fileName.toLowerCase()
  const slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'))
  const name = slash === -1 ? base : base.slice(slash + 1)
  const rules: Array<[RegExp, ImportKind]> = [
    [/follower/, 'followers'],
    [/mute/, 'mutes'],
    [/block/, 'blocks'],
    [/follow/, 'following'],
  ]
  for (const [re, kind] of rules) {
    if (re.test(name)) {
      if (shape === 'address+bool' && kind !== 'mutes' && kind !== 'following') return null
      return { kind }
    }
  }
  return null
}

const CANDIDATES: Record<'address' | 'address+bool' | 'other', ImportType[]> = {
  address: ['blocks', 'following', 'mutes'],
  'address+bool': ['mutes', 'following'],
  other: ['following', 'blocks', 'mutes'],
}

/** Tokenise, detect and describe a CSV file. Never throws: whole-file problems land in `error`. */
export function parseImportText(text: string, fileName: string, sizeBytes?: number): ParsedFile {
  const size = sizeBytes ?? text.length
  if (size > MAX_FILE_BYTES) {
    return emptyFile(fileName, size, 'That file is larger than 20 MB.')
  }
  const clean = text.replace(/^\uFEFF/, '')
  if (clean.startsWith('PK\u0003\u0004') || clean.includes('\u0000')) {
    return emptyFile(fileName, size, 'This does not look like a text file. Pick the CSV files from the export.')
  }

  let records: CsvRecord[]
  try {
    records = tokenize(clean)
  } catch (e) {
    if (e instanceof UnterminatedQuoteError) {
      return emptyFile(fileName, size, e.message)
    }
    throw e
  }
  records = records.filter((record) => !record.cells.every((cell) => cell.trim() === ''))

  let header: string[] | null = null
  if (records.length > 0 && HEADER_FIRST_CELLS.has(records[0].cells[0].trim().toLowerCase())) {
    header = records[0].cells.map((cell) => cell.trim())
    records = records.slice(1)
  }

  if (records.length === 0) {
    return emptyFile(fileName, size, header ? 'The file has a header row but no rows.' : 'The file is empty.', header)
  }
  if (records.length > MAX_ROWS) {
    return emptyFile(
      fileName,
      size,
      `More than 20,000 rows (found ${records.length}). Split the file and import the parts.`,
      header,
    )
  }

  const columnCount = records.reduce((max, record) => Math.max(max, trimTrailing(record.cells).length), 0)

  let kind: ImportKind = 'unknown'
  let detectedBy: DetectedBy = 'none'
  let candidates: ImportType[] = []
  const headerGuess = header ? detectFromHeader(header) : null
  if (headerGuess) {
    kind = headerGuess.kind
    detectedBy = 'header'
  } else {
    const shape = shapeOf(records)
    if (shape === 'urls') {
      kind = 'bookmarks'
      detectedBy = 'shape'
    } else if (shape === 'domains') {
      kind = 'domain_blocks'
      detectedBy = 'shape'
    } else if (shape === 'title+address') {
      kind = 'lists'
      detectedBy = 'shape'
    } else if (shape === 'address+flags') {
      kind = 'following'
      detectedBy = 'shape'
    } else if (shape === 'address' || shape === 'address+bool') {
      const nameGuess = detectFromFileName(fileName, shape)
      if (nameGuess) {
        kind = nameGuess.kind
        detectedBy = 'filename'
      } else {
        candidates = CANDIDATES[shape]
      }
    } else {
      candidates = CANDIDATES.other
    }
  }

  return {
    fileName: fileName,
    size,
    records,
    header,
    columnCount,
    kind,
    detectedBy,
    candidates,
    error: null,
  }
}

function emptyFile(fileName: string, size: number, error: string, header: string[] | null = null): ParsedFile {
  return {
    fileName,
    size,
    records: [],
    header,
    columnCount: 0,
    kind: 'unknown',
    detectedBy: 'none',
    candidates: [],
    error,
  }
}

/** Apply the type's shape rules to a parsed file. Pure; cheap enough to run on every type change. */
export function prepareRows(file: ParsedFile, type: ImportType): PreparedRows {
  const rows: ImportRow[] = []
  const skipped: SkippedRow[] = []
  let duplicates = 0
  if (file.error) {
    return { type: type, rows, skipped, duplicates }
  }
  const seen = new Set<string>()
  const noun = IMPORT_TYPE_INFO[type].noun

  for (const record of file.records) {
    const cells = trimTrailing(record.cells)
    const limit = type === 'blocks' ? 1 : type === 'mutes' ? 2 : 4
    if (cells.length > limit) {
      skipped.push({
        line: record.line,
        reason: `Too many columns for ${noun} (found ${cells.length}). This looks like a different kind of file.`,
        raw: rawOf(record),
      })
      continue
    }

    const address = checkAddress(cells[0])
    if (!address.ok) {
      skipped.push({
        line: record.line,
        reason: address.missing
          ? `Missing the server part ("${truncate(address.value, 60)}")`
          : `Not a valid account address ("${truncate(address.value, 60)}")`,
        raw: rawOf(record),
      })
      continue
    }
    const at = address.value.indexOf('@')
    const username = address.value.slice(0, at)
    const domain = address.value.slice(at + 1).toLowerCase()
    const normalised = `${username}@${domain}`
    const key = normalised.toLowerCase()
    if (seen.has(key)) {
      duplicates += 1
      continue
    }

    const flags = parseFlagCells(type, cells)
    if (!flags.ok) {
      skipped.push({
        line: record.line,
        reason: `"${flags.column}" must be true or false (found "${truncate(flags.value.trim(), 60)}").`,
        raw: rawOf(record),
      })
      continue
    }

    seen.add(key)
    rows.push({
      line: record.line,
      address: normalised,
      showBoosts: flags.showBoosts,
      notify: flags.notify,
      hideNotifications: flags.hideNotifications,
    })
  }

  return { type: type, rows, skipped, duplicates }
}

/** The record's cells joined with ',' and truncated to 80 chars. */
function rawOf(record: CsvRecord): string {
  return truncate(record.cells.join(','), 80)
}

type FlagResult =
  | { ok: true; showBoosts: boolean; notify: boolean; hideNotifications: boolean }
  | { ok: false; column: string; value: string }

/**
 * Parse the type's boolean cells. A blank cell takes the type's default; a
 * non-blank non-boolean cell fails and names the column it was in.
 */
function parseFlagCells(type: ImportType, cells: string[]): FlagResult {
  let showBoosts = true
  let notify = false
  let hideNotifications = true
  if (type === 'following') {
    if (cells.length >= 2) {
      const flag = parseBooleanCell(cells[1])
      if (flag.kind === 'bad') return { ok: false, column: 'Show boosts', value: cells[1] }
      showBoosts = flag.kind === 'blank' ? true : flag.kind === 'true'
    }
    if (cells.length >= 3) {
      const flag = parseBooleanCell(cells[2])
      if (flag.kind === 'bad') return { ok: false, column: 'Notify on new posts', value: cells[2] }
      notify = flag.kind === 'blank' ? false : flag.kind === 'true'
    }
  } else if (type === 'mutes') {
    if (cells.length >= 2) {
      const flag = parseBooleanCell(cells[1])
      if (flag.kind === 'bad') return { ok: false, column: 'Hide notifications', value: cells[1] }
      hideNotifications = flag.kind === 'blank' ? true : flag.kind === 'true'
    }
  }
  return { ok: true, showBoosts, notify, hideNotifications }
}

/** The canonical CSV the server accepts for `type` containing exactly `rows`. */
export function serializeUpload(type: ImportType, rows: ImportRow[]): string {
  const csvField = (value: string): string =>
    /[\r\n",]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value
  const lines: string[] = []
  if (type === 'following') {
    lines.push('Account address,Show boosts,Notify on new posts,Languages')
    for (const row of rows) {
      lines.push(
        [csvField(row.address), row.showBoosts ? 'true' : 'false', row.notify ? 'true' : 'false', ''].join(','),
      )
    }
  } else if (type === 'mutes') {
    lines.push('Account address,Hide notifications')
    for (const row of rows) {
      lines.push([csvField(row.address), row.hideNotifications ? 'true' : 'false'].join(','))
    }
  } else {
    for (const row of rows) lines.push(csvField(row.address))
  }
  return lines.join('\n') + '\n'
}
