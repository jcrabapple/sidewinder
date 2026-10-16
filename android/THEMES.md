# Theming design (Appearance)

Goal: a set of bundled color themes, each with a light AND dark variant, chosen in a new
**Appearance** section of Settings together with mode, Dynamic colors, pure-black dark and text
size. Plus the Sidewinder logo in the app's brand slots.

## Themes (id, display name, light variant / dark variant, official source)

Use ONLY colors from each theme's official palette (cite the source URL in a comment above each
palette). Do not invent hex values. If a role has no exact palette color, pick the nearest palette
color; never blend new ones, except `accentContainer` which may be a palette color OR the accent
mixed toward the background (document the mix ratio).

| id | Name | Light | Dark | Source |
|----|------|-------|------|--------|
| `sidewinder` | Sidewinder | existing Light* tokens in Color.kt | existing Dark* tokens | src/app.css (current) |
| `catppuccin` | Catppuccin | Latte | Mocha | https://github.com/catppuccin/palette (palette.json) |
| `nord` | Nord | Snow Storm grounds (nord4-6) + Polar Night text (nord0-3) | Polar Night grounds + Snow Storm text | https://www.nordtheme.com/docs/colors-and-palettes |
| `rosepine` | Rosé Pine | Dawn | Main (Rosé Pine) | https://rosepinetheme.com/palette/ |
| `tokyonight` | Tokyo Night | Day | Night | https://github.com/folke/tokyonight.nvim (lua/tokyonight/colors/*.lua; Day = `day` style) |
| `gruvbox` | Gruvbox | light (medium) | dark (medium) | https://github.com/morhetz/gruvbox (README palette) |
| `dracula` | Dracula | Alucard | Dracula | https://draculatheme.com/spec |
| `solarized` | Solarized | light | dark | https://ethanschoonover.com/solarized/ |
| `everforest` | Everforest | light (medium) | dark (medium) | https://github.com/sainnhe/everforest/blob/master/palette.md |
| `kanagawa` | Kanagawa | Lotus | Wave | https://github.com/rebelot/kanagawa.nvim (lua/kanagawa/colors.lua) |

Fetch the palettes with the web tools (web_extract / web_search); record each hex in code with the
palette's own color name in a comment, e.g. `val base = Color(0xFF1E1E2E) // Mocha "Base"`.

## Code shape

- `ui/theme/ThemePalette.kt`:
  ```kotlin
  data class ThemePalette(
      val bg: Color, val surface: Color, val surface2: Color, val surface3: Color,
      val fg: Color, val fgMuted: Color,
      val accent: Color, val onAccent: Color, val accentContainer: Color, val onAccentContainer: Color,
      val secondary: Color, val danger: Color, val warn: Color,
      val outline: Color, val outlineVariant: Color,
      val favourite: Color, val bookmark: Color,
  )
  data class AppTheme(val id: String, val name: String, val light: ThemePalette, val dark: ThemePalette)
  object AppThemes { val all: List<AppTheme>; fun byId(id: String): AppTheme /* unknown -> sidewinder */ }
  fun ThemePalette.toColorScheme(dark: Boolean, pureBlack: Boolean): ColorScheme
  ```
  `toColorScheme` maps roles exactly like the current DarkColors/LightColors in Theme.kt
  (primary=accent, onPrimary=onAccent, primaryContainer, background=bg, surface, surfaceVariant=
  surface2, surfaceContainer* ladder, onSurfaceVariant=fgMuted, outline, error=danger, tertiary=warn,
  secondary=secondary) so every screen keeps working. `pureBlack` (dark only) sets background,
  surface, surfaceContainerLowest/Low to #000000 and keeps the rest.
- One file per theme family under `ui/theme/palettes/` (e.g. `Catppuccin.kt`). Sidewinder's palette
  is built from the existing Color.kt tokens (keep Color.kt; it is the web-synced source).
- Typical role picks (adapt per theme, keep the theme's identity): accent = the theme's signature
  hue (Catppuccin Mauve, Nord nord8 frost, Rosé Pine Iris on dark / Pine on Dawn if contrast needs it,
  Tokyo Night blue, Gruvbox aqua or yellow, Dracula purple, Solarized blue, Everforest green,
  Kanagawa crystal blue / lotus blue); favourite = the theme's yellow/gold; bookmark = its
  purple/magenta (choose a different hue than accent where possible); danger = red; warn = orange/yellow.
- `SidewinderTheme(themeId, mode-resolved darkTheme, dynamicColor, pureBlack, reduceMotion)`:
  dynamic color (API 31+) overrides the palette's scheme exactly as today; `LocalActionColors`
  comes from the palette's favourite/bookmark (dynamic: keep the current derivation).

## Contrast guard (unit test, required)

`ThemeContrastTest` iterates every theme x {light, dark} x pureBlack where applicable and asserts
WCAG 2.x contrast ratios (write the relative-luminance math in the test helper):
- fg on bg >= 7.0 and fg on surface >= 7.0 (relax to 4.5 only for themes whose official text/base
  pair is below 7, and list those exceptions explicitly in the test with a comment)
- fgMuted on surface >= 4.5
- onAccent on accent >= 4.5
- onAccentContainer on accentContainer >= 4.5
- accent on bg >= 3.0 (icons/links), favourite on surface >= 3.0, bookmark on surface >= 3.0,
  danger on surface >= 3.0
If an official palette pairing fails, choose a different palette color for that role
(e.g. a darker variant on the light theme) until it passes. Never weaken the thresholds to
make a theme pass (the documented 4.5 text exception is the only relaxation allowed).

## Settings model (core/settings/SettingsStore.kt)

- `ThemePref` keeps decoding old values, but its meaning becomes the MODE: SYSTEM / LIGHT / DARK.
  Stored legacy `AMOLED` migrates to mode DARK + `pureBlack = true` (unit test the migration).
- New fields on AppSettings + Stored (defaults keep current look): `themeId: String = "sidewinder"`,
  `pureBlack: Boolean = false`. Keep `dynamicColor`.
- Settings backup/export (ui/settings/SettingsScreen.kt `SettingsBackup`) round-trips `themeId` and
  `pureBlack` (old backup files without them still import; unknown themeId falls back to sidewinder).
  Extend `SettingsBackupTest`.

## Appearance section (Settings)

New file `ui/settings/AppearanceSection.kt`, placed FIRST in the Settings list under a section
header "Appearance". Remove the old Theme row, Dynamic colors row and Text size row from their old
places (they move here). Contents, in order:
1. **Mode**: `SingleChoiceSegmentedButtonRow` System / Light / Dark.
2. **Theme**: a grid of theme cards (2 columns; `LazyVerticalGrid` is NOT allowed inside the
   scrolling settings list, so use rows of 2 built with `chunked(2)`). Each card: theme name, and a
   preview of BOTH variants side by side (two small rounded panels, each showing bg with a surface
   strip, an accent dot, a fg text line "Aa", favourite + bookmark dots). Selected card: 2dp accent
   border + check icon. Card is `selectable(role = Role.RadioButton)`, merged semantics
   "Catppuccin theme, selected/not selected"; min height 48dp. Tapping applies immediately.
   When Dynamic colors is on, the grid is disabled (alpha 0.5, not clickable) with the note
   "Dynamic colors are on, so your wallpaper sets the colors. Turn them off to pick a theme."
3. **Dynamic colors** toggle ("Use colors from your wallpaper. Android 12 and newer."). On API < 31
   the row is shown disabled with "Needs Android 12 or newer."
4. **Pure black** toggle ("Black backgrounds in dark mode. Saves battery on OLED screens.").
   Disabled with explanation when mode is Light.
5. **Text size** (the existing control, moved).
Follow the existing ToggleRow a11y pattern (whole row toggleable(Role.Switch), inner Switch
clearAndSetSemantics). Copy: sentence case, no exclamation points.

## System bars + window

The app's theme can now be light or dark independent of the system. In MainActivity, after the
scheme is resolved, set `WindowCompat.getInsetsController(window, view)` light status/nav bar icons
when the resolved scheme background is light (luminance > 0.5), and the window background color to
the scheme background, in a `SideEffect`, so status-bar icons stay visible on every theme.

## Logo in the app (brand slots)

`res/drawable-nodpi/sidewinder_logo.png` (384px, transparent corners, circular medallion) is already
added by the orchestrator; do not regenerate it. Use it via `painterResource(R.drawable.sidewinder_logo)`:
- Top app bar brand (where the "Sidewinder" title is drawn on top-level screens): 28dp logo to the
  left of the title, contentDescription null (title text is the name).
- Login screen: 96dp logo centered above the heading, contentDescription "Sidewinder logo".
The launcher icon (mipmap ic_launcher*) is already done; do not touch res/mipmap-* or colors.xml's
ic_launcher_background.
