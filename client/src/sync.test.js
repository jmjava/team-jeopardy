import { describe, expect, it } from 'vitest'
import { applySnapshot, shouldApplySnapshot } from './sync'

describe('snapshot revision gate', () => {
  it('applies the first snapshot when local state is empty', () => {
    expect(shouldApplySnapshot(null, { revision: 1, phase: 'BOARD' })).toBe(true)
  })

  it('drops an older revision so a late packet cannot rewind the table', () => {
    const current = { revision: 12, phase: 'BUZZ_LOCKED' }
    const stale = { revision: 11, phase: 'CLUE_OPEN' }
    expect(shouldApplySnapshot(current, stale)).toBe(false)
    expect(applySnapshot(current, stale)).toBe(current)
  })

  it('applies equal or newer revisions so SYNC can refresh the same tick', () => {
    const current = { revision: 12, phase: 'BUZZ_LOCKED' }
    expect(shouldApplySnapshot(current, { revision: 12, phase: 'BUZZ_LOCKED' })).toBe(true)
    expect(shouldApplySnapshot(current, { revision: 13, phase: 'ANSWER_REVEALED' })).toBe(true)
  })

  it('ignores empty payloads', () => {
    expect(shouldApplySnapshot({ revision: 1 }, null)).toBe(false)
  })
})
