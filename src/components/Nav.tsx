/**
 * Navigation: left rail on wide screens, bottom tab bar on narrow.
 * All targets ≥44px, current location marked for screen readers via
 * aria-current="page".
 */
import { route, navigate } from '../router'
import { Icon } from './Icon'
import { activeSession, sessions, switchAccount, logout } from '../state/auth'
import { openCompose } from '../state/compose'
import './Nav.css'

interface NavItem {
  href: string
  label: string
  icon: string
  /** When true, shows a dot if unseen notifications exist. */
  attention?: boolean
}

const ITEMS: NavItem[] = [
  { href: '/', label: 'Home', icon: 'home' },
  { href: '/notifications', label: 'Notifications', icon: 'bell', attention: true },
  { href: '/search', label: 'Search', icon: 'search' },
  { href: '/lists', label: 'Lists', icon: 'list' },
  { href: '/favourites', label: 'Favourites', icon: 'star' },
  { href: '/bookmarks', label: 'Bookmarks', icon: 'bookmark' },
]

function isActive(href: string, segments: string[]): boolean {
  const parts = href.split('/').filter(Boolean)
  if (parts.length === 0) return segments.length === 0
  if (parts.length !== segments.length) return false
  return parts.every((p, i) => p === segments[i])
}

function NavButton({ item }: { item: NavItem }) {
  const active = isActive(item.href, route.value.segments)
  return (
    <a
      href={'#' + item.href}
      class="nav-link"
      aria-current={active ? 'page' : undefined}
    >
      <Icon name={item.icon} size={22} />
      <span>{item.label}</span>
    </a>
  )
}

export function NavRail() {
  const sess = activeSession.value
  if (!sess) return null
  return (
    <nav class="nav-rail" aria-label="Primary">
      <a href="#/" class="nav-brand" aria-label="Sidewinder home">
        <img src="/favicon.svg" alt="" width="34" height="34" />
        <span class="nav-brand__name">Sidewinder</span>
      </a>
      <div class="nav-rail__items">
        {ITEMS.map((item) => (
          <NavButton key={item.href} item={item} />
        ))}
      </div>
      <div class="nav-rail__footer">
        <button class="btn btn--primary nav-compose" onClick={() => openCompose()}>
          <Icon name="pencil" size={18} />
          Post
        </button>
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
        <button
          class="icon-btn nav-logout"
          aria-label="Sign out"
          title="Sign out"
          onClick={() => {
            logout()
            navigate('/')
          }}
        >
          <Icon name="logout" size={20} />
        </button>
      </div>
    </nav>
  )
}

const MOBILE: NavItem[] = [
  ITEMS[0], // home
  ITEMS[1], // notifications
  ITEMS[2], // search
  ITEMS[3], // lists
]

export function MobileTabs() {
  const sess = activeSession.value
  if (!sess) return null
  return (
    <nav class="mobile-tabs" aria-label="Primary">
      {MOBILE.map((item) => {
        const active = isActive(item.href, route.value.segments)
        return (
          <a key={item.href} href={'#' + item.href} class="tab" aria-current={active ? 'page' : undefined}>
            <Icon name={item.icon} size={24} />
            <span>{item.label}</span>
          </a>
        )
      })}
      <button class="tab tab--compose" onClick={() => openCompose()}>
        <span class="tab__fab">
          <Icon name="pencil" size={22} />
        </span>
        <span>Post</span>
      </button>
    </nav>
  )
}
