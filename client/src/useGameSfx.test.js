import { describe, expect, it } from 'vitest'
import { sfxEventForTransition } from './useGameSfx.js'

describe('sfxEventForTransition', () => {
  it('maps a full round to original game-show cues', () => {
    expect(sfxEventForTransition('BOARD', 'HOST_PREVIEW', { dailyDouble: false })).toBe('select')
    expect(sfxEventForTransition('BOARD', 'HOST_PREVIEW', { dailyDouble: true })).toBe('dailyDouble')
    expect(sfxEventForTransition('HOST_PREVIEW', 'CLUE_OPEN')).toBe('open')
    expect(sfxEventForTransition('CLUE_OPEN', 'BUZZ_LOCKED')).toBe('buzz')
    expect(sfxEventForTransition('BUZZ_LOCKED', 'CLUE_OPEN')).toBe('incorrect')
    expect(sfxEventForTransition('BUZZ_LOCKED', 'ANSWER_REVEALED')).toBe('correct')
    expect(sfxEventForTransition('CLUE_OPEN', 'ANSWER_REVEALED')).toBe('reveal')
    expect(sfxEventForTransition('LOBBY', 'BOARD')).toBe('board')
    expect(sfxEventForTransition('ANSWER_REVEALED', 'BOARD')).toBe('board')
    expect(sfxEventForTransition('ANSWER_REVEALED', 'FINISHED')).toBe('finished')
  })

  it('ignores unchanged phases', () => {
    expect(sfxEventForTransition('BOARD', 'BOARD')).toBeNull()
  })
})
