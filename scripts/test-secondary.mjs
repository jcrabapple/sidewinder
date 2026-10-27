import { describe, test } from 'node:test'
import assert from 'node:assert/strict'

const storage = new Map()
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: {
    getItem(key) {
      return storage.get(key) ?? null
    },
    setItem(key, value) {
      storage.set(key, String(value))
    },
    removeItem(key) {
      storage.delete(key)
    },
    clear() {
      storage.clear()
    },
  },
})

const { MAX_JOBS_PER_SESSION, trimList } = await import('../src/state/importer.ts')
const DAY = 24 * 60 * 60_000
const NOW = 2_000_000_000_000

function job(id, startedAt, phase = 'ended') {
  return {
    id,
    sessionId: 'session',
    type: 'following',
    mode: 'merge',
    fileName: `${id}.csv`,
    rows: 1,
    startedAt,
    baseline: 0,
    current: 1,
    lastChangeAt: startedAt,
    phase,
    endedReason: phase === 'ended' ? 'stopped' : undefined,
  }
}

describe('import job retention', () => {
  test('newer ended jobs cannot evict older watching or quiet jobs', () => {
    const running = [
      job('watching', NOW - 10_000, 'watching'),
      job('quiet', NOW - 9_000, 'quiet'),
    ]
    const ended = Array.from({ length: MAX_JOBS_PER_SESSION + 1 }, (_, index) =>
      job(`ended-${index}`, NOW - index * 1_000),
    )

    const result = trimList([...ended, ...running], NOW)

    assert.deepEqual(result.map((item) => item.id), [
      'quiet',
      'watching',
      'ended-0',
      'ended-1',
      'ended-2',
    ])
    assert.equal(result.length, MAX_JOBS_PER_SESSION)
  })

  test('all running jobs survive even when they exceed the ended-job capacity', () => {
    const running = Array.from({ length: MAX_JOBS_PER_SESSION + 2 }, (_, index) =>
      job(`running-${index}`, NOW - 10_000 - index * 1_000, index % 2 === 0 ? 'watching' : 'quiet'),
    )
    const ended = [job('newest-ended', NOW), job('next-ended', NOW - 1_000)]

    const result = trimList([...ended, ...running], NOW)

    assert.equal(result.length, running.length)
    assert.deepEqual(
      result.map((item) => item.id),
      running.map((item) => item.id),
    )
    assert.ok(result.every((item) => item.phase !== 'ended'))
  })

  test('expired ended jobs drop out without dropping old active work', () => {
    const result = trimList(
      [
        job('expired-ended', NOW - DAY),
        job('ancient-running', NOW - DAY * 2, 'watching'),
        job('fresh-ended', NOW - 1_000),
      ],
      NOW,
    )

    assert.deepEqual(result.map((item) => item.id), ['ancient-running', 'fresh-ended'])
  })
})
