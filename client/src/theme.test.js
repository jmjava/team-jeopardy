import { describe, expect, it } from 'vitest'
import { applyTheme, readThemeFromEnv } from './theme'

describe('theme', () => {
  it('falls back to Team Jeopardy tokens', () => {
    const theme = readThemeFromEnv()
    expect(theme.appName).toBe('Team Jeopardy')
    expect(theme.accent).toBe('#f0d060')
    expect(theme.board).toBe('#0b3d91')
  })

  it('writes CSS variables onto a style bag', () => {
    const bag = new Map()
    const style = {
      setProperty(name, value) {
        bag.set(name, value)
      }
    }
    const applied = applyTheme(
      {
        appName: 'Code Quiz Night',
        accent: '#38bdf8',
        accentDeep: '#0ea5e9',
        board: '#1e3a5f',
        boardCell: '#2563eb',
        background: '#0a1628',
        backgroundMid: '#123056'
      },
      style
    )
    expect(applied.appName).toBe('Code Quiz Night')
    expect(bag.get('--gold')).toBe('#38bdf8')
    expect(bag.get('--board')).toBe('#1e3a5f')
    expect(bag.get('--bg')).toBe('#0a1628')
  })
})
