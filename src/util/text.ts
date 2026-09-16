/** Strip HTML down to plain text using an inert template element. */
export function htmlToText(html: string): string {
  const el = document.createElement('template')
  el.innerHTML = html
  return (el.content.textContent ?? '').replace(/\s+/g, ' ').trim()
}
