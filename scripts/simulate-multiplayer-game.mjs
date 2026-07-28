#!/usr/bin/env node
/**
 * Validates all main moderator/game scenarios against a running API:
 *   - sample content picks (maven/gradle/vue/python)
 *   - GitHub folder ingest + PRs-only
 *   - question hints / focus chips
 *   - lobby admit gate (unadmitted cannot buzz / cannot start without admit)
 *   - host preview vs public redaction
 *   - open buzzers, buzz race, incorrect reopen, correct judge, reveal, return
 *
 * Usage:
 *   node scripts/simulate-multiplayer-game.mjs [owner/repo] [ref] [folder]
 * Env:
 *   API_BASE (default http://localhost:8080)
 *   SKIP_GITHUB=1 to skip GitHub/PR scenarios (samples only)
 *
 * Prefer a branch that contains source (e.g. the feature branch), not an empty main.
 */
const API = process.env.API_BASE || 'http://localhost:8080'
const repo = process.argv[2] || 'jmjava/team-jeopardy'
const ref = process.argv[3] || process.env.SIM_REF || 'cursor/team-jeopardy-realtime-dc86'
const folder = process.argv[4] || 'client/src'
const skipGithub = process.env.SKIP_GITHUB === '1'

let passed = 0
let failed = 0
const failures = []

async function req(path, options = {}) {
  const res = await fetch(`${API}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options
  })
  const text = await res.text()
  let body
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = text
  }
  return { ok: res.ok, status: res.status, body }
}

async function must(path, options = {}) {
  const result = await req(path, options)
  if (!result.ok) {
    throw new Error(
      `${options.method || 'GET'} ${path} -> ${result.status}: ${
        typeof result.body === 'string' ? result.body : JSON.stringify(result.body)
      }`
    )
  }
  return result.body
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg)
}

async function scenario(name, fn) {
  process.stdout.write(`\n▶ ${name}\n`)
  try {
    await fn()
    passed += 1
    console.log(`  ✓ ${name}`)
  } catch (err) {
    failed += 1
    failures.push({ name, error: err.message })
    console.error(`  ✗ ${name}: ${err.message}`)
  }
}

async function createRoom(title) {
  const created = await must('/api/rooms', {
    method: 'POST',
    body: JSON.stringify({ hostName: 'Pat Host', title })
  })
  return {
    roomId: created.snapshot.roomId,
    code: created.snapshot.code,
    hostId: created.hostPlayerId
  }
}

async function join(code, displayName, teamName) {
  return must('/api/rooms/join', {
    method: 'POST',
    body: JSON.stringify({ code, displayName, teamName })
  })
}

async function action(roomId, playerId, type, payload = {}) {
  return must(`/api/rooms/${roomId}/actions`, {
    method: 'POST',
    body: JSON.stringify({ playerId, type, payload })
  })
}

async function actionExpectFail(roomId, playerId, type, payload = {}) {
  const result = await req(`/api/rooms/${roomId}/actions`, {
    method: 'POST',
    body: JSON.stringify({ playerId, type, payload })
  })
  assert(!result.ok, `Expected ${type} to fail but got ${result.status}`)
  return result
}

async function ingest(roomId, hostId, body) {
  return must('/api/rooms/ingest', {
    method: 'POST',
    body: JSON.stringify({ roomId, playerId: hostId, ...body })
  })
}

function firstOpenClue(snap) {
  for (const cat of snap.board?.categories || []) {
    for (const clue of cat.clues || []) {
      const cell = (snap.cells || []).find((c) => c.clueId === clue.id)
      if (!cell?.answered) return clue
    }
  }
  return null
}

async function playThroughClue(roomId, hostId, players, { incorrectFirst = true } = {}) {
  let snap = await must(`/api/rooms/${roomId}?playerId=${hostId}`)
  const clue = firstOpenClue(snap)
  assert(clue, 'No unanswered clue')

  snap = await action(roomId, hostId, 'SELECT_CLUE', { clueId: clue.id })
  assert(snap.phase === 'HOST_PREVIEW', `Expected HOST_PREVIEW, got ${snap.phase}`)
  assert(snap.activeClue?.prompt, 'Host must see prompt in preview')
  assert(snap.activeClue?.response, 'Host must see answer in preview')

  const pub = await must(`/api/rooms/${roomId}`)
  assert(!pub.activeClue?.prompt, 'Public must hide prompt during HOST_PREVIEW')
  assert(!pub.activeClue?.response, 'Public must hide answer during HOST_PREVIEW')

  snap = await action(roomId, hostId, 'OPEN_BUZZERS')
  assert(snap.phase === 'CLUE_OPEN', `Expected CLUE_OPEN, got ${snap.phase}`)
  assert(snap.activeClue?.prompt, 'Players should see prompt after open')

  const [p1, p2] = players
  if (incorrectFirst && p2) {
    snap = await action(roomId, p1.playerId, 'BUZZ')
    assert(snap.phase === 'BUZZ_LOCKED', 'Expected BUZZ_LOCKED')
    assert(snap.activeClue?.buzzedPlayerName, 'Buzz should record player')

    await actionExpectFail(roomId, p2.playerId, 'BUZZ')

    snap = await action(roomId, hostId, 'JUDGE', { correct: false })
    assert(snap.phase === 'CLUE_OPEN', 'Incorrect judge should reopen buzzers')
    assert(!snap.activeClue?.buzzedPlayerId, 'Buzz lock should clear after incorrect')

    snap = await action(roomId, p2.playerId, 'BUZZ')
    assert(snap.phase === 'BUZZ_LOCKED', 'Second player should buzz after reopen')
    snap = await action(roomId, hostId, 'JUDGE', { correct: true })
  } else if (p2) {
    // Race: both try; only first wins
    const buzzResults = await Promise.allSettled([
      action(roomId, p1.playerId, 'BUZZ'),
      sleep(8).then(() => action(roomId, p2.playerId, 'BUZZ'))
    ])
    const winner = buzzResults.find((r) => r.status === 'fulfilled')?.value
    assert(winner?.phase === 'BUZZ_LOCKED', 'Expected first buzz to lock')
    snap = await action(roomId, hostId, 'JUDGE', { correct: true })
  } else {
    snap = await action(roomId, p1.playerId, 'BUZZ')
    assert(snap.phase === 'BUZZ_LOCKED', 'Expected BUZZ_LOCKED')
    snap = await action(roomId, hostId, 'JUDGE', { correct: true })
  }

  assert(snap.phase === 'ANSWER_REVEALED', `Expected ANSWER_REVEALED, got ${snap.phase}`)
  assert(snap.activeClue?.responseVisible, 'Answer should be visible after correct')

  snap = await action(roomId, hostId, 'RETURN_BOARD')
  assert(snap.phase === 'BOARD', `Expected BOARD, got ${snap.phase}`)
  return snap
}

async function main() {
  console.log(`API ${API}`)
  console.log(`Repo ${repo}@${ref} folder=${folder} skipGithub=${skipGithub}`)

  const health = await must('/api/health')
  assert(health.status === 'ok', 'Health not ok')
  console.log('health ok; supported:', (health.supported || []).join(', '))

  // --- Sample content picks with hints ---
  for (const sampleType of ['maven', 'gradle', 'vue', 'python']) {
    await scenario(`sample:${sampleType} + hints + admit + incorrect/correct loop`, async () => {
      const { roomId, code, hostId } = await createRoom(`Sample ${sampleType}`)
      const p1 = await join(code, 'Alex', 'Blue Owls')
      const p2 = await join(code, 'Sam', 'Red Foxes')

      // Unadmitted cannot start the game meaningfully — START should fail with no admitted players
      await actionExpectFail(roomId, hostId, 'START')

      const board = await ingest(roomId, hostId, {
        useSample: true,
        sampleType,
        boardTitle: `${sampleType} board`,
        questionHints: `Emphasize ${sampleType} architecture and QA risk`,
        questionFocuses: ['architecture', 'qa', 'patterns']
      })
      assert(board.snapshot.board?.categories?.length, `${sampleType} board empty`)
      assert(
        board.snapshot.questionHints?.includes(sampleType) ||
          board.snapshot.questionHints?.includes('architecture') ||
          !!board.snapshot.questionHints,
        'questionHints should be stored on snapshot'
      )
      console.log(
        `    categories=${board.ingestSummary?.categories || board.snapshot.board.categories.length}`,
        `titles=${(board.ingestSummary?.categoryTitles || board.snapshot.board.categories.map((c) => c.title)).slice(0, 4).join(' | ')}`
      )

      // Unadmitted buzz path: admit none, force board+start shouldn't work; after admit+start, stranger can't buzz
      await action(roomId, hostId, 'ADMIT_PLAYER', { playerId: p1.playerId })
      // p2 still waiting
      let snap = await action(roomId, hostId, 'START')
      assert(snap.phase === 'BOARD', 'Should start after one admitted player')

      const clue = firstOpenClue(snap)
      snap = await action(roomId, hostId, 'SELECT_CLUE', { clueId: clue.id })
      snap = await action(roomId, hostId, 'OPEN_BUZZERS')
      await actionExpectFail(roomId, p2.playerId, 'BUZZ') // not admitted
      console.log('    unadmitted buzz blocked')

      await action(roomId, hostId, 'ADMIT_PLAYER', { playerId: p2.playerId })
      // finish this clue with incorrect then correct
      snap = await action(roomId, p1.playerId, 'BUZZ')
      snap = await action(roomId, hostId, 'JUDGE', { correct: false })
      assert(snap.phase === 'CLUE_OPEN', 'reopen after incorrect')
      snap = await action(roomId, p2.playerId, 'BUZZ')
      snap = await action(roomId, hostId, 'JUDGE', { correct: true })
      await action(roomId, hostId, 'RETURN_BOARD')

      // second clue: race + correct
      await playThroughClue(roomId, hostId, [p1, p2], { incorrectFirst: false })
    })
  }

  // --- GitHub browse + folder ingest + PRs ---
  if (!skipGithub) {
    await scenario('github:browse folders', async () => {
      const browse = await must('/api/github/browse', {
        method: 'POST',
        body: JSON.stringify({ repo, ref, path: '', recursive: false })
      })
      assert(Array.isArray(browse.folders), 'browse.folders missing')
      assert(browse.folders.length > 0, `Expected folders on ${repo}@${ref}`)
      console.log(`    top-level folders=${browse.folders.length}`)
    })

    await scenario('github:folder ingest + hints + admit-all + full loop', async () => {
      const { roomId, code, hostId } = await createRoom(`GH ${repo}`)
      const p1 = await join(code, 'Alex', 'Blue Owls')
      const p2 = await join(code, 'Sam', 'Red Foxes')

      const board = await ingest(roomId, hostId, {
        useSample: false,
        sampleType: 'github',
        repo,
        ref,
        folders: folder ? [folder] : [],
        includePulls: true,
        prLimit: 8,
        boardTitle: `Sim Jeopardy ${repo}`,
        questionHints: 'Focus on architecture, components, and recent pull requests',
        questionFocuses: ['architecture', 'components', 'pull-requests', 'qa']
      })
      assert(board.snapshot.board?.categories?.length, 'GitHub board empty')
      assert(
        (board.ingestSummary?.nodes || 0) > 0 || (board.ingestSummary?.pullRequests || 0) > 0,
        'GitHub ingest produced neither code nodes nor PRs'
      )
      console.log('    ingest', {
        nodes: board.ingestSummary?.nodes,
        edges: board.ingestSummary?.edges,
        pullRequests: board.ingestSummary?.pullRequests,
        categories: board.ingestSummary?.categories,
        categoryTitles: board.ingestSummary?.categoryTitles,
        source: board.ingestSummary?.source,
        fetchMethod: board.ingestSummary?.fetchMethod
      })

      await action(roomId, hostId, 'ADMIT_ALL')
      await action(roomId, hostId, 'START')
      await playThroughClue(roomId, hostId, [p1, p2], { incorrectFirst: true })

      // reveal path on another clue
      let snap = await must(`/api/rooms/${roomId}?playerId=${hostId}`)
      const clue = firstOpenClue(snap)
      assert(clue, 'Need another clue for REVEAL path')
      snap = await action(roomId, hostId, 'SELECT_CLUE', { clueId: clue.id })
      snap = await action(roomId, hostId, 'OPEN_BUZZERS')
      snap = await action(roomId, hostId, 'REVEAL')
      assert(snap.phase === 'ANSWER_REVEALED' || snap.activeClue?.responseVisible, 'REVEAL should show answer')
      await action(roomId, hostId, 'RETURN_BOARD')
    })

    await scenario('github:PRs-only board with pull-requests focus', async () => {
      const { roomId, code, hostId } = await createRoom(`PRs ${repo}`)
      const p1 = await join(code, 'Riley', 'Green Beans')

      const board = await ingest(roomId, hostId, {
        useSample: false,
        sampleType: 'pulls',
        repo,
        prLimit: 10,
        boardTitle: `PR Jeopardy ${repo}`,
        questionHints: 'Recent pull requests and review risk',
        questionFocuses: ['pull-requests', 'qa']
      })
      assert(board.snapshot.board?.categories?.length, 'PR board empty')
      console.log('    PR ingest', {
        pullRequests: board.ingestSummary?.pullRequests,
        categories: board.ingestSummary?.categories,
        categoryTitles: board.ingestSummary?.categoryTitles,
        source: board.ingestSummary?.source
      })

      await action(roomId, hostId, 'ADMIT_ALL')
      await action(roomId, hostId, 'START')
      await playThroughClue(roomId, hostId, [p1], { incorrectFirst: false })
    })
  } else {
    console.log('\n(skipping GitHub scenarios — SKIP_GITHUB=1)')
  }

  console.log(`\n==== results: ${passed} passed, ${failed} failed ====`)
  if (failures.length) {
    for (const f of failures) console.error(`- ${f.name}: ${f.error}`)
    process.exit(1)
  }
  console.log('SUCCESS all moderator scenarios validated')
}

main().catch((err) => {
  console.error('FAILED', err.message)
  process.exit(1)
})
