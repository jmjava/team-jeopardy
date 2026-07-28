const API_BASE = import.meta.env.VITE_API_BASE || ''

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    },
    ...options
  })
  if (!response.ok) {
    let detail = response.statusText
    try {
      const body = await response.json()
      detail = body.message || body.error || detail
    } catch {
      // ignore
    }
    throw new Error(detail)
  }
  return response.json()
}

export function createRoom({ hostName, title }) {
  return request('/api/rooms', {
    method: 'POST',
    body: JSON.stringify({ hostName, title })
  })
}

export function joinRoom({ code, displayName, teamName }) {
  return request('/api/rooms/join', {
    method: 'POST',
    body: JSON.stringify({ code, displayName, teamName })
  })
}

export function ingestBoard(payload) {
  return request('/api/rooms/ingest', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function browseGithub({ repo, ref, path, recursive }) {
  return request('/api/github/browse', {
    method: 'POST',
    body: JSON.stringify({ repo, ref, path, recursive })
  })
}

export function postAction(roomId, { playerId, type, payload }) {
  return request(`/api/rooms/${roomId}/actions`, {
    method: 'POST',
    body: JSON.stringify({ playerId, type, payload })
  })
}

export function getRoom(roomId, playerId) {
  const q = playerId ? `?playerId=${encodeURIComponent(playerId)}` : ''
  return request(`/api/rooms/${roomId}${q}`)
}

export function getRoomByCode(code) {
  return request(`/api/rooms/code/${encodeURIComponent(code)}`)
}

export function getHealth() {
  return request('/api/health')
}
