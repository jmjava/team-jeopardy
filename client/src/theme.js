const defaults = {
  appName: 'Team Jeopardy',
  logoUrl: '',
  accent: '#f0d060',
  accentDeep: '#c9a227',
  board: '#0b3d91',
  boardCell: '#1152c0',
  background: '#071225',
  backgroundMid: '#10284f'
}

function env(name, fallback) {
  const value = import.meta.env[name]
  return typeof value === 'string' && value.trim() ? value.trim() : fallback
}

export function readThemeFromEnv() {
  return {
    appName: env('VITE_APP_NAME', defaults.appName),
    logoUrl: env('VITE_LOGO_URL', defaults.logoUrl),
    accent: env('VITE_ACCENT_COLOR', defaults.accent),
    accentDeep: env('VITE_ACCENT_DEEP_COLOR', defaults.accentDeep),
    board: env('VITE_BOARD_COLOR', defaults.board),
    boardCell: env('VITE_BOARD_CELL_COLOR', defaults.boardCell),
    background: env('VITE_BACKGROUND_COLOR', defaults.background),
    backgroundMid: env('VITE_BACKGROUND_MID_COLOR', defaults.backgroundMid)
  }
}

export function applyTheme(theme = readThemeFromEnv(), rootStyle) {
  const next = { ...defaults, ...theme }
  const style = rootStyle || (typeof document !== 'undefined' ? document.documentElement.style : null)
  if (style) {
    style.setProperty('--gold', next.accent)
    style.setProperty('--gold-deep', next.accentDeep)
    style.setProperty('--board', next.board)
    style.setProperty('--board-cell', next.boardCell)
    style.setProperty('--bg', next.background)
    style.setProperty('--bg-mid', next.backgroundMid)
  }
  if (typeof document !== 'undefined') {
    document.title = next.appName
    const favicon = document.querySelector('link[rel="icon"]')
    if (favicon && next.logoUrl) {
      favicon.setAttribute('href', next.logoUrl)
    }
  }
  return next
}

export const theme = readThemeFromEnv()
export const appName = theme.appName
export const logoUrl = theme.logoUrl
