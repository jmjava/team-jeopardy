export const SESSION_KEY = 'team-jeopardy.session'

function memoryStorage() {
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

export function defaultSessionStorage() {
  try {
    if (typeof sessionStorage !== 'undefined') return sessionStorage
  } catch {
    // private mode / blocked storage
  }
  return memoryStorage()
}

export function saveSession(session, storage = defaultSessionStorage()) {
  if (!session?.roomId || !session?.playerId) {
    storage.removeItem(SESSION_KEY)
    return null
  }
  const payload = {
    roomId: session.roomId,
    code: session.code || '',
    playerId: session.playerId,
    hostName: session.hostName || '',
    displayName: session.displayName || '',
    isHost: !!session.isHost
  }
  storage.setItem(SESSION_KEY, JSON.stringify(payload))
  return payload
}

export function loadSession(storage = defaultSessionStorage()) {
  const raw = storage.getItem(SESSION_KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw)
    if (!parsed?.roomId || !parsed?.playerId) return null
    return parsed
  } catch {
    storage.removeItem(SESSION_KEY)
    return null
  }
}

export function clearSession(storage = defaultSessionStorage()) {
  storage.removeItem(SESSION_KEY)
}

export function playerJoinUrl(code, locationHref = typeof window !== 'undefined' ? window.location.href : '') {
  if (!code || !locationHref) return ''
  const url = new URL(locationHref)
  url.searchParams.delete('view')
  url.searchParams.delete('room')
  url.searchParams.set('code', String(code).trim().toUpperCase())
  return url.toString()
}

export async function copyText(text) {
  if (!text) return false
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // fall through to execCommand
  }
  if (typeof document === 'undefined') return false
  const el = document.createElement('textarea')
  el.value = text
  el.setAttribute('readonly', '')
  el.style.position = 'fixed'
  el.style.left = '-9999px'
  document.body.appendChild(el)
  el.select()
  try {
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    el.remove()
  }
}
