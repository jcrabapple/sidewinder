/** Toast notifications: transient feedback, announced via aria-live. */
import { signal } from '@preact/signals'

export interface ToastAction {
  label: string
  onClick: () => void
}

export interface Toast {
  id: number
  message: string
  kind: 'info' | 'error'
  action?: ToastAction
}

let nextId = 1

export const toasts = signal<Toast[]>([])

export function dismissToast(id: number): void {
  toasts.value = toasts.value.filter((t) => t.id !== id)
}

export function toast(message: string, kind: Toast['kind'] = 'info', action?: ToastAction) {
  const id = nextId++
  toasts.value = [...toasts.value, { id, message, kind, action }]
  // An actionable notice must remain until its control is used. Merely
  // telling someone to reload and then removing the instruction is not enough.
  if (action) return
  setTimeout(() => dismissToast(id), kind === 'error' ? 7000 : 3500)
}
