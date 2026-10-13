/**
 * Sanitize server-provided status HTML before it touches the DOM.
 * Server HTML is content from strangers; treat it as hostile input.
 */
import DOMPurify from 'dompurify'

const ALLOWED_TAGS = [
  'p', 'br', 'a', 'span', 'b', 'strong', 'i', 'em', 'u', 's', 'del',
  'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'hr', 'small', 'sub', 'sup',
]

const ALLOWED_ATTR = ['href', 'class', 'title', 'lang', 'dir']

export function sanitize(html: string, baseUrl: string): string {
  const clean = DOMPurify.sanitize(html, {
    ALLOWED_TAGS,
    ALLOWED_ATTR,
    ALLOW_DATA_ATTR: false,
    FORBID_ATTR: ['style'],
  })
  // Wrap in a detached element to normalize link targets.
  const tpl = document.createElement('template')
  tpl.innerHTML = clean
  for (const a of tpl.content.querySelectorAll('a[href]')) {
    try {
      const url = new URL(a.getAttribute('href')!, baseUrl)
      if (url.protocol !== 'http:' && url.protocol !== 'https:' && url.protocol !== 'mailto:') {
        a.removeAttribute('href')
      } else {
        a.setAttribute('href', url.toString())
      }
    } catch {
      a.removeAttribute('href')
    }
    a.setAttribute('rel', 'nofollow noopener noreferrer')
    // External links open in a new tab; mention/hashtag links stay internal
    // (the click handler in StatusContent routes them in-app).
    if (!a.classList.contains('mention') && !a.classList.contains('hashtag')) {
      a.setAttribute('target', '_blank')
    }
  }
  return tpl.innerHTML
}

/** Does this sanitized HTML contain any visible content? */
export function htmlIsEmpty(html: string): boolean {
  const tpl = document.createElement('template')
  tpl.innerHTML = html
  return tpl.content.textContent?.trim() === '' && !tpl.content.querySelector('img, br, hr, ul, ol, pre, blockquote')
}
