/**
 * Unit tests for src/util/importWatch.ts, run by `pnpm test:import` together with the CSV tests:
 *   node --experimental-strip-types --test scripts/test-import-csv.mjs scripts/test-import-watch.mjs
 *
 * The centrepiece is a replay of a real import: 300 blocks sent to a live GoToSocial on 2026-10-15, where the
 * first version of the watcher stopped at 15 minutes while accounts were still being created.
 */
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import {
  POLL_FAST_MS,
  POLL_SLOW_MS,
  POLL_LONG_MS,
  FAST_WINDOW_MS,
  SLOW_WINDOW_MS,
  IDLE_LIMIT_MS,
  WATCH_CAP_MS,
  QUIET_AFTER_MS,
  pollDelay,
  quietAfter,
  watchEnd,
  resumeAction,
  canRewatch,
} from '../src/util/importWatch.ts'

const MIN = 60_000

describe('pollDelay', () => {
  test('fast, then slow, then long, with the boundaries on the later tier', () => {
    assert.equal(pollDelay(0), POLL_FAST_MS)
    assert.equal(pollDelay(FAST_WINDOW_MS - 1), POLL_FAST_MS)
    assert.equal(pollDelay(FAST_WINDOW_MS), POLL_SLOW_MS)
    assert.equal(pollDelay(SLOW_WINDOW_MS - 1), POLL_SLOW_MS)
    assert.equal(pollDelay(SLOW_WINDOW_MS), POLL_LONG_MS)
    assert.equal(pollDelay(6 * 60 * MIN), POLL_LONG_MS)
  })

  test('a six-hour import costs about two polls a minute after the first quarter hour', () => {
    let t = 0
    let polls = 0
    while (t < 6 * 60 * MIN) {
      t += pollDelay(t)
      polls++
    }
    // 40 fast + 78 slow + 2/min for the remaining 345 minutes = 808; polling every 10 s throughout would be 2,160.
    assert.ok(polls >= 800 && polls <= 815, `${polls} polls`)
  })
})

describe('quietAfter', () => {
  test('30 s while polling every 3 or 10 s, three polls (90 s) at the long cadence', () => {
    assert.equal(quietAfter(0), QUIET_AFTER_MS)
    assert.equal(quietAfter(10 * MIN), QUIET_AFTER_MS)
    assert.equal(quietAfter(SLOW_WINDOW_MS), 3 * POLL_LONG_MS)
  })

  test('a job polled every 30 s is not quiet after one unchanged poll', () => {
    assert.ok(quietAfter(30 * MIN) > POLL_LONG_MS)
    assert.ok(quietAfter(30 * MIN) >= 3 * POLL_LONG_MS)
  })
})

describe('watchEnd', () => {
  test('a job that is moving keeps going, however old (until the cap)', () => {
    assert.equal(watchEnd(16 * MIN, 0), null)
    assert.equal(watchEnd(5 * 60 * MIN, 0), null)
    assert.equal(watchEnd(WATCH_CAP_MS - 1, 0), null)
  })

  test('stalls up to just under an hour do not end it, an hour of nothing does', () => {
    assert.equal(watchEnd(40 * MIN, 6 * MIN), null)
    assert.equal(watchEnd(90 * MIN, IDLE_LIMIT_MS - 1), null)
    assert.equal(watchEnd(90 * MIN, IDLE_LIMIT_MS), 'idle')
  })

  test('the cap wins over everything', () => {
    assert.equal(watchEnd(WATCH_CAP_MS, 0), 'timeout')
    assert.equal(watchEnd(WATCH_CAP_MS + 1, IDLE_LIMIT_MS), 'timeout')
  })

  test('the idle limit is longer than every stall measured on the live server', () => {
    const longestStallSeen = 6.2 * MIN
    assert.ok(IDLE_LIMIT_MS > 5 * longestStallSeen)
    assert.ok(QUIET_AFTER_MS < POLL_SLOW_MS * 4)
  })
})

describe('replay of the real 300-account blocks import (2026-10-15)', () => {
  // Accounts created per minute, minute 0 = 09:21 (from the server's blocks table).
  const created = { 0: 28, 2: 1, 6: 16, 7: 31, 8: 20, 9: 29, 10: 4, 15: 18, 19: 8 }
  const countAt = (minute) => 3 + Object.entries(created).reduce((n, [m, c]) => (Number(m) <= minute ? n + c : n), 0)

  /** Drive the watcher like importer.ts does and report when (if ever) it ends. */
  function run(untilMinutes) {
    let t = 0
    let current = countAt(0)
    let lastChangeAt = 0
    const sawLive = []
    while (t <= untilMinutes * MIN) {
      t += pollDelay(t)
      const count = countAt(t / MIN)
      if (count !== current) {
        current = count
        lastChangeAt = t
      }
      const end = watchEnd(t, t - lastChangeAt)
      if (end) return { end, atMinute: t / MIN, current, lastChangeAt: lastChangeAt / MIN, sawLive }
      sawLive.push(t / MIN)
    }
    return { end: null, atMinute: untilMinutes, current, lastChangeAt: lastChangeAt / MIN, sawLive }
  }

  test('the rule it replaces would have stopped at minute 15, while accounts were still arriving at minute 19', () => {
    const oldRuleStopsAt = 15
    assert.ok(Object.keys(created).some((m) => Number(m) > oldRuleStopsAt))
  })

  test('the watcher is still going at minute 30 and sees the final count', () => {
    const r = run(30)
    assert.equal(r.end, null)
    assert.equal(r.current, countAt(30))
    assert.equal(r.lastChangeAt, 19)
  })

  test('it ends as idle an hour after the last account, and not before', () => {
    const r = run(200)
    assert.equal(r.end, 'idle')
    assert.ok(r.atMinute >= 19 + 60 && r.atMinute < 19 + 60 + 1, `ended at minute ${r.atMinute}`)
  })
})

describe('resumeAction', () => {
  const now = 1_000 * MIN
  const job = (over) => ({ phase: 'watching', startedAt: now - 30 * MIN, ...over })

  test('a running job is polled again, even one the page was closed on for hours', () => {
    assert.equal(resumeAction(job({}), now), 'poll')
    assert.equal(resumeAction(job({ phase: 'quiet', startedAt: now - 5 * 60 * MIN }), now), 'poll')
  })

  test('a running job past the cap expires without a poll', () => {
    assert.equal(resumeAction(job({ startedAt: now - WATCH_CAP_MS }), now), 'expire')
  })

  test('a legacy 15-minute cut-off (stored as timeout, younger than the cap) is revived', () => {
    assert.equal(resumeAction(job({ phase: 'ended', endedReason: 'timeout' }), now), 'revive')
  })

  test('every other ended job is left alone', () => {
    for (const endedReason of ['stopped', 'idle', 'error', 'session', 'superseded']) {
      assert.equal(resumeAction(job({ phase: 'ended', endedReason }), now), 'leave', endedReason)
    }
  })

  test('a timeout at or past the cap is a real timeout and stays ended', () => {
    assert.equal(resumeAction(job({ phase: 'ended', endedReason: 'timeout', startedAt: now - WATCH_CAP_MS }), now), 'leave')
  })
})

describe('canRewatch', () => {
  test('stopped, idle, error and the legacy timeout can be watched again', () => {
    for (const reason of ['stopped', 'idle', 'error', 'timeout']) assert.equal(canRewatch(reason, 10 * MIN), true, reason)
  })

  test('superseded and signed-out jobs cannot, nor can anything past the cap or without a reason', () => {
    assert.equal(canRewatch('superseded', MIN), false)
    assert.equal(canRewatch('session', MIN), false)
    assert.equal(canRewatch(undefined, MIN), false)
    assert.equal(canRewatch('idle', WATCH_CAP_MS), false)
  })
})
