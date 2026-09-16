/**
 * Hash router. Routes live in location.hash as "#/path?query".
 * Hash routing keeps the static host dumb: any file server works, no rewrites.
 */
import { signal } from '@preact/signals'
import { useEffect } from 'preact/hooks'

export interface Route {
  /** Path segments without the leading '#'. Empty array = home. */
  segments: string[]
  query: URLSearchParams
  /** The raw hash, for comparisons. */
  href: string
}

function parse(): Route {
  const raw = location.hash.replace(/^#/, '') || '/'
  const [path, queryStr = ''] = raw.split('?')
  const segments = path.split('/').filter(Boolean).map(decodeURIComponent)
  return { segments, query: new URLSearchParams(queryStr), href: raw }
}

export const route = signal<Route>(parse())

let started = false

export function startRouter() {
  if (started) return
  started = true
  window.addEventListener('hashchange', () => {
    route.value = parse()
  })
}

export function navigate(path: string, { replace = false } = {}) {
  const target = '#' + (path.startsWith('/') ? path : '/' + path)
  if (location.hash === target) return
  if (replace) {
    history.replaceState(null, '', target)
    route.value = parse()
  } else {
    location.hash = target
  }
}

/** Keep the document title in sync with the active view. */
export function useTitle(title: string) {
  useEffect(() => {
    document.title = title ? `${title} · Sidewinder` : 'Sidewinder'
  }, [title])
}
