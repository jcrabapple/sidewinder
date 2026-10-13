/**
 * Moderation helpers for admins: approve or deny pending account signups.
 *
 * GoToSocial 0.22 has no API to confirm a new account's email address, so a
 * freshly approved signup still cannot log in until its email is confirmed.
 * This instance runs a tiny localhost helper (gts-confirm-helper, exposed by
 * caddy at /gts-admin-helper/*) that runs the CLI confirm; approveSignup
 * calls it as the second step. Without the helper, approve falls back to an
 * error carrying the exact CLI command.
 */
import { api, activeSession } from './auth'

export interface AdminAccount {
  id: string
  username: string
  domain: string | null
  email?: string
  reason?: string | null
  invite_request?: string | null
  created_at: string
  confirmed: boolean
  approved: boolean
  account: {
    id: string
    username: string
    acct: string
    display_name: string
    avatar: string
    avatar_static: string
  }
}

export function hasAdminScope(): boolean {
  return (activeSession.value?.scopes ?? '').split(/\s+/).includes('admin')
}

const HELPER_URL = location.origin + '/gts-admin-helper/confirm'

/**
 * Accounts waiting on approval. The GTS `status=pending` filter returns some
 * internal rows (confirmed:false/approved:false), so filter to real humans:
 * local, unconfirmed, unapproved, and not the instance actor.
 */
export async function fetchPendingSignups(): Promise<AdminAccount[]> {
  const list = await api.value.get<AdminAccount[]>('/api/v1/admin/accounts', {
    status: 'pending',
  })
  const host = new URL(api.value.base).hostname
  return (list ?? []).filter(
    (a) => a.domain === null && !a.approved && !a.confirmed && a.username !== host,
  )
}

export function signupReason(a: AdminAccount): string {
  return (a.invite_request ?? a.reason ?? '').trim()
}

/**
 * Approve a pending signup AND confirm its email (the step GTS has no API
 * for). Throws with a helpful message if the second step fails, since an
 * approved-but-unconfirmed account still cannot log in.
 */
export async function approveSignup(a: AdminAccount): Promise<void> {
  await api.value.post(`/api/v1/admin/accounts/${encodeURIComponent(a.id)}/approve`)

  const res = await fetch(HELPER_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${activeSession.value?.token ?? ''}`,
    },
    body: JSON.stringify({ username: a.username, account_id: a.id }),
  })
  if (res.ok) return

  let detail = `helper returned ${res.status}`
  try {
    const body = (await res.json()) as { error?: string }
    if (body?.error) detail = body.error
  } catch {
    // keep the status fallback
  }
  if (res.status === 404) {
    detail = 'helper not reachable'
  }
  throw new Error(
    `Approved, but the email confirmation step failed (${detail}). ` +
      `They cannot log in until it runs: podman exec gotosocial /gotosocial/gotosocial ` +
      `admin account confirm --username ${a.username}`,
  )
}

/** Deny a pending signup. Rejecting removes the pending account. */
export async function denySignup(id: string): Promise<void> {
  await api.value.post(`/api/v1/admin/accounts/${encodeURIComponent(id)}/reject`)
}
