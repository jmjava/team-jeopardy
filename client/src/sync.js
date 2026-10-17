/**
 * Full-state snapshots are safe to skip when they are older than what we
 * already applied. Equal revisions refresh (same state, useful after SYNC).
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
  return nextRev >= curRev
}

export function applySnapshot(current, incoming) {
  return shouldApplySnapshot(current, incoming) ? incoming : current
}
