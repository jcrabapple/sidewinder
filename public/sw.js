/*
 * Sidewinder service worker.
 * - Static build assets (hashed by Vite): cache-first.
 * - Navigations: network-first with a cached-app-shell fallback for offline.
 * - API and streaming traffic is never intercepted.
 */
const VERSION = 'v1'
const STATIC_CACHE = `sidewinder-static-${VERSION}`
const SHELL_CACHE = `sidewinder-shell-${VERSION}`
const SHELL_URL = '/index.html'

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(SHELL_CACHE)
      .then((cache) => cache.add(SHELL_URL))
      .then(() => self.skipWaiting()),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((k) => k !== STATIC_CACHE && k !== SHELL_CACHE)
            .map((k) => caches.delete(k)),
        ),
      )
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET') return

  const url = new URL(req.url)

  // Never touch API, streaming, or cross-origin traffic.
  if (url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/oauth/')) return

  // Navigations: network-first, fall back to the cached shell when offline.
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((res) => {
          const copy = res.clone()
          caches.open(SHELL_CACHE).then((cache) => cache.put(SHELL_URL, copy))
          return res
        })
        .catch(() => caches.match(SHELL_URL)),
    )
    return
  }

  // Hashed build assets: cache-first, they are immutable.
  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(
      caches.match(req).then(
        (hit) =>
          hit ??
          fetch(req).then((res) => {
            if (res.ok) {
              const copy = res.clone()
              caches.open(STATIC_CACHE).then((cache) => cache.put(req, copy))
            }
            return res
          }),
      ),
    )
    return
  }

  // Everything else same-origin (icons, manifest): stale-while-revalidate.
  event.respondWith(
    caches.match(req).then((hit) => {
      const fresh = fetch(req)
        .then((res) => {
          if (res.ok) {
            const copy = res.clone()
            caches.open(STATIC_CACHE).then((cache) => cache.put(req, copy))
          }
          return res
        })
        .catch(() => hit)
      return hit ?? fresh
    }),
  )
})
