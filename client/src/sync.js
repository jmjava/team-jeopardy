/**
 * Full-state snapshots are safe to skip when they are older than what we
 * already applied. Equal revisions refresh (same state, useful after SYNC),
 * except a redacted copy must not wipe a host view of that same revision.
 */
export function shouldApplySnapshot(current, incoming) {
  if (!incoming || typeof incoming !== 'object') {
    return false
  }
  const nextRev = incoming.revision
  const curRev = current?.revision
  if (typeof nextRev !== 'number' || typeof curRev !== 'number') {
    return true
  }
  if (nextRev < curRev) {
    return false
  }
  if (nextRev === curRev && hasPrivilegedClueText(current) && !hasPrivilegedClueText(incoming)) {
    return false
  }
  return true
}

function hasPrivilegedClueText(snapshot) {
  const clue = snapshot?.activeClue
  if (clue && (clue.response || clue.explanation)) return true
  if (clue?.prompt && snapshot?.phase === 'HOST_PREVIEW') return true
  const categories = snapshot?.board?.categories || []
  return categories.some((category) =>
    (category?.clues || []).some((item) => item?.prompt || item?.response)
  )
}

export function applySnapshot(current, incoming) {
  return shouldApplySnapshot(current, incoming) ? incoming : current
}
