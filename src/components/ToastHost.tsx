import { toasts } from '../state/toast'
import { Icon } from './Icon'

export function ToastHost() {
  if (toasts.value.length === 0) return null
  return (
    <div class="toast-region" role="status" aria-live="polite">
      {toasts.value.map((t) => (
        <div key={t.id} class={t.kind === 'error' ? 'toast toast--error' : 'toast'}>
          {t.kind === 'error' && <Icon name="alert" size={16} />}
          {t.message}
        </div>
      ))}
    </div>
  )
}
