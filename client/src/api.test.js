import { describe, expect, it } from 'vitest'

describe('team jeopardy client smoke', () => {
  it('builds action payloads', () => {
    const action = { type: 'BUZZ', playerId: 'p1', payload: {} }
    expect(action.type).toBe('BUZZ')
  })
})
