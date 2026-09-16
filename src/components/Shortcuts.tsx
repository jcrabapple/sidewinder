/**
 * Keyboard shortcuts layer. Mount <ShortcutsHost /> once in the app shell.
 *
 * Single keys (handled in App.tsx): n = compose, / = search.
 * Here: g-prefixed navigation chords and "?" for the help dialog.
 * Shortcuts never fire while typing or with modifier keys held.
 */
import { useEffect, useRef, useState } from 'preact/hooks'
import { navigate } from '../router'
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
    title: 'Go to (press g, then the key)',
    rows: [
      { keys: 'g h', action: 'Home' },
      { keys: 'g l', action: 'Local timeline' },
      { keys: 'g f', action: 'Federated timeline' },
      { keys: 'g n', action: 'Notifications' },
      { keys: 'g s', action: 'Lists' },
      { keys: 'g v', action: 'Favourites' },
      { keys: 'g b', action: 'Bookmarks' },
      { keys: 'g ,', action: 'Settings' },
    ],
  },
]

const CHORDS: Record<string, string> = {
  h: '/home',
  l: '/local',
  f: '/federated',
  n: '/notifications',
  s: '/lists',
  v: '/favourites',
  b: '/bookmarks',
  ',': '/settings',
}

const CHORD_TIMEOUT_MS = 1200

export function ShortcutsHost() {
  const [helpOpen, setHelpOpen] = useState(false)
  const chordPending = useRef(false)
  const chordTimer = useRef<number | undefined>(undefined)

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
      if (!activeSession.value) return

      if (chordPending.current) {
        const dest = CHORDS[e.key]
        clearChord()
        if (dest) {
          e.preventDefault()
          navigate(dest)
        }
        return
      }

      if (e.key === 'g') {
        chordPending.current = true
        chordTimer.current = window.setTimeout(clearChord, CHORD_TIMEOUT_MS)
      } else if (e.key === '?') {
        e.preventDefault()
        setHelpOpen(true)
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
    dlg.showModal()
    const onCancel = () => onClose()
    dlg.addEventListener('cancel', onCancel)
    return () => dlg.removeEventListener('cancel', onCancel)
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
