import { dismissToast, toasts } from '../state/toast'
import { Icon } from './Icon'

export function ToastHost() {
  if (toasts.value.length === 0) return null
  return (
    <div class="toast-region">
      {toasts.value.map((t) => (
        <div key={t.id} class={`toast${t.kind === 'error' ? ' toast--error' : ''}`} role={t.kind === 'error' ? 'alert' : 'status'}>
          {t.kind === 'error' && <Icon name="alert" size={16} />}
          <span>{t.message}</span>
          {t.action && (
            <button
              type="button"
              class="btn"
              onClick={() => {
                dismissToast(t.id)
                t.action?.onClick()
              }}
            >
              {t.action.label}
            </button>
          )}
        </div>
      ))}
    </div>
  )
}
