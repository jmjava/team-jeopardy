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

  it('keeps the host view when a redacted copy of the same revision arrives late', () => {
    const host = {
      revision: 8,
      phase: 'HOST_PREVIEW',
      activeClue: { clueId: 'c1', prompt: 'secret', response: 'What is secret?' },
      board: { categories: [{ clues: [{ prompt: 'secret' }] }] }
    }
    const redacted = {
      revision: 8,
      phase: 'HOST_PREVIEW',
      activeClue: { clueId: 'c1', prompt: null, response: null },
      board: { categories: [{ clues: [{ prompt: null }] }] }
    }
    expect(shouldApplySnapshot(host, redacted)).toBe(false)
    expect(applySnapshot(host, redacted)).toBe(host)
  })

  it('still applies a newer public snapshot over an older host view', () => {
    const host = {
      revision: 8,
      phase: 'HOST_PREVIEW',
      activeClue: { prompt: 'secret', response: 'What is secret?' }
    }
    const next = { revision: 9, phase: 'CLUE_OPEN', activeClue: { prompt: 'secret', response: null } }
    expect(shouldApplySnapshot(host, next)).toBe(true)
  })
})
