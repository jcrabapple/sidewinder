# Import feature: contract and spec

Sidewinder imports follows, blocks and mutes from Mastodon / GoToSocial export
files by handing them to GoToSocial's own importer. This file is the single
source of truth for the feature. Read it fully before touching code.

Status: native-only scope (approved). Anything GoToSocial's importer cannot do
(lists, bookmarks, domain blocks, followers, filters, the account archive) is
RECOGNISED and EXPLAINED in the UI, never imported client-side.

## 1. Server facts (GoToSocial 0.22.2, verified against source at fb2bf13)

### POST /api/v1/import

- Auth: Bearer token with the `write` scope (Sidewinder sessions carry `read write follow push`).
- Body: `multipart/form-data` with
  - `data`: the CSV file (required)
  - `type`: `following` | `blocks` | `mutes` (required, lowercased by the server; anything else is a 400 whose message lists the valid types)
  - `mode`: `merge` | `overwrite` (optional, default merge)
- Success: `202 Accepted`, body `{"status":"accepted"}`. The file is parsed synchronously
  but the follows/blocks/mutes are created in a background worker. There is NO job id
  and NO status endpoint. Nothing reports completion or per-row failure.
- Errors: `400 {error}` (bad form, bad type/mode, unreadable CSV), `401`, `403`
  (account has moved: "forbidden after move"), `404`/`405` (route absent, so not
  a GoToSocial server that has the importer), `406` (Accept header), `422`
  ("import type not yet supported"), `500`.
- `mode=overwrite` builds the wanted set from the file, then REMOVES every existing
  follow/follow-request (or block, or mute) not in that set. An empty wanted set removes everything.
- Rows the worker cannot resolve (dead instance, blocked domain, blocked by target)
  are logged server-side and skipped. Follows of locked accounts become pending
  follow requests, which do NOT count in `following_count`.
- Temporary mutes are exported and imported as permanent mutes (the CSV has no expiry).

### Server-side CSV parsing (why the client must normalise first)

Go `encoding/csv` `ReadAll()` with default settings: the first record fixes the field
count and any later record with a different count makes the WHOLE upload a `400`
("record on line N: wrong number of fields"). A stray quote inside an unquoted
field also fails the whole file. After that, per-record rules, all of which skip the
row SILENTLY:

| type | rule |
|---|---|
| all | `record[0]` empty -> skip. For following and mutes, `record[0] == "Account address"` (exact case) is treated as the header and skipped. A leading `@` is added if missing, then the string must match `^@(U+)(?:@(D+))?$` where U = letters/marks/numbers/emoji/`.`/`-`/`_` and D = same plus `:`. No domain part means "an account on this server". A domain equal to this server's host or account-domain is cleared (treated as local). |
| following | 1 to 4 fields. If field 2 present it must parse with Go `strconv.ParseBool` (accepts `1 t T TRUE true True 0 f F FALSE false False`; a BLANK value fails and drops the row). Same for field 3 (notify). Field 4 (languages) ignored. |
| blocks | EXACTLY 1 field. Any record with 2+ fields (even a trailing comma) is dropped. No header skipping. |
| mutes | 1 or 2 fields. Field 2 (hide notifications) must parse as a Go bool; blank fails. |

Consequences the client protects against:

1. The server never checks that `type` matches the file. A blocks file sent as `following` follows everyone in it.
2. `overwrite` with a file whose rows are all dropped wipes the user's existing follows/blocks/mutes.
3. One ragged row rejects the file; blank booleans silently drop rows.
4. Completion is never reported.

### GET /api/v1/exports/stats

`200` JSON, read scope:

```json
{ "media_storage": "", "followers_count": 0, "following_count": 0, "statuses_count": 0,
  "lists_count": 0, "blocks_count": 0, "mutes_count": 0 }
```

Sidewinder uses `following_count` / `blocks_count` / `mutes_count` as the progress signal
(before upload vs. now). It is GoToSocial-only; `404` means the server has no importer.

### GET /api/v1/exports/{following,followers,lists,blocks,mutes}.csv

`200 text/csv`, Mastodon-compatible. Used for the pre-overwrite backup download.
Formats GoToSocial writes (and therefore reads back):

- `following.csv`: header `Account address,Show boosts,Notify on new posts,Languages`, rows `user@domain,true,false,` (languages always blank)
- `followers.csv`: header `Account address`, rows `user@domain`
- `lists.csv`: NO header, rows `List title,user@domain`
- `blocks.csv`: NO header, rows `user@domain`
- `mutes.csv`: header `Account address,Hide notifications`, rows `user@domain,true`

## 2. What Mastodon exports (verified against mastodon/mastodon main)

Settings > Import and export > Export gives one file per data type. Download names are the
controller name: older versions used the names in the second column.

| Mastodon file (current) | older name | Shape | GTS importable |
|---|---|---|---|
| `following_accounts.csv` | `follows.csv` | header `Account address,Show boosts,Notify on new posts,Languages`; rows `user@domain,true,false,"en, de"` | yes: `following` |
| `blocked_accounts.csv` | `blocks.csv` | 1 column, NO header | yes: `blocks` |
| `muted_accounts.csv` | `mutes.csv` | header `Account address,Hide notifications`; rows `user@domain,true` | yes: `mutes` |
| `lists.csv` | | NO header; rows `List name,user@domain` (one row per member) | no |
| `bookmarks.csv` | | 1 column of status URLs, NO header | no |
| `blocked_domains.csv` | `domain_blocks.csv` | 1 column of domains, NO header | no (GTS domain blocks are instance-wide, admin only) |
| `custom_filters.json` | | JSON `{"custom_filters":[...]}` | no |
| archive `.zip` (older: `.tar.gz`) | | `actor.json`, `outbox.json`, `likes.json`, `bookmarks.json`, `avatar.*`, `header.*`, `media_attachments/` | no (nobody imports posts) |

Account addresses are always `user@domain` with NO leading `@`; accounts local to the old
server carry the old server's domain. Mastodon's own importer also accepts the headers
`#domain`, `#uri`, `List name` (and optional header rows on blocks files), skips blank
lines, strips a leading `@`, caps files at 20 MB and 20,000 rows, and warns when a file
"looks mismatched" (guessed from headers first, then file name). Sidewinder mirrors that.

GoToSocial's own exports (`following.csv`, `followers.csv`, `lists.csv`, `blocks.csv`,
`mutes.csv`) use the same shapes. `followers.csv` is informational: nobody can import followers.

## 3. Architecture and file ownership

```
src/util/importCsv.ts       pure parser + detector + canonical writer      (Agent PARSER)
scripts/test-import-csv.mjs node:test unit tests, run by `pnpm test:import` (Agent PARSER)
scripts/fixtures/**         CSV fixtures for the tests                      (Agent PARSER)
src/state/importer.ts       upload, stats polling, job persistence, backup  (Agent STATE)
src/views/Import.tsx/.css   the page: drop zone, file cards, help           (Agent VIEW)
src/views/ImportConfirm.tsx/.css   confirm dialog (overwrite / mismatch)    (Agent DIALOG)
src/views/ImportJobs.tsx/.css      "Recent imports" progress panel          (Agent DIALOG)
```

Orchestrator-owned (do NOT edit): `registry.tsx` (route `#/import`), `Settings.tsx` (entry
card linking to `#/import`), `Nav.tsx`, `Icon.tsx` (icons `upload`, `download`, `file` already
added), `auth.ts`, `client.ts`, `app.css`, `shell.css`, `AGENTS.md`, `package.json`.

The route `#/import` is intentionally NOT a nav item: it is reached from a Settings card, and
Settings is on the desktop rail and the phone More sheet. Nothing is highlighted in Nav on it (same as thread/profile pages), so the
view header carries a back link to Settings.

Dependency direction: `Import.tsx` -> `ImportConfirm.tsx`, `ImportJobs.tsx`, `state/importer.ts`,
`util/importCsv.ts`. `ImportConfirm.tsx` and `ImportJobs.tsx` -> `state/importer.ts`, `util/importCsv.ts`.
`state/importer.ts` -> `util/importCsv.ts`. `util/importCsv.ts` imports nothing.

Exported names and signatures in the stub files are FROZEN contracts. Implement bodies; add
internal helpers freely; never rename or change the signature of an export. If a contract is
genuinely wrong, finish what you can and report the proposed change.

## 4. Parser rules (`src/util/importCsv.ts`)

Constraint: erasable TypeScript only (no `enum`, `namespace`, parameter properties, decorators)
because `node --experimental-strip-types` runs the file in the unit tests. No imports at all.

### parseImportText(text, fileName, size?)

1. `size` defaults to `text.length`. `size > MAX_FILE_BYTES` -> error `That file is larger than 20 MB.`
2. Strip one leading U+FEFF. If the text starts with `PK\u0003\u0004` or contains `\u0000` -> error
   `This does not look like a text file. Pick the CSV files from the export.`
3. Tokenise as RFC 4180: quoted fields, `""` escape, newlines inside quotes preserved, line breaks are
   `\r\n`, `\n` or `\r`. A quote in the middle of an unquoted field is literal. An unterminated quote ->
   error `Line N: a quoted value is never closed.` (N = line where that quote opened).
   Each record carries `line` = 1-based physical line where it starts. Records whose cells are all
   empty after trimming are blank lines and are dropped.
4. Header: if the first record's first cell (trim, lowercase) is one of `account address`, `#domain`,
   `#uri`, `list name`, it is the header: store trimmed cells in `header` and remove the record.
5. No data records left -> error: header present ? `The file has a header row but no rows.` : `The file is empty.`
6. More than `MAX_ROWS` records -> error `More than 20,000 rows (found N). Split the file and import the parts.`
7. `columnCount` = widest record after trimming trailing empty cells.
8. Detect (below). Return `ParsedFile`; `error` is set (and `records` may be empty) for whole-file problems.

### Detection (content first, file name second; mirrors Mastodon's header-then-name guess)

Header signals (`detectedBy: 'header'`, `candidates: []`), checked on the lowercased header cells:

1. contains `hide notifications` -> `mutes`
2. contains `show boosts`, `notify on new posts` or `languages` -> `following`
3. first cell `#domain` -> `domain_blocks`; `#uri` -> `bookmarks`; `list name` -> `lists`
4. otherwise (header is just `Account address`): fall through to shape.

Shape of the first 50 records (every sampled record must satisfy the class):

| shape | test |
|---|---|
| `urls` | exactly one non-empty cell, matching `^https?://` |
| `domains` | exactly one non-empty cell that has no `@`, matches `^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$` (case-insensitive) |
| `title+address` | 2+ cells; cell 2 is a valid address; cell 1 is NOT a valid address |
| `address` | exactly one non-empty cell, a valid address |
| `address+bool` | exactly 2 cells; cell 1 a valid address; cell 2 a boolean or empty |
| `address+flags` | 3 or 4 cells; cell 1 a valid address; cells 2 and 3 booleans or empty |
| `other` | anything else |

Map shape to kind:

- `urls` -> `bookmarks` (shape); `domains` -> `domain_blocks` (shape); `title+address` -> `lists` (shape)
- `address+flags` -> `following` (shape)
- `address`, `address+bool`: ask the file name. Lowercase the base name (no directories) and take the first
  match of: `/follower/` -> followers, `/mute/` -> mutes, `/block/` -> blocks, `/follow/` -> following.
  For `address+bool` only `mutes` and `following` are allowed results (any other name match is ignored).
  A match gives `detectedBy: 'filename'`.
- No match / `other` -> `kind: 'unknown'`, `detectedBy: 'none'`, `candidates`:
  `address` -> `['blocks','following','mutes']`, `address+bool` -> `['mutes','following']`, `other` -> `['following','blocks','mutes']`.
  (A content type beats a misleading file name: a `bookmarks-people.csv` full of addresses is `unknown`, not bookmarks.)

Valid address (client mirror of the server regex, plus: a domain part is REQUIRED):
`^([\p{L}\p{M}\p{N}._\-\p{Extended_Pictographic}]+)@([\p{L}\p{M}\p{N}.\-:\p{Extended_Pictographic}]+)$` with the `u` flag,
tested after trimming and removing ONE leading `@`.

Booleans (case-insensitive after trim): `true t 1` -> true, `false f 0` -> false. Anything else is "not a boolean". Empty is "blank".

### prepareRows(file, type)

Returns `{ type, rows, skipped, duplicates }`. If `file.error` is set, return empty arrays. For each record
(header already removed), first trim trailing empty cells (so `user@x.org,` is one cell), then:

- column limits: blocks exactly 1 cell, mutes 1-2 cells, following 1-4 cells; more -> skipped with
  `Too many columns for blocks (found N). This looks like a different kind of file.` (use the type's noun)
- cell 1 -> address: trim, drop one leading `@`, must be valid, else skipped `Not a valid account address ("…")`
  (truncate the quoted value to 60 chars); an address with no `@domain` -> `Missing the server part ("…")`
- following: cell 2 = Show boosts (blank/absent -> `true`), cell 3 = Notify (blank/absent -> `false`);
  mutes: cell 2 = Hide notifications (blank/absent -> `true`). A non-blank non-boolean value -> skipped
  `"Show boosts" must be true or false (found "maybe").` (use the column's header text)
- normalise address: `username` kept as written, `domain` lowercased; dedupe on the lowercased whole address,
  first occurrence wins, count the rest in `duplicates` (duplicates are NOT listed in `skipped`)
- skipped entries: `{ line, reason, raw }` where `raw` = the record's cells joined with `,` truncated to 80 chars
- every returned `ImportRow` has all three booleans set (defaults above) regardless of type

### serializeUpload(type, rows)

Canonical CSV the server accepts. `\n` line endings, trailing newline, no BOM.

- following: `Account address,Show boosts,Notify on new posts,Languages` then `address,showBoosts,notify,` per row (4 fields, last blank)
- blocks: `address` per row, NO header
- mutes: `Account address,Hide notifications` then `address,hideNotifications` per row

Quote a field only if it contains `,` `"` `\r` or `\n` (validated addresses never do).

### Test vectors (the unit tests must include every one of these)

`parseImportText(content, fileName)` -> `kind / detectedBy / header? / records.length`:

| # | fileName | content | expect |
|---|---|---|---|
| D1 | `following_accounts.csv` | `Account address,Show boosts,Notify on new posts,Languages\nalice@example.social,true,false,\nbob@example.org,false,true,"en, de"\n` | following / header / header set / 2 records |
| D2 | `muted_accounts.csv` | `Account address,Hide notifications\ncarol@example.net,true\n` | mutes / header |
| D3 | `blocked_accounts.csv` | `dave@example.com\nerin@example.org\n` | blocks / filename / no header |
| D4 | `export.csv` | same as D3 | unknown / none / candidates `['blocks','following','mutes']` |
| D5 | `x.csv` | `Friends,alice@example.social\nFriends,bob@example.org\n"Tech, news",carol@example.net\n` | lists / shape |
| D6 | `bookmarks.csv` | `https://example.social/@alice/111\nhttps://example.org/users/bob/statuses/22\n` | bookmarks / shape |
| D7 | `blocked_domains.csv` | `spam.example\nbad.example.org\n` | domain_blocks / shape |
| D8 | `followers.csv` | `Account address\nfrank@example.net\n` | followers / filename / header set |
| D9 | `following.csv` (GTS) | `Account address,Show boosts,Notify on new posts,Languages\nzed@localhost:18080,true,false,\n` | following / header |
| D10 | `m.csv` | `\uFEFFAccount address,Hide notifications\r\ngina@example.social,false\r\n` | mutes / header, 1 record, header[0] === `Account address` |
| D11 | `mutes.csv` | `alice@example.social,true\n` | mutes / filename / no header |
| D12 | `bookmarks-of-people.csv` | `alice@example.social\nbob@example.org\n` | unknown / none (name does not beat content) |
| D13 | `any.csv` | `alice@example.social,true,false,\n` | following / shape |
| D14 | `a.csv` | `` (empty) | error `The file is empty.` |
| D15 | `a.csv` | `Account address\n` | error `The file has a header row but no rows.` |
| D16 | `a.csv` | `PK\u0003\u0004rest` | error (not a text file) |
| D17 | `a.csv` | `Account address\n"alice@example.social\n` | error mentioning `Line 2` |
| D18 | `a.csv` | 20,001 address lines | error mentioning `20,000` |
| D19 | `a.csv` | `alice@example.social\n\n\n  ,  \nbob@example.org\n` | 2 records (blank lines dropped); the second record's `line` is 5 |

`prepareRows` (parse then prepare with the stated type):

| # | input | type | expect |
|---|---|---|---|
| P1 | `@Alice@Example.Social,true,false,` | following | 1 row, address `Alice@example.social`, showBoosts true, notify false |
| P2 | `alice@example.social`, `ALICE@EXAMPLE.SOCIAL`, `alice@example.social` | blocks | 1 row, duplicates 2, skipped 0 |
| P3 | `not-an-address`, `@@x`, `x@`, `@y`, `justuser`, `bad@@host` | blocks | rows 0, skipped 6, each with a reason (`justuser`/`@y`: Missing the server part) |
| P4 | `alice@example.social,,,` | following | showBoosts true, notify false (blanks take defaults) |
| P5 | `alice@example.social,maybe` | following | skipped, reason contains `Show boosts` |
| P6 | the D1 content | blocks | rows 0, skipped 2, reason starts `Too many columns for blocks` |
| P7 | `alice@example.social,true,false,x` | mutes | skipped (4 cells) |
| P8 | `alice@example.social,` | blocks | 1 row (trailing empty cell trimmed) |
| P9 | `a@x.org\nb@x.org,true\nc@x.org,true,false,\n` | following | 3 rows (ragged records are fine) |
| P10 | `ünïcode@example.org`, `bob@localhost:18080` | blocks | 2 rows |
| P11 | `alice@example.social,true` | mutes | hideNotifications true; `alice@example.social,false` -> false |
| P12 | `alice@example.social` | mutes | hideNotifications defaults to true |

`serializeUpload`:

| # | call | expect (exact string) |
|---|---|---|
| S1 | following, rows alice (true,false), bob (false,true) | `Account address,Show boosts,Notify on new posts,Languages\nalice@example.social,true,false,\nbob@example.org,false,true,\n` |
| S2 | mutes, rows carol (hide true), dan (hide false) | `Account address,Hide notifications\ncarol@example.net,true\ndan@example.org,false\n` |
| S3 | blocks, rows erin, frank | `erin@example.org\nfrank@example.net\n` |
| S4 | round trip: serialize(following) -> parse -> prepare(following) gives the same rows; serialize(mutes) re-detects as mutes by header |

`isMismatch(kind, selected)`: `(mutes, following)` true; `(following, following)` false; `(unknown, blocks)` false; `(lists, blocks)` true; `(followers, following)` true.

`classifyNonCsv(fileName)`: `archive-20240101-ab.zip` -> archive; `x.tar.gz` -> archive; `outbox.json`, `likes.json`, `bookmarks.json`, `actor.json` -> archive part; `custom_filters.json` -> filters; `data.json` -> other JSON; `a.csv`, `a.txt` -> null.

## 5. Importer rules (`src/state/importer.ts`)

- `fetchExportStats()`: `GET /api/v1/exports/stats` on the ACTIVE session's Api. `ApiError` 404/405 -> throw `ImportError('unsupported', ...)`.
- `countFor(stats, type)`: following -> `following_count`, blocks -> `blocks_count`, mutes -> `mutes_count`.
- `startImport({type, mode, fileName, rows})`:
  1. Capture the ACTIVE session once; every later request for this job uses an `Api` built from THAT session's `base` + `token`
     (looked up in `sessions` by id), never `api.value`, so switching accounts mid-watch cannot poll the wrong account.
  2. Refuse `rows.length === 0` with `ImportError('rejected', 'There is nothing valid to import.')` (belt and braces: the view also blocks it).
  3. Read stats for the baseline (before uploading).
  4. `serializeUpload`, build `FormData`: `data` = `new Blob([csv], {type:'text/csv'})` with filename `${type}.csv`, `type`, `mode`.
  5. `POST /api/v1/import` (60 s timeout). On success create the job (`phase: 'watching'`), persist, start polling, return it.
  6. Map failures with `ImportError` (see `importErrorMessage`).
- Polling: every `POLL_FAST_MS` (3 s) for the first `FAST_WINDOW_MS` (2 min), then every `POLL_SLOW_MS` (10 s). Each tick reads stats and
  updates `current`; when it changed set `lastChangeAt = now` and `phase = 'watching'`; when unchanged for `QUIET_AFTER_MS` (30 s) set
  `phase = 'quiet'` (keep polling). After `WATCH_LIMIT_MS` (15 min) from `startedAt`, or 5 consecutive failed polls, or the session is gone:
  `phase = 'ended'` with `endedReason` `timeout` / `error` / `session`. `stopJob` -> ended/`stopped`. Keep at most one timer per job.
- Persistence: `localStorage['sidewinder.importJobs']` = `Record<sessionId, ImportJob[]>`; at most 5 jobs per session, newest first;
  drop ended jobs older than 24 h on load. `resumeJobs()` restarts polling for stored jobs of the active session that are not ended and
  younger than `WATCH_LIMIT_MS` (and marks the rest ended/`timeout`). `jobs` is a signal of the ACTIVE session's jobs (recomputed when the active session changes).
  Parse stored JSON defensively (bad JSON -> empty).
- `clearJobsFor(sessionId)`: remove that session's jobs and stop its timers (the orchestrator wires this into logout).
- `dismissJob(id)`: stop its timer and delete the record.
- `downloadBackup(type)`: `GET /api/v1/exports/${type}.csv` with the active session's bearer token via `Api.fetchWithTimeout`; on `!ok`
  throw `ImportError`; turn the body into a Blob, click a temporary `<a download="sidewinder-backup-${type}-YYYY-MM-DD.csv">`, revoke the object URL.
- `importErrorMessage(e)`: `ImportError` -> its message; `NetworkError` -> `Could not reach the server. Check your connection and try again.`;
  anything else -> `Something went wrong while importing. Try again.` The `ImportError` messages: unsupported -> `This server does not have GoToSocial's importer, so Sidewinder cannot import files into it.`;
  scope (401/403 without move) -> `Your session cannot make changes. Sign out and back in to grant write access.`; moved (403 whose message contains "move") -> `Accounts that have moved cannot import data.`;
  rejected (400/422) -> the server's own message, prefixed `The server rejected the file: `.

## 6. UI spec

### Page (`ImportView`, route `#/import`)

Layout (all inside `<div class="import">`): sticky `header.view-header` with a back link
(`<a class="icon-btn" href="#/settings" aria-label="Back to Settings" title="Back to Settings">` holding `Icon chevron-left size 18`) then `h1` "Import", then in order:

1. Intro card `.import__intro.card`: "Bring follows, blocks and mutes over from a Mastodon or GoToSocial export. Sidewinder checks each file here first, then hands it to your server's importer." plus, when stats loaded, a status line
   `Your account now: Following 212 · Blocks 15 · Mutes 3` (class `.import__now`, numbers via `formatCount`, tabular). When stats fail with `ImportError` kind `unsupported`, show an `.error-note role="alert"` with the importErrorMessage text and disable the drop zone (`aria-disabled`, input `disabled`).
   Other stats failures: show nothing (neutral).
2. Help `<details class="import__help">`, summary "Where do I get these files?", body:
   - "On Mastodon: Settings, Import and export, Export. Download the CSV for follows, blocks and mutes (following_accounts.csv, blocked_accounts.csv, muted_accounts.csv)."
   - "On GoToSocial: Settings, Export & Import, then use the export buttons."
   - "You can pick several files at once. Each one is checked on its own."
3. Drop zone `label.import__drop`: text "Choose CSV files" + secondary "or drop them here" + `help-text` "Up to 20 MB and 20,000 rows per file." Icon `upload`. Contains `input.import__file-input[type=file][multiple][accept=".csv,.txt,.json,.zip,text/csv"]` (visually hidden but focusable; the label shows the focus ring via `:focus-within`). Drag over adds `.is-dragover`. Both picker and drop feed one `addFiles(files)`. Reset the input value after reading so the same file can be chosen again.
4. One `article.import__entry` per file (below), in the order added.
5. `<ImportJobsPanel />` (renders nothing when there are no jobs).
6. "What can't be imported" `section.import__limits.card`: h2 "What can't be imported" and a `<dl>` with one `dt`/`dd` per kind in `UNSUPPORTED_REASON` (label from `KIND_LABELS`, plus rows for filters and the archive using `classifyNonCsv` info for `custom_filters.json` and `archive.zip`).

### File entry states

`parsing` (spinner, `aria-busy`), `error` (`.error-note role="alert"` + Remove), `blocked` (recognised, not importable: label + reason, Remove), `ready` (full controls). Every entry header shows the file name (ellipsised), `formatBytes(size)`, and a Remove `icon-btn` (`aria-label="Remove {name}"`).

Pipeline per file: size over `MAX_FILE_BYTES` -> error entry WITHOUT reading it. `classifyNonCsv(name)` non-null -> `blocked` entry WITHOUT reading it (zip/json can be gigabytes). Otherwise `await file.text()` then `parseImportText`. `parsed.error` -> `error` entry. `parsed.kind` in followers/lists/bookmarks/domain_blocks -> `blocked` with `UNSUPPORTED_REASON[kind]`. Else `ready`.

`ready` entry contents:

- Detection line (`.import__detect`): header -> `Looks like: {KIND_LABELS[kind]}. Detected from the column headers.`; shape -> `... Detected from the contents.`; filename -> `... Detected from the file name.`; unknown -> `Can't tell what this file is. Choose the type below.`
- Field "Import as": `<select class="select import__type">` with a label, first option `Choose a type…` (value ""), then one option per `IMPORT_TYPES` labelled `{title}: {hint}` from `IMPORT_TYPE_INFO`. Preselect the detected importable kind; otherwise "" (and when `candidates` is non-empty, list candidates first in that order).
- Fieldset "If you already have some" (legend) with two radios (`input.import__mode`, name unique per entry): `merge` "Merge: keep what you have and add these" (default) and `overwrite` "Overwrite: replace what you have with these".
- Preview (`.import__preview`, with `data-ready`, `data-skipped`, `data-duplicates` attributes): recomputed with `useMemo(() => prepareRows(parsed, type))` whenever the type changes. Shows `{n} accounts ready to import` (`.import__ready`), `{d} duplicates removed` if d>0, `{s} rows skipped` if s>0, a sample of the first 5 addresses ("and N more"), and a `<details>` "{s} rows skipped" listing `Line {line}: {reason}` for the first 50 (then "and N more"). With no type chosen show nothing but the hint "Choose a type to see what would be imported."
- Zero valid rows with a type chosen: `.error-note` "No valid accounts in this file for {title}. Nothing will be imported." and the submit button disabled. This guard is what makes overwrite safe: overwrite can never be sent with zero rows.
- Mismatch (`isMismatch(parsed.kind, type)` and type chosen): `.error-note role="alert"` "This file looks like {KIND_LABELS[kind]}, but you chose {title}. Check the type before importing."
- Submit `button.btn.btn--primary.import__submit` (icon `upload`): label `Import {n} accounts` (singular `Import 1 account`); while sending `Sending…`; disabled when no type, zero rows, sending, or already sent.
  Click: if `mode === 'overwrite'` or mismatch -> open `ImportConfirmDialog` (below); otherwise send immediately.
- Sending: `await startImport(...)`; success -> toast "Import sent. Watching your counts below." and replace the controls with a `.import__sent` note "Sent to your server. It works through the file in the background and does not report back, so progress is under Recent imports." Failure -> `.error-note role="alert"` with `importErrorMessage(e)` and the controls stay.
- On mount: `resumeJobs()` and one `fetchExportStats()`.

### ConfirmDialog (`ImportConfirm.tsx`)

```ts
export interface ConfirmRequest { type: ImportType; mode: ImportMode; rowCount: number; fileName: string; mismatchKind: ImportKind | null }
export function ImportConfirmDialog(props: { request: ConfirmRequest; onConfirm: () => void; onCancel: () => void }): JSX.Element
```

Native `<dialog class="sw-dialog import-confirm">`; follow the Lists.tsx `ListEditDialog` pattern (showModal on mount, remember and restore focus, `cancel` event -> `onCancel`). The parent mounts it only while a confirm is pending.
Sections shown depending on the request:

- overwrite: title `Replace your {noun} with this file?`. On open fetch stats (`aria-busy` while loading): "You currently have {N} {noun}. This file has {rowCount}. Everything not in the file will be {removed}." where noun/removed come from `IMPORT_TYPE_INFO`. Stats failure: "Could not load your current count, but the import will still replace everything not in the file." Then "The server does this in the background and Sidewinder cannot undo it. Download a backup first if you might want the old list back." and a `button.btn.import-confirm__backup` "Download a backup" (`downloadBackup(type)`; busy state; after success "Backup downloaded" with the check icon; failure -> inline `.field-error role="alert"` with `importErrorMessage`). For `blocks` add: "Blocking an account also removes any follow between you and them."
- mismatch (`mismatchKind` set): heading text (or a second paragraph if also overwrite): `This file looks like {KIND_LABELS[mismatchKind]}.` "You chose {title}. Importing it this way is usually a mistake." with a REQUIRED checkbox `input.import-confirm__ack` labelled `Import it as {title} anyway`.
- Confirm button `button.import-confirm__go` (overwrite -> `btn btn--danger` "Replace my {noun}"; mismatch only -> `btn btn--primary` "Import anyway"); disabled until the checkbox is ticked when mismatch applies. Cancel `button.btn.import-confirm__cancel` "Cancel". Escape and backdrop click cancel.

### JobsPanel (`ImportJobs.tsx`)

```ts
export function ImportJobsPanel(): JSX.Element | null
```

Reads `jobs` from `state/importer`; `null` when empty. `section.import-jobs.card` with h2 "Recent imports". One `article.import-job` per job with `data-job-id`, `data-phase`, `data-baseline`, `data-current`:

- Title: `{IMPORT_TYPE_INFO[type].title} · {Merge|Overwrite}`; second line: file name, `{rows} accounts sent`, started `formatRelative(new Date(startedAt).toISOString())`.
- Counts line (`.import-job__counts`, `aria-live="off"`): `{Following}: {baseline} → {current}` and the signed delta `(+35)` / `(−12)` / `(no change)`; `.num` tabular.
- Phase sentence in a visually-polite `role="status"` element: watching -> "Watching your counts. They update every few seconds."; quiet -> "No change for {m}. The server may be done, or still working through slow lookups."; ended/stopped -> "Stopped watching."; ended/timeout -> "Stopped watching after 15 minutes."; ended/error -> "Stopped watching because the server could not be reached."; ended/session -> "Stopped watching because you switched accounts."
- Caveat `.help-text`: "The server skips accounts it cannot reach, accounts on blocked domains, and locked accounts that have not approved you yet, so the final count can be lower than the file." For blocks add "Blocking an account also removes any follow between you and them."
- Buttons: `Stop watching` (not ended) -> `stopJob`; `Dismiss` -> `dismissJob`.
- A running `Date.now()` for the quiet-minutes text re-renders on a 5 s interval while any job is not ended.

### Shared UI rules (from AGENTS.md, restated)

Design tokens only (no hex/rgb/oklch literals), class prefix per component (`.import__*`, `.import-confirm__*`, `.import-job__*`), one CSS file per component imported by it, sentence-case concrete copy with no exclamation marks and no em-dashes, every control has an accessible name, min 44px targets, loading/empty/error/success covered, native `<dialog>`, `role="alert"` for errors, strict TS with no `any`, components under ~300 lines (split if bigger), mobile-first (320px: no horizontal overflow; buttons may wrap; file names ellipsise), icons only from `Icon.tsx` (`upload`, `download`, `file`, `check`, `alert`, `x`, `refresh`, `clock` are available).

## 7. Gates and rules for every agent

- Gate: `pnpm exec tsc --noEmit` clean (run from `~/projects/sidewinder`). The PARSER agent also leaves `pnpm test:import` green.
  A tsc error in a file you do not own may be another agent mid-edit: re-run once after a minute before reporting it, and never fix other people's files.
- Forbidden: `git commit` / `git add` / `git push`, editing files you do not own, `pnpm add|install|remove`, `pnpm build` or `vite build` into `dist/` (that directory is the LIVE site), touching `~/sidewinder`, the production `gotosocial` container, `~/gotosocial`, or any token / `admin.env` file, calling `https://gts.example.org` with a real token, adding dependencies.
- Report: files touched, edge cases handled, assumptions made, anything in the contract you believe is wrong, and the exact commands you ran with their outcome (pass/fail counts). Do not paste secrets.
