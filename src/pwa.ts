/**
 * PWA registration. Call registerPwa() once at startup (main.tsx wires this).
 * Registers /sw.js and surfaces an "update available" toast that reloads into
 * the new version on confirmation.
 */
import { toast } from './state/toast'

export function registerPwa() {
  if (!('serviceWorker' in navigator)) return
  // Dev server serves TS sources; the SW only makes sense for built assets.
  if (import.meta.env.DEV) return

  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').then(
      (reg) => {
        reg.addEventListener('updatefound', () => {
          const worker = reg.installing
          if (!worker) return
          worker.addEventListener('statechange', () => {
            if (worker.state === 'installed' && navigator.serviceWorker.controller) {
              toast('A new version is ready. Reload to update.')
            }
          })
        })
      },
      (err) => console.warn('Service worker registration failed', err),
    )
  })
}
