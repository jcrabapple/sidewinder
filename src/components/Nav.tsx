/**
 * Navigation: left rail on wide screens, bottom tab bar on narrow.
 * All targets ≥44px, current location marked for screen readers via
 * aria-current="page".
 */
import { useRef, useState } from 'preact/hooks'
import { route, navigate } from '../router'
import { Icon } from './Icon'
import { activeSession, sessions, switchAccount, logout } from '../state/auth'
import { unreadNotificationCount, COUNT_WINDOW } from '../state/notifications'
import { openCompose } from '../state/compose'
import { scrollToTop } from '../util/scroll'
import './Nav.css'

interface NavItem {
  href: string
  label: string
  icon: string
  /** Only rendered for admin/moderator accounts. */
  admin?: boolean
}

const ITEMS: NavItem[] = [
  { href: '/', label: 'Home', icon: 'home' },
  { href: '/local', label: 'Local', icon: 'users' },
  { href: '/federated', label: 'Federated', icon: 'globe' },
  { href: '/catchup', label: 'Catch-up', icon: 'inbox' },
  { href: '/notifications', label: 'Notifications', icon: 'bell' },
  { href: '/search', label: 'Search', icon: 'search' },
  { href: '/lists', label: 'Lists', icon: 'list' },
  { href: '/favourites', label: 'Favourites', icon: 'star' },
  { href: '/bookmarks', label: 'Bookmarks', icon: 'bookmark' },
  { href: '/moderation', label: 'Moderation', icon: 'shield', admin: true },
  { href: '/settings', label: 'Settings', icon: 'sliders' },
]

/** Is this session's account an instance admin/moderator (per its role claim)? */
function isAdminRole(sess: { account?: { role?: { name?: string } } } | null): boolean {
  const role = sess?.account?.role?.name?.toLowerCase()
  return role === 'admin' || role === 'moderator'
}

function isActive(href: string, segments: string[]): boolean {
  const parts = href.split('/').filter(Boolean)
  if (parts.length === 0) return segments.length === 0
  if (parts.length !== segments.length) return false
  return parts.every((p, i) => p === segments[i])
}

/** Tapping the destination you are already on scrolls it back to the top. */
function reTapToTop(e: MouseEvent, active: boolean) {
  if (!active || e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
  e.preventDefault()
  scrollToTop()
}

function NavButton({ item }: { item: NavItem }) {
  const active = isActive(item.href, route.value.segments)
  const unread = item.href === '/notifications' ? unreadNotificationCount.value : 0
  const countLabel = unread >= COUNT_WINDOW ? `${COUNT_WINDOW}+` : String(unread)
  return (
    <a
      href={'#' + item.href}
      class="nav-link"
      aria-current={active ? 'page' : undefined}
      onClick={(e) => reTapToTop(e, active)}
    >
      <span class="nav-link__icon">
        <Icon name={item.icon} size={22} />
      </span>
      <span>{item.label}</span>
      {unread > 0 && (
        <>
          <span class="nav-count" aria-hidden="true">{countLabel}</span>
          <span class="visually-hidden">{`, ${countLabel} unread`}</span>
        </>
      )}
    </a>
  )
}

export function NavRail() {
  const sess = activeSession.value
  if (!sess) return null
  return (
    <nav class="nav-rail" aria-label="Primary">
      <a href="#/" class="nav-brand" aria-label="Sidewinder home">
        <img src="/favicon.png" alt="" width="34" height="34" />
        <span class="nav-brand__name">Sidewinder</span>
      </a>
      <button class="btn btn--primary nav-compose" onClick={() => openCompose()}>
        <Icon name="pencil" size={18} />
        Post
      </button>
      <div class="nav-rail__items">
        {ITEMS.filter((item) => !item.admin || isAdminRole(sess)).map((item) => (
          <NavButton key={item.href} item={item} />
        ))}
        <button
          type="button"
          class="nav-link nav-logout"
          onClick={() => {
            logout()
            navigate('/')
          }}
        >
          <span class="nav-link__icon">
            <Icon name="logout" size={22} />
          </span>
          <span>Logout</span>
        </button>
      </div>
      <div class="nav-rail__footer">
        <a href={'#/accounts/' + sess.account.id} class="nav-account" aria-current={isActive('/accounts/' + sess.account.id, route.value.segments) ? 'page' : undefined}>
          <img class="nav-account__avatar" src={sess.account.avatar} alt="" width="34" height="34" />
          <span class="nav-account__meta">
            <strong>{sess.account.display_name || sess.account.username}</strong>
            <span>@{sess.account.acct}</span>
          </span>
        </a>
        {sessions.value.length > 1 && (
          <div class="nav-switch">
            <span class="visually-hidden" id="switch-label">Switch account</span>
            <select
              class="select"
              aria-labelledby="switch-label"
              value={activeSession.value?.id}
              onChange={(e) => switchAccount((e.target as HTMLSelectElement).value)}
            >
              {sessions.value.map((s) => (
                <option key={s.id} value={s.id}>
                  @{s.account.acct} · {new URL(s.base).host}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>
    </nav>
  )
}

// Mobile tabs: a fixed subset of ITEMS, selected by href so that
// reordering or inserting into ITEMS cannot silently reshuffle the tabs.
// Everything else lives behind the "More" sheet.
const MOBILE_HREFS = ['/', '/notifications', '/search', '/catchup']
const MOBILE: NavItem[] = MOBILE_HREFS
  .map((href) => ITEMS.find((i) => i.href === href))
  .filter((i): i is NavItem => Boolean(i))

const MORE_HREFS = ['/local', '/federated', '/lists', '/favourites', '/bookmarks', '/moderation', '/settings']
const MORE_ITEMS: NavItem[] = MORE_HREFS
  .map((href) => ITEMS.find((i) => i.href === href))
  .filter((i): i is NavItem => Boolean(i))

export function MobileTabs() {
  const sess = activeSession.value
  if (!sess) return null
  const segments = route.value.segments
  // Admin-only entries are hidden for regular accounts on both surfaces;
  // build the surface's item list from the same ITEMS array so insertions
  // cannot silently reshuffle either one.
  const moreItems = MORE_ITEMS.filter((item) => !item.admin || isAdminRole(sess))
  const moreActive = moreItems.some((item) => isActive(item.href, segments))
  return (
    <nav class="mobile-tabs" aria-label="Primary">
      {MOBILE.map((item) => {
        const active = isActive(item.href, segments)
        return (
          <a
            key={item.href}
            href={'#' + item.href}
            class="tab"
            aria-current={active ? 'page' : undefined}
            onClick={(e) => reTapToTop(e, active)}
          >
            <Icon name={item.icon} size={24} />
            <span>{item.label}</span>
          </a>
        )
      })}
      <MoreTab active={moreActive} />
    </nav>
  )
}

/** Floating compose button (mobile only): sits above the tab bar, right side. */
export function MobileFab() {
  const sess = activeSession.value
  if (!sess) return null
  return (
    <button class="mobile-fab" aria-label="New post" onClick={() => openCompose()}>
      <Icon name="pencil" size={24} />
    </button>
  )
}

function MoreTab({ active }: { active: boolean }) {
  const ref = useRef<HTMLDialogElement>(null)
  const [isOpen, setIsOpen] = useState(false)
  const segments = route.value.segments
  const profileHref = '/accounts/' + (activeSession.value?.account.id ?? '')
  const profileActive = isActive(profileHref, segments)

  function close() {
    setIsOpen(false)
    ref.current?.close()
  }

  return (
    <>
      <button
        class={`tab tab--more${active ? ' tab--active' : ''}`}
        aria-haspopup="dialog"
        aria-expanded={isOpen}
        aria-current={active ? 'page' : undefined}
        onClick={() => {
          setIsOpen(true)
          ref.current?.showModal()
        }}
      >
        <span class="nav-link__icon">
          <Icon name="more" size={24} />
        </span>
        <span>More</span>
      </button>
      <dialog class="sw-dialog more-sheet" ref={ref} aria-labelledby="more-sheet-title" onClose={() => setIsOpen(false)} onClick={(e) => { if (e.target === ref.current) close() }}>
        <div class="sw-dialog__head">
          <h2 id="more-sheet-title">More</h2>
          <button class="icon-btn" aria-label="Close" onClick={close}>
            <Icon name="x" size={20} />
          </button>
        </div>
        <div class="sw-dialog__body more-sheet__items">
          {(isAdminRole(activeSession.value) ? MORE_ITEMS : MORE_ITEMS.filter((item) => !item.admin)).map((item) => {
            const itemActive = isActive(item.href, segments)
            return (
              <a
                key={item.href}
                href={'#' + item.href}
                class="more-sheet__row"
                aria-current={itemActive ? 'page' : undefined}
                onClick={close}
              >
                <span class="nav-link__icon">
                  <Icon name={item.icon} size={20} />
                </span>
                <span>{item.label}</span>
                <Icon name="chevron-right" size={16} />
              </a>
            )
          })}
          {/* Profile hosts Wrapped and the account's own posts; on phones
              it is the way in, so it lives here rather than a tab. */}
          <a
            href={'#' + profileHref}
            class="more-sheet__row"
            aria-current={profileActive ? 'page' : undefined}
            onClick={close}
          >
            <span class="nav-link__icon">
              <Icon name="user" size={20} />
            </span>
            <span>Profile</span>
            <Icon name="chevron-right" size={16} />
          </a>
        </div>
      </dialog>
    </>
  )
}

