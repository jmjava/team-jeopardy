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
  if (response.status === 204) return null
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

export function ingestJiraBoard(payload) {
  return request('/api/rooms/ingest-jira', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function loadSavedBoard(payload) {
  return request('/api/rooms/load-board', {
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

export function listQuestionBank(limit = 100) {
  return request(`/api/question-bank?limit=${limit}`)
}

export function getQuestionBankBoard(id) {
  return request(`/api/question-bank/${encodeURIComponent(id)}`)
}

export function createQuestionBankBoard(payload) {
  return request('/api/question-bank', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function addQuestionBankClue(boardId, payload) {
  return request(`/api/question-bank/${encodeURIComponent(boardId)}/clues`, {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function deleteQuestionBankBoard(id) {
  return request(`/api/question-bank/${encodeURIComponent(id)}`, {
    method: 'DELETE'
  })
}

export function deleteQuestionBankClue(clueId) {
  return request(`/api/question-bank/clues/${encodeURIComponent(clueId)}`, {
    method: 'DELETE'
  })
}

export function deleteAllQuestionBank() {
  return request('/api/question-bank', {
    method: 'DELETE'
  })
}

export function searchQuestionBankClues(q = '', limit = 100) {
  const params = new URLSearchParams({ limit: String(limit) })
  if (q) params.set('q', q)
  return request(`/api/question-bank/clues?${params}`)
}

export function bulkUploadQuestionBank(payload) {
  return request('/api/question-bank/bulk', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function bulkUploadQuestionBankFile(file, skipDuplicates = true) {
  const form = new FormData()
  form.append('file', file)
  return fetch(`${API_BASE}/api/question-bank/bulk?skipDuplicates=${skipDuplicates ? 'true' : 'false'}`, {
    method: 'POST',
    body: form
  }).then(async (response) => {
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
  })
}

export function exportQuestionBank(limit = 200) {
  return request(`/api/question-bank/export?limit=${limit}`)
}
