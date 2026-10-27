import { describe, test } from 'node:test'
import assert from 'node:assert/strict'
import { newestWrapperId, statusRowMatches } from '../src/util/statusRows.ts'

describe('newestWrapperId', () => {
  test('uses the newest boost wrapper rather than its original', () => {
    const original = { id: '01ORIGINAL', reblog: null }
    const boost = { id: '01WRAPPER', reblog: original }
    assert.equal(newestWrapperId([original, boost]), '01WRAPPER')
  })

  test('does not depend on chronological array order', () => {
    const rows = [
      { id: '03', reblog: null },
      { id: '01', reblog: null },
      { id: '02', reblog: { id: '99-original-must-not-win' } },
    ]
    assert.equal(newestWrapperId(rows), '03')
  })

  test('returns an empty boundary for an empty timeline', () => {
    assert.equal(newestWrapperId([]), '')
  })
})

describe('statusRowMatches', () => {
  const direct = { id: 'direct', reblog: null }
  const wrapper = { id: 'wrapper', reblog: { id: 'original' } }

  test('matches direct rows by their own id', () => {
    assert.equal(statusRowMatches(direct, 'direct'), true)
    assert.equal(statusRowMatches(direct, 'other'), false)
  })

  test('matches boost rows by either wrapper or original id', () => {
    assert.equal(statusRowMatches(wrapper, 'wrapper'), true)
    assert.equal(statusRowMatches(wrapper, 'original'), true)
    assert.equal(statusRowMatches(wrapper, 'other'), false)
  })
})
