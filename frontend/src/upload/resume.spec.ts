import { describe, expect, it } from 'vitest'
import { missingChunkIndexes } from './resume'

describe('missingChunkIndexes', () => {
  it.each([30, 70, 99])('only schedules the missing suffix after a %i%% interruption', (completed) => {
    const uploaded = Array.from({ length: completed }, (_, index) => index)
    const missing = missingChunkIndexes(100, uploaded)
    expect(missing).toHaveLength(100 - completed)
    expect(missing[0]).toBe(completed)
    expect(missing.at(-1)).toBe(99)
  })

  it('does not count duplicate chunk indexes twice', () => {
    expect(missingChunkIndexes(3, [0, 0, 2])).toEqual([1])
  })
})
