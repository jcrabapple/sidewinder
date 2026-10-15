/**
 * Unit tests for src/util/importCsv.ts, run by `pnpm test:import`:
 *   node --experimental-strip-types --test scripts/test-import-csv.mjs
 *
 * Covers every vector D1-D19, P1-P12, S1-S4, the isMismatch and
 * classifyNonCsv cases from IMPORT.md section 4, plus edge cases and the
 * fixtures under scripts/fixtures/.
 */
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  parseImportText,
  prepareRows,
  serializeUpload,
  isMismatch,
  classifyNonCsv,
  MAX_ROWS,
} from '../src/util/importCsv.ts'

const here = dirname(fileURLToPath(import.meta.url))
const fixtures = join(here, 'fixtures')

function addresses(n) {
  const lines = []
  for (let i = 0; i < n; i++) lines.push(`user${i}@example.org`)
  return lines.join('\n') + '\n'
}

function rowOf(parsed, type, i = 0) {
  return prepareRows(parsed, type).rows[i]
}

describe('parseImportText: detection vectors D1-D19', () => {
  test('D1 following_accounts.csv -> following / header / 2 records', () => {
    const p = parseImportText(
      'Account address,Show boosts,Notify on new posts,Languages\nalice@example.social,true,false,\nbob@example.org,false,true,"en, de"\n',
      'following_accounts.csv',
    )
    assert.equal(p.error, null)
    assert.equal(p.kind, 'following')
    assert.equal(p.detectedBy, 'header')
    assert.ok(p.header)
    assert.equal(p.records.length, 2)
  })

  test('D2 muted_accounts.csv -> mutes / header', () => {
    const p = parseImportText('Account address,Hide notifications\ncarol@example.net,true\n', 'muted_accounts.csv')
    assert.equal(p.kind, 'mutes')
    assert.equal(p.detectedBy, 'header')
    assert.ok(p.header)
  })

  test('D3 blocked_accounts.csv -> blocks / filename / no header', () => {
    const p = parseImportText('dave@example.com\nerin@example.org\n', 'blocked_accounts.csv')
    assert.equal(p.kind, 'blocks')
    assert.equal(p.detectedBy, 'filename')
    assert.equal(p.header, null)
  })

  test('D4 export.csv (same content) -> unknown / none / candidates', () => {
    const p = parseImportText('dave@example.com\nerin@example.org\n', 'export.csv')
    assert.equal(p.kind, 'unknown')
    assert.equal(p.detectedBy, 'none')
    assert.deepEqual(p.candidates, ['blocks', 'following', 'mutes'])
  })

  test('D5 lists by shape (titles incl. a comma)', () => {
    const p = parseImportText(
      'Friends,alice@example.social\nFriends,bob@example.org\n"Tech, news",carol@example.net\n',
      'x.csv',
    )
    assert.equal(p.kind, 'lists')
    assert.equal(p.detectedBy, 'shape')
    assert.equal(p.records[2].cells[0], 'Tech, news')
  })

  test('D6 bookmarks by shape', () => {
    const p = parseImportText(
      'https://example.social/@alice/111\nhttps://example.org/users/bob/statuses/22\n',
      'bookmarks.csv',
    )
    assert.equal(p.kind, 'bookmarks')
    assert.equal(p.detectedBy, 'shape')
  })

  test('D7 blocked_domains.csv -> domain_blocks / shape', () => {
    const p = parseImportText('spam.example\nbad.example.org\n', 'blocked_domains.csv')
    assert.equal(p.kind, 'domain_blocks')
    assert.equal(p.detectedBy, 'shape')
  })

  test('D8 followers.csv -> followers / filename / header set', () => {
    const p = parseImportText('Account address\nfrank@example.net\n', 'followers.csv')
    assert.equal(p.kind, 'followers')
    assert.equal(p.detectedBy, 'filename')
    assert.deepEqual(p.header, ['Account address'])
  })

  test('D9 GTS following.csv with port domain -> following / header', () => {
    const p = parseImportText(
      'Account address,Show boosts,Notify on new posts,Languages\nzed@localhost:18080,true,false,\n',
      'following.csv',
    )
    assert.equal(p.kind, 'following')
    assert.equal(p.detectedBy, 'header')
  })

  test('D10 BOM + CRLF -> mutes / header, header[0] exact', () => {
    const p = parseImportText('\uFEFFAccount address,Hide notifications\r\ngina@example.social,false\r\n', 'm.csv')
    assert.equal(p.kind, 'mutes')
    assert.equal(p.detectedBy, 'header')
    assert.equal(p.records.length, 1)
    assert.equal(p.header[0], 'Account address')
  })

  test('D11 mutes.csv -> mutes / filename / no header', () => {
    const p = parseImportText('alice@example.social,true\n', 'mutes.csv')
    assert.equal(p.kind, 'mutes')
    assert.equal(p.detectedBy, 'filename')
    assert.equal(p.header, null)
  })

  test('D12 misleading name does not beat content', () => {
    const p = parseImportText('alice@example.social\nbob@example.org\n', 'bookmarks-of-people.csv')
    assert.equal(p.kind, 'unknown')
    assert.equal(p.detectedBy, 'none')
  })

  test('D13 any.csv address+flags -> following / shape', () => {
    const p = parseImportText('alice@example.social,true,false,\n', 'any.csv')
    assert.equal(p.kind, 'following')
    assert.equal(p.detectedBy, 'shape')
  })

  test('D14 empty file', () => {
    const p = parseImportText('', 'a.csv')
    assert.equal(p.error, 'The file is empty.')
  })

  test('D15 header but no rows', () => {
    const p = parseImportText('Account address\n', 'a.csv')
    assert.equal(p.error, 'The file has a header row but no rows.')
  })

  test('D16 zip bytes', () => {
    const p = parseImportText('PK\u0003\u0004rest', 'a.csv')
    assert.match(p.error, /does not look like a text file/)
  })

  test('D17 unterminated quote names the line it opened on', () => {
    const p = parseImportText('Account address\n"alice@example.social\n', 'a.csv')
    assert.match(p.error, /Line 2/)
  })

  test('D18 more than 20,000 rows', () => {
    const p = parseImportText(addresses(20_001), 'a.csv')
    assert.match(p.error, /20,000/)
  })

  test('D19 blank and whitespace-only lines dropped, line numbers kept', () => {
    const p = parseImportText('alice@example.social\n\n\n  ,  \nbob@example.org\n', 'a.csv')
    assert.equal(p.records.length, 2)
    assert.equal(p.records[0].line, 1)
    assert.equal(p.records[1].line, 5)
  })
})

describe('parseImportText: tokenizer edge cases', () => {
  test('CR-only line endings', () => {
    const p = parseImportText('alice@example.social\rbob@example.org\r', 'a.csv')
    assert.equal(p.error, null)
    assert.equal(p.records.length, 2)
    assert.equal(p.records[1].line, 2)
  })

  test('CRLF line endings', () => {
    const p = parseImportText('alice@example.social\r\nbob@example.org\r\n', 'a.csv')
    assert.equal(p.records.length, 2)
    assert.equal(p.records[1].line, 2)
  })

  test('quoted field containing a newline; next record line number accounts for it', () => {
    const p = parseImportText('Friends,alice@example.social\n"Multi\nline",bob@example.org\ncarol@example.net\n', 'a.csv')
    assert.equal(p.records.length, 3)
    assert.equal(p.records[1].cells[0], 'Multi\nline')
    // "Multi\nline" occupies physical lines 2-3, so the next record starts on 4.
    assert.equal(p.records[2].line, 4)
  })

  test('quote in the middle of an unquoted field is literal', () => {
    const p = parseImportText('al"ice@example.social\n', 'a.csv')
    assert.equal(p.records[0].cells[0], 'al"ice@example.social')
  })

  test('doubled quote escape', () => {
    const p = parseImportText('"say ""hi""",bob@example.org\n', 'a.csv')
    assert.equal(p.records[0].cells[0], 'say "hi"')
  })

  test('header detected in any case (ACCOUNT ADDRESS)', () => {
    const p = parseImportText('ACCOUNT ADDRESS\nalice@example.social\n', 'a.csv')
    assert.deepEqual(p.header, ['ACCOUNT ADDRESS'])
    assert.equal(p.records.length, 1)
  })

  test('trailing comma row is one cell', () => {
    const p = parseImportText('alice@example.social,\n', 'a.csv')
    assert.equal(p.records[0].cells.length, 2)
    assert.equal(p.columnCount, 1)
  })

  test('file larger than 20 MB by size argument', () => {
    const p = parseImportText('alice@example.social\n', 'a.csv', 21 * 1024 * 1024)
    assert.equal(p.error, 'That file is larger than 20 MB.')
  })

  test('NUL byte rejects the file', () => {
    const p = parseImportText('alice@example.social\n\u0000', 'a.csv')
    assert.match(p.error, /does not look like a text file/)
  })

  test('exactly 20,000 rows accepted', () => {
    const p = parseImportText(addresses(MAX_ROWS), 'a.csv')
    assert.equal(p.error, null)
    assert.equal(p.records.length, MAX_ROWS)
  })
})

describe('prepareRows: vectors P1-P12', () => {
  const parse = (text, name = 'a.csv') => parseImportText(text, name)

  test('P1 leading @ stripped, username kept, domain lowercased', () => {
    const row = rowOf(parse('@Alice@Example.Social,true,false,'), 'following')
    assert.equal(row.address, 'Alice@example.social')
    assert.equal(row.showBoosts, true)
    assert.equal(row.notify, false)
  })

  test('P2 case-insensitive dedupe, duplicates not in skipped', () => {
    const p = prepareRows(parse('alice@example.social\nALICE@EXAMPLE.SOCIAL\nalice@example.social\n'), 'blocks')
    assert.equal(p.rows.length, 1)
    assert.equal(p.duplicates, 2)
    assert.equal(p.skipped.length, 0)
  })

  test('P3 invalid addresses each skipped with a reason', () => {
    const p = prepareRows(parse('not-an-address\n@@x\nx@\n@y\njustuser\nbad@@host\n'), 'blocks')
    assert.equal(p.rows.length, 0)
    assert.equal(p.skipped.length, 6)
    for (const s of p.skipped) assert.ok(s.reason.length > 0)
    const missing = p.skipped.filter((s) => s.reason.startsWith('Missing the server part'))
    // not-an-address, @@x (empty user after strip), x@, @y, justuser
    assert.ok(missing.length >= 5, `expected most to be missing-domain, got ${p.skipped.map((s) => s.reason)}`)
  })

  test('P4 blanks take defaults', () => {
    const row = rowOf(parse('alice@example.social,,,'), 'following')
    assert.equal(row.showBoosts, true)
    assert.equal(row.notify, false)
  })

  test('P5 non-boolean Show boosts skipped', () => {
    const p = prepareRows(parse('alice@example.social,maybe'), 'following')
    assert.equal(p.rows.length, 0)
    assert.match(p.skipped[0].reason, /Show boosts/)
    assert.match(p.skipped[0].reason, /maybe/)
  })

  test('P6 D1 content as blocks: too many columns', () => {
    const p = prepareRows(
      parse(
        'Account address,Show boosts,Notify on new posts,Languages\nalice@example.social,true,false,\nbob@example.org,false,true,"en, de"\n',
        'following_accounts.csv',
      ),
      'blocks',
    )
    assert.equal(p.rows.length, 0)
    assert.equal(p.skipped.length, 2)
    assert.ok(p.skipped[0].reason.startsWith('Too many columns for blocks'))
  })

  test('P7 4 cells skipped for mutes', () => {
    const p = prepareRows(parse('alice@example.social,true,false,x'), 'mutes')
    assert.equal(p.rows.length, 0)
    assert.equal(p.skipped.length, 1)
    assert.match(p.skipped[0].reason, /Too many columns for mutes/)
  })

  test('P8 trailing empty cell trimmed for blocks', () => {
    const p = prepareRows(parse('alice@example.social,'), 'blocks')
    assert.equal(p.rows.length, 1)
  })

  test('P9 ragged records fine for following', () => {
    const p = prepareRows(parse('a@x.org\nb@x.org,true\nc@x.org,true,false,\n'), 'following')
    assert.equal(p.rows.length, 3)
  })

  test('P10 unicode username and port domain accepted', () => {
    const p = prepareRows(parse('\u00fcn\u00efcode@example.org\nbob@localhost:18080\n'), 'blocks')
    assert.equal(p.rows.length, 2)
    assert.equal(p.rows[1].address, 'bob@localhost:18080')
  })

  test('P11 mutes boolean parsed', () => {
    assert.equal(rowOf(parse('alice@example.social,true'), 'mutes').hideNotifications, true)
    assert.equal(rowOf(parse('alice@example.social,false'), 'mutes').hideNotifications, false)
  })

  test('P12 mutes blank flag defaults to true', () => {
    assert.equal(rowOf(parse('alice@example.social'), 'mutes').hideNotifications, true)
  })

  test('Mastodon quoted languages cell parses as one field', () => {
    const p = parseImportText('bob@example.org,false,true,"en, de"\n', 'a.csv')
    assert.equal(p.records[0].cells[3], 'en, de')
  })

  test('mutes blank second cell still defaults true', () => {
    const row = rowOf(parse('alice@example.social,'), 'mutes')
    assert.equal(row.hideNotifications, true)
  })

  test('following notify non-boolean names its column', () => {
    const p = prepareRows(parse('alice@example.social,true,maybe'), 'following')
    assert.match(p.skipped[0].reason, /Notify on new posts/)
  })

  test('raw is cells joined with comma, truncated to 80', () => {
    const long = 'a'.repeat(100)
    const p = prepareRows(parse(`${long}\n`), 'blocks')
    assert.equal(p.skipped[0].raw.length, 80)
  })

  test('every ImportRow carries all three booleans', () => {
    const rows = prepareRows(parse('a@x.org\nb@x.org,true\n'), 'blocks').rows
    for (const row of rows) {
      assert.equal(typeof row.showBoosts, 'boolean')
      assert.equal(typeof row.notify, 'boolean')
      assert.equal(typeof row.hideNotifications, 'boolean')
    }
    assert.equal(rows[0].showBoosts, true)
    assert.equal(rows[0].notify, false)
    assert.equal(rows[0].hideNotifications, true)
  })

  test('prepareRows on a file with an error returns empty arrays', () => {
    const p = prepareRows(parseImportText('', 'a.csv'), 'blocks')
    assert.deepEqual(p.rows, [])
    assert.deepEqual(p.skipped, [])
    assert.equal(p.duplicates, 0)
  })
})

describe('serializeUpload: vectors S1-S4', () => {
  const row = (address, showBoosts, notify, hideNotifications) => ({
    line: 1,
    address,
    showBoosts,
    notify,
    hideNotifications,
  })

  test('S1 following exact string', () => {
    assert.equal(
      serializeUpload('following', [row('alice@example.social', true, false, true), row('bob@example.org', false, true, true)]),
      'Account address,Show boosts,Notify on new posts,Languages\nalice@example.social,true,false,\nbob@example.org,false,true,\n',
    )
  })

  test('S2 mutes exact string', () => {
    assert.equal(
      serializeUpload('mutes', [row('carol@example.net', true, false, true), row('dan@example.org', true, false, false)]),
      'Account address,Hide notifications\ncarol@example.net,true\ndan@example.org,false\n',
    )
  })

  test('S3 blocks exact string', () => {
    assert.equal(
      serializeUpload('blocks', [row('erin@example.org', true, false, true), row('frank@example.net', true, false, true)]),
      'erin@example.org\nfrank@example.net\n',
    )
  })

  test('S4a round trip: serialize(following) -> parse -> prepare gives the same rows', () => {
    const csv = serializeUpload('following', [row('alice@example.social', true, false, true), row('bob@example.org', false, true, true)])
    const parsed = parseImportText(csv, 'upload.csv')
    assert.equal(parsed.kind, 'following')
    const prepared = prepareRows(parsed, 'following')
    assert.deepEqual(
      prepared.rows.map((r) => [r.address, r.showBoosts, r.notify]),
      [
        ['alice@example.social', true, false],
        ['bob@example.org', false, true],
      ],
    )
  })

  test('S4b serialize(mutes) re-detects as mutes by header', () => {
    const csv = serializeUpload('mutes', [row('carol@example.net', true, false, true)])
    const parsed = parseImportText(csv, 'upload.csv')
    assert.equal(parsed.kind, 'mutes')
    assert.equal(parsed.detectedBy, 'header')
  })

  test('round trip blocks', () => {
    const csv = serializeUpload('blocks', [row('erin@example.org', true, false, true)])
    const parsed = parseImportText(csv, 'upload.csv')
    assert.equal(parsed.records.length, 1)
    const prepared = prepareRows(parsed, 'blocks')
    assert.deepEqual(prepared.rows.map((r) => r.address), ['erin@example.org'])
  })
})

describe('isMismatch and classifyNonCsv', () => {
  test('isMismatch cases', () => {
    assert.equal(isMismatch('mutes', 'following'), true)
    assert.equal(isMismatch('following', 'following'), false)
    assert.equal(isMismatch('unknown', 'blocks'), false)
    assert.equal(isMismatch('lists', 'blocks'), true)
    assert.equal(isMismatch('followers', 'following'), true)
  })

  test('classifyNonCsv cases', () => {
    assert.equal(classifyNonCsv('archive-20240101-ab.zip')?.label, 'Account archive')
    assert.equal(classifyNonCsv('x.tar.gz')?.label, 'Account archive')
    assert.equal(classifyNonCsv('outbox.json')?.label, 'Account archive file')
    assert.equal(classifyNonCsv('likes.json')?.label, 'Account archive file')
    assert.equal(classifyNonCsv('bookmarks.json')?.label, 'Account archive file')
    assert.equal(classifyNonCsv('actor.json')?.label, 'Account archive file')
    assert.equal(classifyNonCsv('custom_filters.json')?.label, 'Filters')
    assert.equal(classifyNonCsv('data.json')?.label, 'JSON file')
    assert.equal(classifyNonCsv('a.csv'), null)
    assert.equal(classifyNonCsv('a.txt'), null)
  })
})

describe('fixtures', () => {
  const cases = [
    ['mastodon-following_accounts.csv', 'following_accounts.csv', 'following', 'header'],
    ['mastodon-blocked_accounts.csv', 'blocked_accounts.csv', 'blocks', 'filename'],
    ['mastodon-muted_accounts.csv', 'muted_accounts.csv', 'mutes', 'header'],
    ['mastodon-lists.csv', 'lists.csv', 'lists', 'shape'],
    ['mastodon-bookmarks.csv', 'bookmarks.csv', 'bookmarks', 'shape'],
    ['mastodon-blocked_domains.csv', 'blocked_domains.csv', 'domain_blocks', 'shape'],
    ['gts-following.csv', 'following.csv', 'following', 'header'],
    ['gts-followers.csv', 'followers.csv', 'followers', 'filename'],
    ['gts-blocks.csv', 'blocks.csv', 'blocks', 'filename'],
    ['gts-mutes.csv', 'mutes.csv', 'mutes', 'header'],
    ['gts-lists.csv', 'lists.csv', 'lists', 'shape'],
  ]

  for (const [file, asName, kind, detectedBy] of cases) {
    test(`${file} detected as ${kind} (${detectedBy})`, () => {
      const text = readFileSync(join(fixtures, file), 'utf8')
      const p = parseImportText(text, asName)
      assert.equal(p.error, null, `fixture ${file} should parse cleanly`)
      assert.equal(p.kind, kind)
      assert.equal(p.detectedBy, detectedBy)
    })
  }

  test('fixtures contain a port-domain row where expected', () => {
    for (const file of ['gts-following.csv', 'gts-followers.csv', 'gts-blocks.csv', 'gts-mutes.csv', 'gts-lists.csv']) {
      const text = readFileSync(join(fixtures, file), 'utf8')
      assert.match(text, /localhost:18080/, `${file} should include a port domain`)
    }
  })

  test('gts-following fixture prepares cleanly', () => {
    const text = readFileSync(join(fixtures, 'gts-following.csv'), 'utf8')
    const p = prepareRows(parseImportText(text, 'following.csv'), 'following')
    assert.equal(p.skipped.length, 0)
    assert.ok(p.rows.length >= 5)
  })
})

describe('performance', () => {
  test('20,000 rows parse+prepare in under 1 second', () => {
    const text = addresses(MAX_ROWS)
    const start = performance.now()
    const parsed = parseImportText(text, 'a.csv')
    const prepared = prepareRows(parsed, 'blocks')
    const elapsed = performance.now() - start
    assert.equal(prepared.rows.length, MAX_ROWS)
    assert.ok(elapsed < 1000, `took ${elapsed.toFixed(0)}ms`)
  })
})

describe('regressions found in review', () => {
  test('#domain header is a header: kind domain_blocks by header, header row not a record', () => {
    const f = parseImportText('#domain\nspam.example\nbad.example.org\n', 'x.csv')
    assert.equal(f.kind, 'domain_blocks')
    assert.equal(f.detectedBy, 'header')
    assert.deepEqual(f.header, ['#domain'])
    assert.equal(f.records.length, 2)
  })

  test('#uri header -> bookmarks by header', () => {
    const f = parseImportText('#uri\nhttps://example.social/@a/1\n', 'x.csv')
    assert.equal(f.kind, 'bookmarks')
    assert.equal(f.detectedBy, 'header')
  })

  test('List name header -> lists by header (case-insensitive)', () => {
    const f = parseImportText('LIST NAME,Account address\nFriends,alice@example.social\n', 'x.csv')
    assert.equal(f.kind, 'lists')
    assert.equal(f.detectedBy, 'header')
    assert.equal(f.records.length, 1)
  })

  test('#domain header with no rows reports the header-only error', () => {
    assert.equal(parseImportText('#domain\n', 'x.csv').error, 'The file has a header row but no rows.')
  })

  test('following: a bad SHOW BOOSTS cell is blamed on Show boosts, with its own value', () => {
    const p = prepareRows(parseImportText('alice@example.social,maybe,false', 'a.csv'), 'following')
    assert.equal(p.rows.length, 0)
    assert.match(p.skipped[0].reason, /^"Show boosts" must be true or false \(found "maybe"\)\.$/)
  })

  test('following: a bad NOTIFY cell is blamed on Notify on new posts', () => {
    const p = prepareRows(parseImportText('alice@example.social,true,nope,', 'a.csv'), 'following')
    assert.match(p.skipped[0].reason, /^"Notify on new posts" must be true or false \(found "nope"\)\.$/)
  })

  test('mutes: a bad cell is blamed on Hide notifications', () => {
    const p = prepareRows(parseImportText('alice@example.social,sure', 'a.csv'), 'mutes')
    assert.match(p.skipped[0].reason, /^"Hide notifications" must be true or false \(found "sure"\)\.$/)
  })

  test('invisible characters are not allowed in addresses (server regex would reject them)', () => {
    const zwnj = '\u200c'
    const p = prepareRows(parseImportText(`ali${zwnj}ce@example.social\nbob@exa${zwnj}mple.org\n`, 'blocked_accounts.csv'), 'blocks')
    assert.equal(p.rows.length, 0)
    assert.equal(p.skipped.length, 2)
  })

  test('source file contains no invisible or non-ASCII characters inside regex literals', () => {
    const src = readFileSync(join(here, '..', 'src', 'util', 'importCsv.ts'), 'utf8')
    const hits = [...src.matchAll(/^.*\/\^.*\/u.*$/gm)].map((m) => m[0])
    for (const line of hits) assert.ok(!/[^\x00-\x7f]/.test(line), `non-ASCII in: ${line}`)
  })
})

describe('real GoToSocial exports (captured from a live 0.22.2 instance)', () => {
  const real = (name) => readFileSync(join(fixtures, `real-gts-${name}.csv`), 'utf8')
  const dataLines = (text) => text.split('\n').filter((l) => l.trim() !== '')

  test('following.csv: header detected, every row valid, flags preserved', () => {
    const text = real('following')
    const f = parseImportText(text, 'following.csv')
    assert.equal(f.kind, 'following')
    assert.equal(f.detectedBy, 'header')
    const p = prepareRows(f, 'following')
    assert.equal(p.skipped.length, 0)
    assert.equal(p.rows.length, dataLines(text).length - 1)
    assert.ok(p.rows.some((r) => r.showBoosts === false) && p.rows.some((r) => r.notify === true), 'fixture has non-default flags')
  })

  test('blocks.csv: headerless single column, detected by file name', () => {
    const text = real('blocks')
    const f = parseImportText(text, 'blocks.csv')
    assert.equal(f.kind, 'blocks')
    assert.equal(f.detectedBy, 'filename')
    assert.equal(prepareRows(f, 'blocks').rows.length, dataLines(text).length)
  })

  test('mutes.csv: header detected, every row valid', () => {
    const text = real('mutes')
    const f = parseImportText(text, 'mutes.csv')
    assert.equal(f.kind, 'mutes')
    assert.equal(f.detectedBy, 'header')
    const p = prepareRows(f, 'mutes')
    assert.equal(p.skipped.length, 0)
    assert.equal(p.rows.length, dataLines(text).length - 1)
  })

  test('lists.csv: recognised by content, not importable', () => {
    const f = parseImportText(real('lists'), 'lists.csv')
    assert.equal(f.kind, 'lists')
    assert.equal(f.detectedBy, 'shape')
  })

  test('followers.csv from an account with no followers is header-only', () => {
    const f = parseImportText(real('followers'), 'followers.csv')
    assert.equal(f.error, 'The file has a header row but no rows.')
  })

  test('re-serialising a real export reproduces the rows (canonical writer is a fixed point for GTS output)', () => {
    for (const [name, type] of [['following', 'following'], ['blocks', 'blocks'], ['mutes', 'mutes']]) {
      const first = prepareRows(parseImportText(real(name), `${name}.csv`), type).rows
      const csv = serializeUpload(type, first)
      const again = prepareRows(parseImportText(csv, `${name}.csv`), type).rows
      assert.deepEqual(again.map((r) => [r.address, r.showBoosts, r.notify, r.hideNotifications]), first.map((r) => [r.address, r.showBoosts, r.notify, r.hideNotifications]))
    }
    // and for following/mutes the canonical text equals what GTS itself wrote (modulo trailing newline)
    assert.equal(serializeUpload('following', prepareRows(parseImportText(real('following'), 'following.csv'), 'following').rows).trim(), real('following').trim())
    assert.equal(serializeUpload('mutes', prepareRows(parseImportText(real('mutes'), 'mutes.csv'), 'mutes').rows).trim(), real('mutes').trim())
    assert.equal(serializeUpload('blocks', prepareRows(parseImportText(real('blocks'), 'blocks.csv'), 'blocks').rows).trim(), real('blocks').trim())
  })
})
