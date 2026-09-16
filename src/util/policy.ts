/**
 * Interaction-policy evaluation (GoToSocial extension, no other client
 * renders this). A status carries interaction_policy with can_reply /
 * can_reblog / can_favourite rules; each rule lists entries in
 * automatic_approval (interaction goes through) and manual_approval
 * (interaction becomes a pending request for the author).
 *
 * Client-side evaluation is necessarily approximate: entries like
 * `followers` depend on server-side state we don't have. So the result is
 * three-valued — 'allowed' when the policy plainly covers us, 'approval'
 * when it plainly requires a request, and 'unknown' when it hinges on
 * keywords we can't evaluate (server decides; buttons stay enabled).
 */
import type { InteractionPolicy, PolicyRules, Status } from '../api/types'

export type PolicyVerdict = 'allowed' | 'approval' | 'unknown' | 'denied'

const AUTO_COVERS_ME = ['me', 'public']
const MANUAL_COVERS_ME = ['me', 'public']
const UNDECIDABLE = ['followers', 'following', 'mutuals', 'mentioned', 'author']

function evaluate(rules: PolicyRules | undefined, opts: { own: boolean; mentionedMe: boolean }): PolicyVerdict {
  if (!rules) return 'allowed'
  if (opts.own) return 'allowed'
  if (rules.automatic_approval.some((v) => AUTO_COVERS_ME.includes(v))) return 'allowed'
  if (opts.mentionedMe && rules.automatic_approval.includes('mentioned')) return 'allowed'
  if (rules.manual_approval.some((v) => MANUAL_COVERS_ME.includes(v))) return 'approval'
  if (rules.automatic_approval.some((v) => UNDECIDABLE.includes(v))) return 'unknown'
  if (rules.manual_approval.some((v) => UNDECIDABLE.includes(v))) return 'unknown'
  // Neither list covers us by keyword: e.g. automatic is just [author].
  return 'denied'
}

export function policyVerdicts(
  status: Status,
  own: boolean,
  mentionedMe: boolean,
): { reply: PolicyVerdict; boost: PolicyVerdict; fav: PolicyVerdict } {
  const p: InteractionPolicy | null | undefined = status.interaction_policy
  return {
    reply: evaluate(p?.can_reply, { own, mentionedMe }),
    boost: evaluate(p?.can_reblog, { own, mentionedMe }),
    fav: evaluate(p?.can_favourite, { own, mentionedMe }),
  }
}

/** True when the policy restricts something relative to the per-visibility
 *  default (public auto-approval for everything). Used to decide whether to
 *  show a chip on the card. */
export function policyIsRestrictive(status: Status): boolean {
  const p = status.interaction_policy
  if (!p) return false
  const rules = [p.can_reply, p.can_reblog, p.can_favourite]
  return rules.some(
    (r) => r && (!r.automatic_approval.includes('public') || r.manual_approval.length > 0),
  )
}

const LABELS: Record<string, string> = {
  public: 'anyone',
  followers: 'followers',
  following: 'people I follow',
  mutuals: 'mutuals',
  mentioned: 'mentioned',
  author: 'only the author',
  me: 'you',
  author_uri_fallback: '',
}

/** Human summary of one rule list, e.g. "followers + mentioned". */
export function describeRules(rules: PolicyRules | undefined): string {
  if (!rules) return 'anyone'
  const parts = [...rules.automatic_approval, ...rules.manual_approval.map((v) => `${v} (approval)`)]
  if (parts.length === 0) return 'anyone'
  return parts.map((v) => LABELS[v] ?? '').filter(Boolean).join(' + ') || 'restricted'
}

/** Compact per-type summaries for the card chip. Only restricted types. */
export function policySummaries(status: Status): string[] {
  const p = status.interaction_policy
  if (!p) return []
  const out: string[] = []
  if (p.can_reply && (!p.can_reply.automatic_approval.includes('public') || p.can_reply.manual_approval.length > 0)) {
    out.push(`Replies: ${describeRules(p.can_reply)}`)
  }
  if (p.can_reblog && (!p.can_reblog.automatic_approval.includes('public') || p.can_reblog.manual_approval.length > 0)) {
    out.push(`Boosts: ${describeRules(p.can_reblog)}`)
  }
  if (p.can_favourite && (!p.can_favourite.automatic_approval.includes('public') || p.can_favourite.manual_approval.length > 0)) {
    out.push(`Likes: ${describeRules(p.can_favourite)}`)
  }
  return out
}
