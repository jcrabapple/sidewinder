/** Toast notifications: transient feedback, announced via aria-live. */
import { signal } from '@preact/signals'

export interface Toast {
  id: number
  message: string
  kind: 'info' | 'error'
}

let nextId = 1

export const toasts = signal<Toast[]>([])

export function toast(message: string, kind: Toast['kind'] = 'info') {
  const id = nextId++
  toasts.value = [...toasts.value, { id, message, kind }]
  setTimeout(() => {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }, kind === 'error' ? 7000 : 3500)
}
