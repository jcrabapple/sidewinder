/**
 * Jump-to-top, the phone convention: tap the active tab again, or tap the
 * sticky view header. The window is the scroll container (main-col is not),
 * so one scrollTo covers every view.
 */
export function scrollToTop(): void {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' })
}

/** Taps on these inside a view header keep their own behaviour. */
const INTERACTIVE = 'a, button, input, select, textarea, label, summary, [role="button"], [role="menu"], [contenteditable]'

/**
 * Delegated handler: a tap on a `.view-header` that did not land on one of
 * its controls scrolls the view back to the top. Installed once at app start.
 */
export function installHeaderTapToTop(): void {
  document.addEventListener('click', (e) => {
    const target = e.target as Element | null
    if (!target || !target.closest('.view-header')) return
    if (target.closest(INTERACTIVE)) return
    // Selecting header text is not a tap.
    if (window.getSelection()?.toString()) return
    if (window.scrollY === 0) return
    scrollToTop()
  })
}
