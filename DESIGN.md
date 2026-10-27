# Sidewinder — DESIGN.md

Visual system ground truth, recorded from the built app. Update here when the
system changes durably.

## World

"Viper in the grass": a serpent's stillness — neutral grounds tinted toward one
hue, and a single confident green that strikes, never floods. Restrained color
strategy: tinted neutrals + one accent. Chroma is earned per element.

## Color

All OKLCH, hue family 155. Neutrals carry chroma 0.004–0.018 toward 155; never
pure gray, never #000/#fff.

- Grounds: `--bg` (page), `--surface` (cards), `--surface-2`/`--surface-3`
  (raised, hover). Dark: L 0.185→0.31. Light: L 0.968→0.90.
- Foreground: `--fg` / `--fg-muted` / `--fg-faint`, tinted from the same hue.
- Accent: `--accent` (dark L 0.78 C 0.145; light L 0.52 C 0.125),
  `--accent-strong` for hover, `--accent-muted` for tints.
- Semantic: `--danger` (red, hue 25), `--warn`/`--fav` (amber, 75–90),
  `--bookmark` (violet, 290). Boost reuses the accent.
- Text on accent is `--on-accent` (dark ground on dark theme), never gray.

## Type

System UI stack (workhorse; Operate surface). Base 15px, `--lh-body` 1.55.
Scale: 0.8125 / 0.875 / 1 / 1.125 / 1.375 / 1.75rem. Tabular numerals for all
counts. Tracking −0.02em on display sizes only.

## Iconography

Inline SVG, 24px grid, stroke 1.75, round caps/joins, one weight everywhere.
No emoji or unicode glyphs as icons.

## Layout

- Desktop ≥920px: nav rail (icons+labels, 230px) · single main column
  (680px max). Threads and profiles use that column; no detail pane is built.
- Below: bottom tab bar (44px+ targets), safe-area padding.
- Compose is a modal `<dialog>` (protected focus); quick actions are inline.

## Motion

Exponential ease-out (`--ease-out`), 120–220ms. One authored moment: new
statuses rise in 8px. Compose dialog: fade+scale 0.98→1. Everything collapses
under `prefers-reduced-motion`.

## Browser surfaces (themed, never defaults)

Selection (accent-tinted), focus ring (2px accent, 2px offset), thin scrollbars
(`--surface-3`), underline offset 0.18em on links, caret color accent,
tabular-nums in counters.

## Bans honored

No side-stripes, no gradient text, no glass decoration, no emoji icons, no
identical card grids as page structure, no purple gradients, no glow halos,
no kicker labels above headings.
