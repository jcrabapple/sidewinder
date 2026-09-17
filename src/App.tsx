import { useEffect } from 'preact/hooks'
import { activeSession, activeId } from './state/auth'
import { touchActivity } from './state/readState'
import { route, startRouter, navigate } from './router'
import { StartView, consumeOAuthReturn } from './views/Start'
import { NavRail, MobileTabs, MobileFab } from './components/Nav'
import { AppRoutes } from './views/registry'
import { ComposeHost } from './views/Compose'
import { ToastHost } from './components/ToastHost'
import { openCompose } from './state/compose'
import { toast } from './state/toast'
import { installKeyboardSheetFix } from './util/keyboard'
import { refreshFollowRequestCount } from './state/notifications'
import { syncMarkersFromServer } from './state/markers'
import { ShortcutsHost } from './components/Shortcuts'
import './shell.css'

startRouter()
installKeyboardSheetFix()

// An OAuth redirect lands on the SPA origin with ?code=&state= — consume it
// before routing; without a session this goes to the login view regardless.
consumeOAuthReturn().catch((e) => {
  console.error('OAuth return failed', e)
  toast(e instanceof Error ? e.message : 'Signing in failed. Start again from the login page.', 'error')
})

export function App() {
  // Adopt server read markers once the session is known (re-runs on switch).
  const sessionId = activeSession.value?.id ?? null
  useEffect(() => {
    if (sessionId) void syncMarkersFromServer()
  }, [sessionId])

  // Activity heartbeat for absence-aware read marking (see readState.ts):
  // user interaction keeps the session "fresh"; after ABSENCE_MS of none,
  // the next timeline load counts as returning and stops marking posts read.
  useEffect(() => {
    touchActivity()
    let last = 0
    const beat = () => {
      const now = Date.now()
      if (now - last > 10_000) {
        last = now
        touchActivity()
      }
    }
    const opts = { passive: true, capture: true } as AddEventListenerOptions
    const events = ['click', 'keydown', 'scroll', 'touchstart'] as const
    for (const ev of events) window.addEventListener(ev, beat, opts)
    return () => {
      for (const ev of events) window.removeEventListener(ev, beat, opts)
    }
  }, [])

  // Global must-haves: 'n' opens compose, '/' jumps to search.
  useEffect(() => {
    refreshFollowRequestCount()
    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      const typing =
        /^(input|textarea|select)$/i.test(target.tagName) || target.isContentEditable
      if (typing || e.metaKey || e.ctrlKey || e.altKey) return
      if (e.key === 'n' && activeSession.value) {
        e.preventDefault()
        openCompose()
      } else if (e.key === '/') {
        e.preventDefault()
        navigate('/search')
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [])

  if (!activeSession.value)
    return (
      <>
        <StartView />
        <ToastHost />
      </>
    )

  return (
    <div class="shell">
      <a class="skip-link" href="#main-content">Skip to content</a>
      <NavRail />
      <main id="main-content" class="main-col" key={activeId.value}>
        <AppRoutes route={route.value} />
      </main>
      <MobileTabs />
      <MobileFab />
      <ComposeHost />
      <ToastHost />
      <ShortcutsHost />
    </div>
  )
}
