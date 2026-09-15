import { describe, expect, it } from 'vitest'
import { SESSION_KEY, clearSession, copyText, loadSession, playerJoinUrl, saveSession } from './session'

function memory() {
  const data = new Map()
  return {
    getItem(key) {
      return data.has(key) ? data.get(key) : null
    },
    setItem(key, value) {
      data.set(key, String(value))
    },
    removeItem(key) {
      data.delete(key)
    }
  }
}

describe('session', () => {
  it('persists and restores a host seat', () => {
    const storage = memory()
    saveSession(
      {
        roomId: 'room-1',
        code: 'ABC123',
        playerId: 'host-1',
        hostName: 'Pat',
        displayName: 'Pat',
        isHost: true
      },
      storage
    )
    expect(loadSession(storage)).toEqual({
      roomId: 'room-1',
      code: 'ABC123',
      playerId: 'host-1',
      hostName: 'Pat',
      displayName: 'Pat',
      isHost: true
    })
    clearSession(storage)
    expect(loadSession(storage)).toBeNull()
    expect(storage.getItem(SESSION_KEY)).toBeNull()
  })

  it('ignores incomplete payloads', () => {
    const storage = memory()
    expect(saveSession({ roomId: 'x' }, storage)).toBeNull()
    storage.setItem(SESSION_KEY, '{not json')
    expect(loadSession(storage)).toBeNull()
  })

  it('builds a player join URL from a room code', () => {
    const href = playerJoinUrl('ab12cd', 'http://localhost:5173/?view=display&room=old')
    expect(href).toContain('code=AB12CD')
    expect(href).not.toContain('view=')
    expect(href).not.toContain('room=')
  })

  it('does not hang if clipboard.writeText never resolves', async () => {
    const started = Date.now()
    const result = await copyText('NXRU4Z', {
      clipboard: { writeText: () => new Promise(() => {}) }
    })
    expect(Date.now() - started).toBeLessThan(1500)
    expect(result).toBe(false)
  })
})
