/**
 * Keyboard-safe bottom sheets.
 *
 * On mobile, fixed `bottom: 0` elements anchor to the layout viewport, which
 * does not shrink when the on-screen keyboard opens — the keyboard covers the
 * sheet. The visual viewport DOES shrink, so the overlap between the two is
 * exactly the keyboard height. Lift any open sheet by that overlap while the
 * keyboard is up, and cap its height to the visible area.
 *
 * Install once at app mount.
 */
export function installKeyboardSheetFix(): void {
  const vv = window.visualViewport
  if (!vv) return

  function update() {
    // Ignore trivial changes (URL-bar show/hide); a keyboard is >150px.
    const overlap = Math.max(0, window.innerHeight - (vv!.height + vv!.offsetTop))
    const keyboardOpen = overlap > 150
    document.querySelectorAll<HTMLDialogElement>('dialog[open].sw-dialog').forEach((dlg) => {
      if (keyboardOpen) {
        dlg.style.bottom = `${overlap}px`
        dlg.style.maxHeight = `${Math.max(200, vv!.height - 16)}px`
      } else {
        dlg.style.bottom = ''
        dlg.style.maxHeight = ''
      }
    })
  }

  vv.addEventListener('resize', update)
  vv.addEventListener('scroll', update)
  window.addEventListener('resize', update)
}
