/**
 * Keyboard shortcuts layer. Mount <ShortcutsHost /> once in the app shell.
 *
 * Single keys (handled in App.tsx): n = compose, / = search.
 * Here:
 *  - g-prefixed navigation chords
 *  - j/k post navigation + r/f/b/s/Enter actions on the focused post
 *  - "?" for the help dialog.
 * Shortcuts never fire while typing, with modifier keys held, or while a
 * modal dialog is open.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import { navigate, route } from '../router'
import { activeSession } from '../state/auth'
import './Shortcuts.css'

interface ShortcutRow {
  keys: string
  action: string
}

const SECTIONS: { title: string; rows: ShortcutRow[] }[] = [
  {
    title: 'Actions',
    rows: [
      { keys: 'n', action: 'New post' },
      { keys: '/', action: 'Search' },
      { keys: '?', action: 'This help' },
    ],
  },
  {
    title: 'Post navigation',
    rows: [
      { keys: 'j', action: 'Next post' },
      { keys: 'k', action: 'Previous post' },
      { keys: 'Enter', action: 'Open focused post thread' },
      { keys: 'r', action: 'Reply to focused post' },
      { keys: 'f', action: 'Favourite / unfavourite focused post' },
      { keys: 'b', action: 'Boost / unboost focused post' },
      { keys: 's', action: 'Bookmark / unbookmark focused post' },
    ],
  },
  {
    title: 'Go to (press g, then the key)',
    rows: [
      { keys: 'g h', action: 'Home' },
      { keys: 'g l', action: 'Local timeline' },
      { keys: 'g f', action: 'Federated timeline' },
      { keys: 'g n', action: 'Notifications' },
      { keys: 'g c', action: 'Catch-up' },
      { keys: 'g p', action: 'Your profile' },
      { keys: 'g s', action: 'Lists' },
      { keys: 'g v', action: 'Favourites' },
      { keys: 'g b', action: 'Bookmarks' },
      { keys: 'g r', action: 'Follow requests' },
      { keys: 'g w', action: 'Wrapped' },
      { keys: 'g ,', action: 'Settings' },
    ],
  },
]

const CHORDS: Record<string, string | (() => string)> = {
  h: '/home',
  l: '/local',
  f: '/federated',
  n: '/notifications',
  c: '/catchup',
  p: () => (activeSession.value?.account.id ? `/accounts/${activeSession.value.account.id}` : '/'),
  s: '/lists',
  v: '/favourites',
  b: '/bookmarks',
  r: '/follow_requests',
  w: '/wrapped',
  ',': '/settings',
}

const CHORD_TIMEOUT_MS = 1200

function clearKbdFocus() {
  for (const el of document.querySelectorAll('.status--kbd-focus')) {
    el.classList.remove('status--kbd-focus')
  }
}

function getFocusedCard(): HTMLElement | null {
  return document.querySelector<HTMLElement>('.status--kbd-focus[data-status-id]')
}

function movePostFocus(delta: 1 | -1): void {
  const cards = Array.from(document.querySelectorAll<HTMLElement>('.main-col article.status[data-status-id]'))
  if (cards.length === 0) return
  const current = getFocusedCard()
  let nextIdx = 0
  if (current) {
    const idx = cards.indexOf(current)
    nextIdx = idx === -1 ? 0 : Math.min(Math.max(idx + delta, 0), cards.length - 1)
  } else {
    // Pick the first card at or below the top of the viewport.
    const firstVisible = cards.findIndex((c) => c.getBoundingClientRect().top >= 48)
    nextIdx = firstVisible >= 0 ? firstVisible : 0
  }
  clearKbdFocus()
  const target = cards[nextIdx]
  if (target) {
    target.classList.add('status--kbd-focus')
    target.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }
}

export function ShortcutsHost() {
  const [helpOpen, setHelpOpen] = useState(false)
  const chordPending = useRef(false)
  const chordTimer = useRef<number | undefined>(undefined)

  // Clear keyboard post focus on route changes.
  useEffect(() => {
    clearKbdFocus()
  }, [route.value.href])

  useEffect(() => {
    function clearChord() {
      chordPending.current = false
      window.clearTimeout(chordTimer.current)
    }

    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      const typing =
        /^(input|textarea|select)$/i.test(target.tagName) || target.isContentEditable
      if (typing || e.metaKey || e.ctrlKey || e.altKey) {
        clearChord()
        return
      }
      // Do not intercept keys while any modal dialog is open.
      if (document.querySelector('dialog[open]')) {
        clearChord()
        return
      }
      if (!activeSession.value) return

      if (chordPending.current) {
        const entry = CHORDS[e.key]
        clearChord()
        if (entry) {
          e.preventDefault()
          const dest = typeof entry === 'function' ? entry() : entry
          navigate(dest)
        }
        return
      }

      if (e.key === 'g') {
        chordPending.current = true
        chordTimer.current = window.setTimeout(clearChord, CHORD_TIMEOUT_MS)
        return
      }
      if (e.key === '?') {
        e.preventDefault()
        setHelpOpen(true)
        return
      }
      if (e.key === 'j') {
        e.preventDefault()
        movePostFocus(1)
        return
      }
      if (e.key === 'k') {
        e.preventDefault()
        movePostFocus(-1)
        return
      }

      const focused = getFocusedCard()
      if (!focused) return

      if (e.key === 'Enter' && target === document.body) {
        const id = focused.dataset.statusId
        if (id) {
          e.preventDefault()
          navigate(`/statuses/${id}`)
        }
      } else if (e.key === 'r') {
        const btn = focused.querySelector<HTMLButtonElement>('.action--reply')
        if (btn && !btn.disabled) {
          e.preventDefault()
          btn.click()
        }
      } else if (e.key === 'f') {
        const btn = focused.querySelector<HTMLButtonElement>('.action--fav')
        if (btn && !btn.disabled) {
          e.preventDefault()
          btn.click()
        }
      } else if (e.key === 'b') {
        const btn = focused.querySelector<HTMLButtonElement>('.action--boost')
        if (btn && !btn.disabled) {
          e.preventDefault()
          btn.click()
        }
      } else if (e.key === 's') {
        const btn = focused.querySelector<HTMLButtonElement>('.action--bookmark')
        if (btn && !btn.disabled) {
          e.preventDefault()
          btn.click()
        }
      }
    }

    window.addEventListener('keydown', handler)
    return () => {
      window.removeEventListener('keydown', handler)
      clearChord()
    }
  }, [])

  return helpOpen ? <ShortcutsHelpDialog onClose={() => setHelpOpen(false)} /> : null
}

function ShortcutsHelpDialog({ onClose }: { onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dlg = ref.current
    if (!dlg) return
    const prevFocus = document.activeElement as HTMLElement | null
    dlg.showModal()
    const onCancel = () => onClose()
    dlg.addEventListener('cancel', onCancel)
    return () => {
      dlg.removeEventListener('cancel', onCancel)
      prevFocus?.focus()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <dialog class="sw-dialog shortcuts__dialog" ref={ref} aria-labelledby="shortcuts-title">
      <div class="sw-dialog__head">
        <h2 id="shortcuts-title">Keyboard shortcuts</h2>
      </div>
      <div class="sw-dialog__body">
        {SECTIONS.map((section) => (
          <section key={section.title} class="shortcuts__section">
            <h3>{section.title}</h3>
            <dl class="shortcuts__list">
              {section.rows.map((row) => (
                <div class="shortcuts__row" key={row.keys}>
                  <dt>
                    {row.keys.split(' ').map((k, i) => (
                      <span key={i}>
                        {i > 0 && <span class="shortcuts__then">then</span>}
                        <kbd>{k === ',' ? ',' : k}</kbd>
                      </span>
                    ))}
                  </dt>
                  <dd>{row.action}</dd>
                </div>
              ))}
            </dl>
          </section>
        ))}
        <div class="shortcuts__actions">
          <button class="btn" onClick={onClose}>Close</button>
        </div>
      </div>
    </dialog>
  )
}
