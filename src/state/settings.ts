/** App settings: theme, media defaults, misc preferences. Persisted to localStorage. */
import { signal } from '@preact/signals'

export type ThemePref = 'system' | 'dark' | 'light'

export interface Settings {
  theme: ThemePref
  /** Warn before posting media with no alt text. */
  requireAltText: boolean
  /** Show 'alt' badge on media that has descriptions. */
  altBadge: boolean
  /** Autoplay animated GIFs/videos; false = click to play. */
  autoplayGifs: boolean
  /** Relative timestamps (e.g. "3m"); false = absolute dates. */
  relativeTimes: boolean
  /** Default post visibility for new posts. */
  defaultVisibility: 'public' | 'unlisted' | 'private' | 'direct'
}

const KEY = 'sidewinder.settings'

const DEFAULTS: Settings = {
  theme: 'system',
  requireAltText: true,
  altBadge: true,
  autoplayGifs: false,
  relativeTimes: true,
  defaultVisibility: 'public',
}

function load(): Settings {
  try {
    return { ...DEFAULTS, ...(JSON.parse(localStorage.getItem(KEY) ?? '{}') as Partial<Settings>) }
  } catch {
    return { ...DEFAULTS }
  }
}

export const settings = signal<Settings>(load())

function applyTheme(pref: ThemePref) {
  const root = document.documentElement
  const resolved =
    pref === 'system'
      ? window.matchMedia('(prefers-color-scheme: dark)').matches
        ? 'dark'
        : 'light'
      : pref
  root.dataset.theme = resolved
}

applyTheme(settings.value.theme)

// Follow system changes while in 'system' mode.
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
  if (settings.value.theme === 'system') applyTheme('system')
})

export function updateSettings(patch: Partial<Settings>) {
  settings.value = { ...settings.value, ...patch }
  applyTheme(settings.value.theme)
  localStorage.setItem(KEY, JSON.stringify(settings.value))
}
