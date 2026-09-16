/**
 * Relationship cache: /api/v1/accounts/relationships results keyed by account
 * id, shared so Profile, Search, and follow buttons stay consistent without
 * refetching. Cached entries update in place after follow/block/mute actions.
 */
import { signal } from '@preact/signals'
import type { Relationship } from '../api/types'
import { api } from './auth'
import { toast } from './toast'

const cache = signal<Map<string, Relationship>>(new Map())
/** Ids with an in-flight or completed fetch this session. */
const fetched = new Set<string>()

/** Wipe the cache — call on account switch/logout so Account B never sees
 *  Account A's relationship data. */
export function clearRelationships(): void {
  cache.value = new Map()
  fetched.clear()
}

export function relationshipFor(id: string): Relationship | null {
  return cache.value.get(id) ?? null
}

function merge(list: Relationship[]) {
  const next = new Map(cache.value)
  for (const r of list) next.set(r.id, r)
  cache.value = next
}

/** Fetch relationships for ids not yet fetched; cached ids are skipped. */
export async function ensureRelationships(ids: string[]): Promise<void> {
  const missing = ids.filter((id) => !fetched.has(id))
  if (missing.length === 0) return
  for (const id of missing) fetched.add(id)
  try {
    const rels = await api.value.get<Relationship[]>('/api/v1/accounts/relationships', {
      'id[]': missing,
    })
    merge(rels)
  } catch {
    // Allow a later retry.
    for (const id of missing) fetched.delete(id)
  }
}

export async function fetchRelationship(id: string): Promise<Relationship | null> {
  await ensureRelationships([id])
  return relationshipFor(id)
}

/** Run a relationship mutation, cache the result, and toast on failure. */
async function act(id: string, verb: string, errorMsg: string): Promise<Relationship | null> {
  try {
    const rel = await api.value.post<Relationship>(`/api/v1/accounts/${encodeURIComponent(id)}/${verb}`)
    merge([rel])
    return rel
  } catch (e) {
    toast(e instanceof Error ? e.message : errorMsg, 'error')
    return null
  }
}

export const followAccount = (id: string) => act(id, 'follow', 'Could not follow that account.')
export const unfollowAccount = (id: string) => act(id, 'unfollow', 'Could not unfollow that account.')
export const blockAccount = (id: string) => act(id, 'block', 'Could not block that account.')
export const unblockAccount = (id: string) => act(id, 'unblock', 'Could not unblock that account.')
export const muteAccount = (id: string) => act(id, 'mute', 'Could not mute that account.')
export const unmuteAccount = (id: string) => act(id, 'unmute', 'Could not unmute that account.')
