import { toasts } from '../state/toast'
import { Icon } from './Icon'

export function ToastHost() {
  if (toasts.value.length === 0) return null
  return (
    <div class="toast-region">
      {toasts.value.map((t) =>
        t.kind === 'error' ? (
          <div key={t.id} class="toast toast--error" role="alert">
            <Icon name="alert" size={16} />
            {t.message}
          </div>
        ) : (
          <div key={t.id} class="toast" role="status">
            {t.message}
          </div>
        ),
      )}
    </div>
  )
}
