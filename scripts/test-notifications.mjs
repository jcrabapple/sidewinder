import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

const storage = new Map()
globalThis.localStorage = {
  getItem: (key) => storage.get(key) ?? null,
  setItem: (key, value) => { storage.set(key, String(value)) },
  removeItem: (key) => { storage.delete(key) },
}
globalThis.location = { origin: 'https://sidewinder.test' }

const { Api, ApiError } = await import('../src/api/client.ts')
const { activeId, activeSession, upsertSession } = await import('../src/state/auth.ts')
const { getSeenTop, setSeenTop } = await import('../src/state/readState.ts')
const {
  createSignupLookup,
  filterSignupNotifications,
  invalidateSignupLookup,
  markSignupHandled,
  resolveSignupNotifications,
} = await import('../src/state/signupNotifications.ts')
const { reconcileUnreadNotifications } = await import('../src/state/notifications.ts')
const { approveSignup, fetchPendingSignups } = await import('../src/state/admin.ts')

function ulid(character) {
  return '01' + character.repeat(24)
}

function signup(notificationId, accountId) {
  return {
    id: notificationId,
    type: 'admin.sign_up',
    created_at: '2026-10-26T00:00:00.000Z',
    status: null,
    account: { id: accountId, username: accountId, acct: accountId },
  }
}

function ordinary(notificationId) {
  return {
    id: notificationId,
    type: 'follow',
    created_at: '2026-10-26T00:00:00.000Z',
    status: null,
    account: { id: 'actor', username: 'actor', acct: 'actor' },
  }
}

function installSession(id = 'https://gts.test#one') {
  const other = 'https://gts.test#two'
  upsertSession({ id, base: 'https://gts.test', token: `token-${id}`, scopes: 'admin read write', account: { id } })
  upsertSession({ id: other, base: 'https://gts.test', token: 'token-two', scopes: 'admin read write', account: { id: 'two' } })
  activeId.value = id
  return { id, other }
}

function adminAccount(id, overrides = {}) {
  return {
    id,
    username: id,
    domain: null,
    created_at: '2026-10-26T00:00:00.000Z',
    confirmed: false,
    approved: false,
    account: { id, username: id, acct: id, display_name: id, avatar: '', avatar_static: '' },
    ...overrides,
  }
}

describe('signup notification tri-state reconciliation', () => {
  test('handled rows disappear, pending rows stay, and failures stay visible with an error', async () => {
    const items = [signup(ulid('3'), 'handled'), signup(ulid('2'), 'pending'), signup(ulid('1'), 'error'), ordinary(ulid('0'))]
    const result = await resolveSignupNotifications(items, async (id) => {
      if (id === 'handled') return { status: 'handled' }
      if (id === 'error') throw new Error('Admin lookup unavailable')
      return { status: 'pending' }
    })
    assert.deepEqual(result.items.map((n) => n.account?.id), ['pending', 'error', 'actor'])
    assert.equal(result.error?.message, 'Admin lookup unavailable')
  })

  test('approved, confirmed, and 404 outcomes are cached; pending and 500 outcomes are retried', async () => {
    const replies = new Map([
      ['approved', { approved: true, confirmed: false }],
      ['confirmed', { approved: false, confirmed: true }],
      ['pending', { approved: false, confirmed: false }],
    ])
    const calls = new Map()
    const lookup = createSignupLookup({
      get: async (path) => {
        const id = decodeURIComponent(path.split('/').at(-1))
        calls.set(id, (calls.get(id) ?? 0) + 1)
        if (id === 'gone') throw new ApiError(404, 'Not found')
        if (id === 'broken') throw new ApiError(500, 'Lookup failed')
        return replies.get(id)
      },
    }, 'lookup-cache-test')

    for (const id of ['approved', 'confirmed', 'gone']) {
      assert.equal((await lookup(id)).status, 'handled')
      assert.equal((await lookup(id)).status, 'handled')
      assert.equal(calls.get(id), 1)
    }
    assert.equal((await lookup('pending')).status, 'pending')
    assert.equal((await lookup('pending')).status, 'pending')
    assert.equal(calls.get('pending'), 2)
    assert.equal((await lookup('broken')).status, 'error')
    assert.equal((await lookup('broken')).status, 'error')
    assert.equal(calls.get('broken'), 2)
  })

  test('in-flight lookups are deduplicated and explicit handled marks survive later lookups', async () => {
    let release
    let calls = 0
    const lookup = createSignupLookup({
      get: () => {
        calls += 1
        return new Promise((resolve) => { release = resolve })
      },
    }, 'in-flight-test')
    const first = lookup('deduplicated')
    const second = lookup('deduplicated')
    assert.equal(calls, 1)
    release({ approved: false, confirmed: false })
    assert.deepEqual(await Promise.all([first, second]), [{ status: 'pending' }, { status: 'pending' }])

    markSignupHandled('deduplicated', 'in-flight-test')
    assert.equal((await lookup('deduplicated')).status, 'handled')
    assert.equal(calls, 1)
    invalidateSignupLookup('deduplicated', 'in-flight-test')
    const afterInvalidate = lookup('deduplicated')
    assert.equal(calls, 2)
    release({ approved: true, confirmed: false })
    assert.equal((await afterInvalidate).status, 'handled')
  })

  test('a session switch invalidates an in-flight filtered result', async () => {
    const { other } = installSession('https://gts.test#session-change')
    let release
    const resultPromise = filterSignupNotifications(
      [signup(ulid('4'), 'session-change')],
      () => new Promise((resolve) => { release = resolve }),
    )
    activeId.value = other
    release({ status: 'handled' })
    assert.equal(await resultPromise, null)
  })
})

describe('raw notification read markers', () => {
  test('a suppressed raw newest row still establishes the fresh-device baseline', () => {
    const sessionId = 'raw-marker-baseline'
    const rawNewest = signup(ulid('9'), 'already-approved')
    const visibleOlder = ordinary(ulid('8'))
    assert.equal(reconcileUnreadNotifications(sessionId, [rawNewest, visibleOlder], [visibleOlder]), 0)
    assert.equal(getSeenTop(sessionId, 'notifications'), rawNewest.id)
    assert.equal(reconcileUnreadNotifications(sessionId, [rawNewest, visibleOlder], [visibleOlder]), 0)
  })

  test('the badge counts visible unread rows, not suppressed signups', () => {
    const sessionId = 'raw-marker-count'
    setSeenTop(sessionId, 'notifications', ulid('0'))
    const raw = [signup(ulid('2'), 'handled'), ordinary(ulid('1'))]
    assert.equal(reconcileUnreadNotifications(sessionId, raw, [raw[1]]), 1)
  })
})

describe('signup moderation states', () => {
  test('approval plus helper failure returns explicit partial success with the captured token', async (t) => {
    installSession('https://gts.test#partial-approval')
    const originalPost = Api.prototype.post
    const originalFetch = globalThis.fetch
    let authorization
    Api.prototype.post = async () => ({})
    globalThis.fetch = async (_url, init) => {
      authorization = init.headers.Authorization
      return new Response(JSON.stringify({ error: 'helper offline' }), { status: 500 })
    }
    t.after(() => {
      Api.prototype.post = originalPost
      globalThis.fetch = originalFetch
    })
    const result = await approveSignup(adminAccount('partial'))
    assert.equal(result.status, 'approved_unconfirmed')
    assert.match(result.warning, /helper offline/)
    assert.match(result.warning, /admin account confirm --username partial/)
    assert.equal(authorization, 'Bearer token-https://gts.test#partial-approval')
    assert.equal(activeSession.value?.id, 'https://gts.test#partial-approval')
  })

  test('an already-approved retry proceeds to email confirmation', async (t) => {
    installSession('https://gts.test#approved-retry')
    const originalPost = Api.prototype.post
    const originalGet = Api.prototype.get
    const originalFetch = globalThis.fetch
    let lookups = 0
    Api.prototype.post = async () => { throw new ApiError(422, 'already approved') }
    Api.prototype.get = async () => {
      lookups += 1
      return { approved: true, confirmed: false }
    }
    globalThis.fetch = async () => new Response('{}', { status: 200 })
    t.after(() => {
      Api.prototype.post = originalPost
      Api.prototype.get = originalGet
      globalThis.fetch = originalFetch
    })
    assert.deepEqual(await approveSignup(adminAccount('retry')), { status: 'confirmed' })
    assert.equal(lookups, 1)
  })

  test('an approval failure remains a failure when the account is still pending', async (t) => {
    installSession('https://gts.test#approval-failure')
    const originalPost = Api.prototype.post
    const originalGet = Api.prototype.get
    Api.prototype.post = async () => { throw new ApiError(500, 'approve failed') }
    Api.prototype.get = async () => ({ approved: false, confirmed: false })
    t.after(() => {
      Api.prototype.post = originalPost
      Api.prototype.get = originalGet
    })
    await assert.rejects(approveSignup(adminAccount('still-pending')), /approve failed/)
  })

  test('pending signups use pending/local query parameters and Link cursors before filtering', async (t) => {
    installSession('https://gts.test#signup-pages')
    const originalPage = Api.prototype.page
    const queries = []
    Api.prototype.page = async (_path, options) => {
      queries.push(options.query)
      if (queries.length === 1) {
        return { items: [adminAccount('gts.test'), adminAccount('human')], nextMaxId: 'cursor-2' }
      }
      return { items: [adminAccount('older')], nextMaxId: null }
    }
    t.after(() => { Api.prototype.page = originalPage })
    const result = await fetchPendingSignups()
    assert.deepEqual(result.map((account) => account.id), ['human', 'older'])
    assert.equal(queries.length, 2)
    assert.equal(queries[0].pending, true)
    assert.equal(queries[0].local, true)
    assert.equal(queries[0].status, undefined)
    assert.equal(queries[1].max_id, 'cursor-2')
  })
})
