import { describe, test } from 'node:test'
import assert from 'node:assert/strict'
import { normalizeDraft, readyDraftMedia } from '../src/state/draft.ts'
import {
  advancePublication,
  ensureIdempotencyKeys,
  mergePresetText,
  nextPublicationIndex,
  withMediaIds,
} from '../src/state/composePublish.ts'

function draft(overrides = {}) {
  return {
    text: 'root',
    cw: false,
    spoiler: '',
    visibility: 'public',
    media: [],
    pollOn: false,
    pollOptions: ['', ''],
    pollMultiple: false,
    pollExpiry: 86400,
    chain: [],
    savedAt: 1,
    ...overrides,
  }
}

describe('draft media recovery', () => {
  test('never restores in-flight or legacy pending placeholders as ready attachments', () => {
    const ready = { attachment: { id: 'server-media' }, uploading: false }
    const pending = { attachment: { id: 'pending-legacy' }, uploading: false }
    const inFlight = { attachment: { id: 'server-still-uploading' }, uploading: true }

    assert.deepEqual(readyDraftMedia([ready, pending, inFlight]), [ready])
    assert.deepEqual(normalizeDraft(draft({ media: [ready, pending, inFlight] })).media, [ready])
  })

  test('restores old drafts while defaulting new chain fields', () => {
    const restored = normalizeDraft(draft({ text: 'before chains' }))
    assert.deepEqual(restored.chain, [])
    assert.deepEqual(restored.idempotencyKeys, [])
    assert.equal(restored.publishedCount, 0)
    assert.equal(restored.publishedParentId, undefined)
  })
})

describe('status media payload', () => {
  test('an edit removing its final attachment sends media_ids: []', () => {
    assert.deepEqual(withMediaIds({ status: 'edited' }, [], true), {
      status: 'edited',
      media_ids: [],
    })
  })

  test('new posts omit empty media and never submit pending ids', () => {
    assert.deepEqual(withMediaIds({ status: 'plain' }, [], false), { status: 'plain' })
    assert.deepEqual(withMediaIds({ status: 'media' }, [
      { attachment: { id: 'pending-one' }, uploading: false },
      { attachment: { id: 'ready-one' }, uploading: false },
    ], false).media_ids, ['ready-one'])
  })
})

describe('idempotent publication', () => {
  test('root and chain keys remain stable across retries', () => {
    const generated = ['root-key', 'second-key', 'third-key']
    const keys = ensureIdempotencyKeys([], 3, () => generated.shift())
    assert.deepEqual(keys, ['root-key', 'second-key', 'third-key'])
    assert.deepEqual(ensureIdempotencyKeys(keys, 3, () => 'unexpected-new-key'), keys)
    assert.deepEqual(ensureIdempotencyKeys(['root-key'], 2, () => 'second-key'), ['root-key', 'second-key'])
  })

  test('chain resume starts after the last confirmed status and retains its parent', () => {
    let progress = { publishedCount: 0 }
    assert.equal(nextPublicationIndex(progress, 3), 0)

    progress = advancePublication(progress, 'root-id')
    assert.equal(nextPublicationIndex(progress, 3), 1)
    progress = advancePublication(progress, 'second-id')

    // A later request fails. A reload and retry must skip both earlier posts.
    const restored = normalizeDraft(draft({
      chain: ['second', 'third'],
      idempotencyKeys: ['root-key', 'second-key', 'third-key'],
      publishedCount: progress.publishedCount,
      publishedParentId: progress.publishedParentId,
    }))
    assert.equal(nextPublicationIndex(restored, 3), 2)
    assert.equal(restored.publishedParentId, 'second-id')
    assert.deepEqual(restored.idempotencyKeys, ['root-key', 'second-key', 'third-key'])

    progress = advancePublication(progress, 'third-id')
    assert.equal(nextPublicationIndex(progress, 3), null)
  })
})

describe('share target recovery', () => {
  test('new shared text is preserved alongside the older draft', () => {
    assert.equal(mergePresetText('old draft', 'shared link'), 'shared link\n\nold draft')
    assert.equal(mergePresetText('', 'shared link'), 'shared link')
    assert.equal(mergePresetText('same text', 'same text'), 'same text')
    assert.equal(mergePresetText('shared link\n\nold draft', 'shared link'), 'shared link\n\nold draft')
  })
})
