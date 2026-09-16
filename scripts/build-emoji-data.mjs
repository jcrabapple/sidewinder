/**
 * Generates src/state/emoji-data.json from the emojibase-data package
 * (Unicode CLDR labels + tags + CLDR shortcodes), trimmed to what the
 * picker needs: character, label, lowercase search tokens, group.
 *
 * Run: pnpm emojis
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const compact = require('emojibase-data/en/compact.json')
const shortcodes = require('emojibase-data/en/shortcodes/cldr.json')

const rows = compact
  .filter((e) => e.group !== undefined && e.group !== 2) // 2 = component (skin tones, etc.)
  .map((e) => {
    const tokens = new Set()
    tokens.add(e.label.toLowerCase())
    for (const t of e.tags ?? []) tokens.add(t.toLowerCase())
    for (const sc of shortcodes[e.hexcode] ?? []) tokens.add(sc.toLowerCase().replace(/_/g, ' '))
    return { c: e.unicode, l: e.label, g: e.group, t: [...tokens].slice(0, 10) }
  })

writeFileSync('src/state/emoji-data.json', JSON.stringify(rows))
console.log(`wrote src/state/emoji-data.json with ${rows.length} emoji`)
