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

// ---------------------------------------------------------------------------
// Reports
// ---------------------------------------------------------------------------

/** Lightweight account card used inside admin report payloads. */
export interface AdminAccountInfo {
  id: string
  username: string
  acct: string
  domain: string | null
  display_name: string
  avatar: string
  avatar_static: string
  suspended?: boolean | null
}

/** Shape of GET /api/v1/admin/reports (GTS AdminReport). */
export interface AdminReport {
  id: string
  action_taken: boolean
  action_taken_at: string | null
  action_taken_comment: string | null
  category: string
  comment: string
  forwarded: boolean
  created_at: string
  updated_at: string
  account: AdminAccountInfo
  target_account: AdminAccountInfo
  assigned_account: AdminAccountInfo | null
  action_taken_by_account: AdminAccountInfo | null
  statuses: { id: string; url?: string | null; content?: string | null }[]
  rules: { id: string; text?: string | null; description?: string | null }[]
}

/** Shape of a domain permission row (GET /api/v1/admin/domain_blocks). */
export interface AdminDomainBlock {
  id: string
  domain: string
  obfuscate: boolean
  public_comment: string | null
  private_comment: string | null
  created_at: string
}

/**
 * One page of reports. `resolved=true` returns resolved reports; omit it for
 * open ones. Paged via the Link header like every other collection.
 */
export async function fetchReports(resolved: boolean, maxId?: string) {
  return api.value.page<AdminReport>('/api/v1/admin/reports', {
    query: { resolved, limit: 40, max_id: maxId },
  })
}

/**
 * Mark a report resolved. The comment is shown to the account that filed it,
 * so keep it factual — "no action needed: the reported posts were spam,
 * removed" rather than nothing.
 */
export async function resolveReport(id: string, comment?: string): Promise<void> {
  await api.value.post(
    `/api/v1/admin/reports/${encodeURIComponent(id)}/resolve`,
    comment?.trim() ? { action_taken_comment: comment.trim() } : {},
  )
}

/**
 * Suspend an account (admin action). GTS 0.22 supports `type=suspend` only on
 * this endpoint, and there is no API to undo a suspend — that takes the
 * server CLI. The UI must say so before anyone clicks.
 */
export async function suspendAccount(id: string, text?: string): Promise<void> {
  const form = new FormData()
  form.append('type', 'suspend')
  if (text?.trim()) form.append('text', text.trim())
  await api.value.request<void>(
    `/api/v1/admin/accounts/${encodeURIComponent(id)}/action`,
    { method: 'POST', body: form },
  )
}

/**
 * Admin account search: by username substring, optionally narrowed to remote
 * or suspended accounts.
 */
export async function searchAdminAccounts(opts: {
  username?: string
  remote?: boolean
  suspended?: boolean
  maxId?: string
}) {
  return api.value.page<AdminAccount>('/api/v1/admin/accounts', {
    query: {
      username: opts.username,
      remote: opts.remote === true ? true : undefined,
      suspended: opts.suspended === true ? true : undefined,
      limit: 40,
      max_id: opts.maxId,
    },
  })
}

// ---------------------------------------------------------------------------
// Domain blocks
// ---------------------------------------------------------------------------

export async function fetchDomainBlocks(maxId?: string) {
  return api.value.page<AdminDomainBlock>('/api/v1/admin/domain_blocks', {
    query: { limit: 100, max_id: maxId },
  })
}

export async function createDomainBlock(
  domain: string,
  opts: { publicComment?: string; obfuscate?: boolean } = {},
): Promise<void> {
  const form = new FormData()
  form.append('domain', domain.trim().toLowerCase())
  if (opts.publicComment?.trim()) form.append('public_comment', opts.publicComment.trim())
  if (opts.obfuscate) form.append('obfuscate', 'true')
  await api.value.request<void>('/api/v1/admin/domain_blocks', { method: 'POST', body: form })
}

export async function deleteDomainBlock(id: string): Promise<void> {
  await api.value.request<void>(`/api/v1/admin/domain_blocks/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  })
}
