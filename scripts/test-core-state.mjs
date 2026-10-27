import { after, describe, test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'

class MemoryStorage {
  values = new Map()

  getItem(key) {
    return this.values.has(key) ? this.values.get(key) : null
  }

  setItem(key, value) {
    this.values.set(key, String(value))
  }

  removeItem(key) {
    this.values.delete(key)
  }
}

// State modules read browser globals at import time. These tests exercise
// their pure storage/selection helpers, not DOM rendering or network calls.
globalThis.localStorage = new MemoryStorage()
globalThis.document = { documentElement: { dataset: {} } }
globalThis.window = {
  addEventListener() {},
  matchMedia() {
    return { matches: false, addEventListener() {} }
  },
}

// Vite resolves the app's extensionless TypeScript imports; the tests below
// still call pure helpers with in-memory inputs and no browser or API I/O.
const vite = await createServer({ server: { middlewareMode: true }, appType: 'custom', logLevel: 'silent' })
after(() => vite.close())

const { loadMutesFor } = await vite.ssrLoadModule('/src/state/mutes.ts')
const { loadPushAlerts, shouldUnsubscribeBrowser } = await vite.ssrLoadModule('/src/state/push.ts')
const { scopeSeenPost, seenRecordKey } = await vite.ssrLoadModule('/src/state/seenPosts.ts')
const { pendingMarkerKey, selectMarkerFlushes } = await vite.ssrLoadModule('/src/state/markers.ts')

describe('account-scoped legacy migration', () => {
  test('keyword mutes move to the active account only and legacy data is removed', () => {
    const storage = new MemoryStorage()
    const legacy = [{ id: 'one', phrase: 'quiet please' }]
    storage.setItem('sidewinder.mutes', JSON.stringify(legacy))

    assert.deepEqual(loadMutesFor('account-a', storage), legacy)
    assert.deepEqual(JSON.parse(storage.getItem('sidewinder.mutes.account-a')), legacy)
    assert.equal(storage.getItem('sidewinder.mutes'), null)
    assert.deepEqual(loadMutesFor('account-b', storage), [])
    assert.deepEqual(loadMutesFor(null, storage), [])
  })

  test('an existing scoped mute list wins over leftover legacy data', () => {
    const storage = new MemoryStorage()
    storage.setItem('sidewinder.mutes.account-a', JSON.stringify([{ id: 'new', phrase: 'scoped' }]))
    storage.setItem('sidewinder.mutes', JSON.stringify([{ id: 'old', phrase: 'legacy' }]))

    assert.deepEqual(loadMutesFor('account-a', storage), [{ id: 'new', phrase: 'scoped' }])
    assert.equal(storage.getItem('sidewinder.mutes'), null)
  })

  test('admin push preferences cannot cross into another account', () => {
    const storage = new MemoryStorage()
    storage.setItem('sidewinder.push.alerts', JSON.stringify({ admin_sign_up: true, admin_report: true, mention: false }))

    const first = loadPushAlerts('account-a', storage)
    assert.equal(first.admin_sign_up, true)
    assert.equal(first.admin_report, true)
    assert.equal(first.mention, false)
    assert.equal(storage.getItem('sidewinder.push.alerts'), null)

    const second = loadPushAlerts('account-b', storage)
    assert.equal(second.admin_sign_up, false)
    assert.equal(second.admin_report, false)
    assert.equal(second.mention, true)
  })
})

describe('shared browser push subscription', () => {
  test('only the final enabled account permits browser unsubscription', () => {
    assert.equal(shouldUnsubscribeBrowser(['account-a'], 'account-a'), true)
    assert.equal(shouldUnsubscribeBrowser(['account-a', 'account-b'], 'account-a'), false)
    assert.equal(shouldUnsubscribeBrowser(['account-b'], 'account-a'), false)
  })
})

describe('seen-post ownership', () => {
  const post = {
    id: '01J00000000000000000000000',
    acct: '@alice',
    name: 'Alice',
    avatar: '',
    text: 'private account context',
    ts: '2026-10-26T00:00:00Z',
    favs: 0,
    boosts: 0,
  }

  test('the same post has distinct storage identity and ownership per account', () => {
    const a = scopeSeenPost(post, 'account-a')
    const b = scopeSeenPost(post, 'account-b')

    assert.equal(a.session, 'account-a')
    assert.equal(b.session, 'account-b')
    assert.notEqual(a.key, b.key)
    assert.equal(a.key, seenRecordKey('account-a', post.id))
  })
})

describe('pending marker selection', () => {
  test('pagehide payloads keep the highest id and captured token per account', () => {
    const values = [
      { session: 'account-a', base: 'https://a.example', token: 'token-a', timeline: 'home', id: '01' },
      { session: 'account-a', base: 'https://a.example', token: 'token-a', timeline: 'home', id: '03' },
      { session: 'account-a', base: 'https://a.example', token: 'token-a', timeline: 'home', id: '02' },
      { session: 'account-a', base: 'https://a.example', token: 'token-a', timeline: 'notifications', id: '04' },
      { session: 'account-b', base: 'https://b.example', token: 'token-b', timeline: 'home', id: '09' },
    ]

    const flushes = selectMarkerFlushes(values)
    assert.equal(flushes.length, 2)
    const a = flushes.find((flush) => flush.token === 'token-a')
    const b = flushes.find((flush) => flush.token === 'token-b')
    assert.ok(a)
    assert.ok(b)
    assert.deepEqual(a.body, { home: { last_read_id: '03' }, notifications: { last_read_id: '04' } })
    assert.deepEqual(b.body, { home: { last_read_id: '09' } })
    assert.equal(a.base, 'https://a.example')
    assert.equal(b.base, 'https://b.example')
  })

  test('pending keys isolate both session and timeline', () => {
    assert.notEqual(pendingMarkerKey('account-a', 'home'), pendingMarkerKey('account-b', 'home'))
    assert.notEqual(pendingMarkerKey('account-a', 'home'), pendingMarkerKey('account-a', 'notifications'))
  })
})
