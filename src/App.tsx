import { useEffect } from 'preact/hooks'
import { activeSession, activeId } from './state/auth'
import { route, startRouter, navigate } from './router'
import { StartView, consumeOAuthReturn } from './views/Start'
import { NavRail, MobileTabs } from './components/Nav'
import { AppRoutes } from './views/registry'
import { ComposeHost } from './views/Compose'
import { ToastHost } from './components/ToastHost'
import { openCompose } from './state/compose'
import './shell.css'

startRouter()

// An OAuth redirect lands on the SPA origin with ?code=&state= — consume it
// before routing; without a session this goes to the login view regardless.
consumeOAuthReturn().catch((e) => console.error('OAuth return failed', e))

export function App() {
  // Global must-haves: 'n' opens compose, '/' jumps to search.
  useEffect(() => {
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

  if (!activeSession.value) return <StartView />

  return (
    <div class="shell">
      <a class="skip-link" href="#main-content">Skip to content</a>
      <NavRail />
      <main id="main-content" class="main-col" key={activeId.value}>
        <AppRoutes route={route.value} />
      </main>
      <MobileTabs />
      <ComposeHost />
      <ToastHost />
    </div>
  )
}
