<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  browseGithub,
  createRoom,
  getHealth,
  getRoom,
  ingestBoard,
  ingestJiraBoard,
  joinRoom,
  loadSavedBoard,
  postAction
} from './api'
import { connectGameSocket } from './useGameSocket'
import { appName } from './theme'
import { clearSession, copyText, loadSession, playerJoinUrl, saveSession } from './session'
import BrandHeader from './components/BrandHeader.vue'
import LobbyView from './components/LobbyView.vue'
import ModeratorConsole from './components/ModeratorConsole.vue'
import QuestionBankAdmin from './components/QuestionBankAdmin.vue'
import WaitingRoom from './components/WaitingRoom.vue'
import BoardView from './components/BoardView.vue'
import ClueStage from './components/ClueStage.vue'
import HostCluePreview from './components/HostCluePreview.vue'
import Scorebar from './components/Scorebar.vue'
import SharedDisplay from './components/SharedDisplay.vue'

const params = new URLSearchParams(window.location.search)
const initialView = params.get('view')
const viewMode = ref(
  initialView === 'display' ? 'display' : initialView === 'admin' ? 'admin' : 'app'
)

const session = reactive({
  roomId: '',
  code: '',
  playerId: '',
  hostName: '',
  displayName: '',
  isHost: false,
  admitted: false
})

const snapshot = ref(null)
const socketStatus = ref('idle')
const error = ref('')
const copied = ref('')
const busy = ref(false)
const restoring = ref(false)
const ingestSummary = ref(null)
const health = ref(null)
const defaultRepo = ref('jmjava/team-jeopardy')
let socket = null
let copiedTimer = 0

const phase = computed(() => snapshot.value?.phase || 'LOBBY')
const me = computed(() =>
  (snapshot.value?.players || []).find((p) => p.id === session.playerId)
)
const admitted = computed(() => session.isHost || !!me.value?.admitted)
const myTeamName = computed(() => {
  const teamId = me.value?.teamId
  return snapshot.value?.teams?.find((t) => t.id === teamId)?.name || ''
})

const showHostPreview = computed(
  () => session.isHost && phase.value === 'HOST_PREVIEW' && snapshot.value?.activeClue
)
const showClue = computed(() =>
  ['CLUE_OPEN', 'BUZZ_LOCKED', 'ANSWER_REVEALED'].includes(phase.value)
  || (!session.isHost && phase.value === 'HOST_PREVIEW')
)
const showBoard = computed(() =>
  admitted.value && (phase.value === 'BOARD' || phase.value === 'FINISHED')
)
const displayUrl = computed(() => {
  if (!session.roomId) return ''
  const url = new URL(window.location.href)
  url.searchParams.set('view', 'display')
  url.searchParams.set('room', session.roomId)
  url.searchParams.set('code', session.code)
  return url.toString()
})
const joinUrl = computed(() => playerJoinUrl(session.code, window.location.href))
const inviteCode = computed(() => (params.get('code') || '').trim().toUpperCase())
const reconnecting = computed(
  () => !!session.roomId && ['disconnected', 'error'].includes(socketStatus.value)
)

async function refreshHealth() {
  try {
    health.value = await getHealth()
    if (health.value?.defaultRepo) {
      defaultRepo.value = health.value.defaultRepo
    }
  } catch {
    health.value = null
  }
}
refreshHealth()

function persistSeat() {
  if (viewMode.value !== 'app') return
  saveSession(session)
}

function bindSocket(roomId, isHost) {
  socket?.disconnect()
  socket = connectGameSocket({
    roomId,
    playerId: session.playerId,
    isHost,
    onSnapshot: (next) => {
      const incoming = next?.revision
      const current = snapshot.value?.revision
      if (
        typeof incoming === 'number' &&
        typeof current === 'number' &&
        incoming < current
      ) {
        return
      }
      snapshot.value = next
      const self = (next.players || []).find((p) => p.id === session.playerId)
      session.admitted = !!self?.admitted || session.isHost
      persistSeat()
    },
    onStatus: (status) => {
      socketStatus.value = status
    },
    onError: (body) => {
      if (body?.message) error.value = body.message
    }
  })
}

function applySeat(result, { isHost, displayName, hostName }) {
  session.roomId = result.snapshot.roomId
  session.code = result.snapshot.code
  session.playerId = result.hostPlayerId || result.playerId
  session.hostName = hostName || result.hostName || ''
  session.displayName = displayName || session.hostName
  session.isHost = isHost
  session.admitted = isHost
  snapshot.value = result.snapshot
  bindSocket(session.roomId, session.isHost)
  persistSeat()
}

async function onCreate(form) {
  error.value = ''
  busy.value = true
  try {
    const result = await createRoom({
      hostName: form.hostName,
      title: form.title
    })
    applySeat(result, {
      isHost: true,
      displayName: result.hostName,
      hostName: result.hostName
    })
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function onJoin(form) {
  error.value = ''
  busy.value = true
  try {
    const result = await joinRoom({
      code: form.code,
      displayName: form.displayName,
      teamName: form.teamName
    })
    applySeat(result, {
      isHost: result.snapshot.hostPlayerId === result.playerId,
      displayName: form.displayName
    })
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function runAction(type, payload = {}) {
  error.value = ''
  try {
    if (socket && socketStatus.value === 'connected') {
      socket.sendAction({
        type,
        playerId: session.playerId,
        payload
      })
      return
    }
    snapshot.value = await postAction(session.roomId, {
      playerId: session.playerId,
      type,
      payload
    })
  } catch (err) {
    error.value = err.message
  }
}

async function onIngest(payload = {}) {
  const sampleType = typeof payload === 'string' ? payload : payload.sampleType || 'maven'
  const questionHints = typeof payload === 'object' ? payload.questionHints : ''
  const questionFocuses = typeof payload === 'object' ? payload.questionFocuses : []
  error.value = ''
  busy.value = true
  try {
    const result = await ingestBoard({
      roomId: session.roomId,
      playerId: session.playerId,
      useSample: true,
      sampleType,
      boardTitle: snapshot.value?.title || `${sampleType} ${appName}`,
      questionHints,
      questionFocuses
    })
    snapshot.value = result.snapshot
    ingestSummary.value = result.ingestSummary
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function onIngestGithub(payload) {
  error.value = ''
  busy.value = true
  try {
    const result = await ingestBoard({
      roomId: session.roomId,
      playerId: session.playerId,
      useSample: false,
      sampleType: 'github',
      repo: payload.repo,
      ref: payload.ref,
      folders: payload.folders || [],
      includePulls: payload.includePulls !== false,
      boardTitle: snapshot.value?.title || `GitHub: ${payload.repo}`,
      questionHints: payload.questionHints,
      questionFocuses: payload.questionFocuses
    })
    snapshot.value = result.snapshot
    ingestSummary.value = result.ingestSummary
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function onIngestPulls(payload) {
  error.value = ''
  busy.value = true
  try {
    const result = await ingestBoard({
      roomId: session.roomId,
      playerId: session.playerId,
      useSample: false,
      sampleType: 'pulls',
      repo: payload.repo,
      boardTitle: snapshot.value?.title || `PRs: ${payload.repo}`,
      questionHints: payload.questionHints,
      questionFocuses: payload.questionFocuses
    })
    snapshot.value = result.snapshot
    ingestSummary.value = result.ingestSummary
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function onIngestJira(payload) {
  error.value = ''
  busy.value = true
  try {
    const result = await ingestJiraBoard({
      roomId: session.roomId,
      playerId: session.playerId,
      projects: payload.projects || [],
      release: payload.release,
      jql: payload.jql,
      useFixture: payload.useFixture !== false,
      fixture: payload.fixture,
      boardTitle: snapshot.value?.title || `JIRA: ${payload.release || 'release'}`,
      questionHints: payload.questionHints,
      questionFocuses: payload.questionFocuses
    })
    snapshot.value = result.snapshot
    ingestSummary.value = result.ingestSummary
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

async function onBrowseGithub(payload) {
  try {
    const result = await browseGithub({
      repo: payload.repo,
      ref: payload.ref,
      path: payload.path,
      recursive: payload.recursive
    })
    payload.resolve?.(result)
  } catch (err) {
    payload.reject?.(err)
    error.value = err.message
  }
}

async function onLoadSavedBoard(savedBoardId) {
  error.value = ''
  busy.value = true
  try {
    const result = await loadSavedBoard({
      roomId: session.roomId,
      playerId: session.playerId,
      savedBoardId
    })
    snapshot.value = result.snapshot
    ingestSummary.value = result.ingestSummary
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}

function openQuestionBankAdmin() {
  const url = new URL(window.location.href)
  url.searchParams.set('view', 'admin')
  window.open(url.toString(), '_blank', 'noopener')
}

function leaveAdmin() {
  const url = new URL(window.location.href)
  url.searchParams.delete('view')
  window.history.replaceState({}, '', url)
  viewMode.value = 'app'
}

function openDisplay() {
  if (displayUrl.value) {
    window.open(displayUrl.value, '_blank', 'noopener')
  }
}

async function flashCopied(label) {
  copied.value = label
  window.clearTimeout(copiedTimer)
  copiedTimer = window.setTimeout(() => {
    copied.value = ''
  }, 1600)
}

async function copyValue(text, label) {
  if (!text) return
  flashCopied(label)
  const ok = await copyText(text)
  if (!ok) {
    copied.value = ''
    error.value = 'Could not copy to clipboard — select the room code and copy it manually'
  }
}

function copyCode() {
  return copyValue(session.code, 'Room code copied')
}

function copyJoinLink() {
  return copyValue(joinUrl.value, 'Player link copied')
}

function copyDisplayLink() {
  return copyValue(displayUrl.value, 'Display link copied')
}

function leaveRoom() {
  socket?.disconnect()
  socket = null
  clearSession()
  session.roomId = ''
  session.code = ''
  session.playerId = ''
  session.hostName = ''
  session.displayName = ''
  session.isHost = false
  session.admitted = false
  snapshot.value = null
  ingestSummary.value = null
  socketStatus.value = 'idle'
  error.value = ''
}

async function restoreSeat() {
  if (viewMode.value !== 'app') return
  const saved = loadSession()
  if (!saved) return
  restoring.value = true
  try {
    const snap = await getRoom(saved.roomId, saved.playerId)
    session.roomId = snap.roomId
    session.code = snap.code || saved.code
    session.playerId = saved.playerId
    session.hostName = saved.hostName
    session.displayName = saved.displayName
    session.isHost = saved.isHost || snap.hostPlayerId === saved.playerId
    snapshot.value = snap
    const self = (snap.players || []).find((p) => p.id === session.playerId)
    session.admitted = !!self?.admitted || session.isHost
    bindSocket(session.roomId, session.isHost)
    persistSeat()
  } catch {
    clearSession()
  } finally {
    restoring.value = false
  }
}

async function bootDisplay() {
  const room = params.get('room')
  const code = params.get('code')
  if (!room && !code) return
  try {
    const { getRoomByCode } = await import('./api')
    const snap = room ? await getRoom(room) : await getRoomByCode(code)
    session.roomId = snap.roomId
    session.code = snap.code
    session.isHost = false
    session.admitted = true
    snapshot.value = snap
    bindSocket(session.roomId, false)
  } catch (err) {
    error.value = err.message
  }
}

function typingInField(target) {
  return !!target?.closest?.('input, textarea, select, [contenteditable="true"]')
}

function onHostKey(e) {
  if (!session.isHost || viewMode.value !== 'app') return
  if (typingInField(e.target)) return
  if (e.repeat) return
  const key = e.key
  if (phase.value === 'HOST_PREVIEW' && (key === 'Enter' || key === 'o' || key === 'O')) {
    e.preventDefault()
    runAction('OPEN_BUZZERS')
    return
  }
  if (phase.value === 'BUZZ_LOCKED' && (key === 'c' || key === 'C' || key === 'y' || key === 'Y')) {
    e.preventDefault()
    runAction('JUDGE', { correct: true })
    return
  }
  if (phase.value === 'BUZZ_LOCKED' && (key === 'x' || key === 'X' || key === 'n' || key === 'N')) {
    e.preventDefault()
    runAction('JUDGE', { correct: false })
    return
  }
  if (phase.value !== 'ANSWER_REVEALED' && phase.value !== 'BOARD' && phase.value !== 'LOBBY' && phase.value !== 'FINISHED'
      && (key === 'r' || key === 'R')) {
    e.preventDefault()
    runAction('REVEAL')
    return
  }
  if (phase.value === 'ANSWER_REVEALED' && (key === 'Escape' || key === 'b' || key === 'B')) {
    e.preventDefault()
    runAction('RETURN_BOARD')
  }
}

if (viewMode.value === 'display') {
  bootDisplay()
} else if (viewMode.value === 'app') {
  restoreSeat()
}

watch(
  () => session.roomId,
  (id) => {
    if (!id) {
      socket?.disconnect()
      socket = null
    }
  }
)

onMounted(() => window.addEventListener('keydown', onHostKey))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onHostKey)
  window.clearTimeout(copiedTimer)
  socket?.disconnect()
})
</script>

<template>
  <SharedDisplay v-if="viewMode === 'display'" :snapshot="snapshot" />

  <div v-else-if="viewMode === 'admin'" class="shell">
    <a class="skip-link" href="#main">Skip to content</a>
    <header class="top">
      <BrandHeader eyebrow="SQLite · question bank" compact />
    </header>
    <main id="main">
      <QuestionBankAdmin @back="leaveAdmin" />
    </main>
  </div>

  <div v-else class="shell">
    <a class="skip-link" href="#main">Skip to content</a>
    <header class="top">
      <BrandHeader eyebrow="Moderator · lobby · live buzzers" />
      <div class="meta" v-if="session.code">
        <div>
          <span class="muted">Room</span>
          <strong>{{ session.code }}</strong>
        </div>
        <div>
          <span class="muted">You</span>
          <strong>{{ session.displayName }}</strong>
        </div>
        <div>
          <span class="muted">Sync</span>
          <strong :class="socketStatus">{{ socketStatus }}</strong>
        </div>
        <div class="meta-actions">
          <button type="button" class="secondary slim" @click="copyCode">Copy code</button>
          <button type="button" class="secondary slim" @click="leaveRoom">Leave</button>
        </div>
      </div>
    </header>

    <p v-if="reconnecting" class="banner reconnect" role="status">
      Realtime sync dropped — reconnecting…
    </p>
    <p v-if="copied" class="banner copied" role="status">{{ copied }}</p>
    <p v-if="error" class="error" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="secondary slim" @click="error = ''">Dismiss</button>
    </p>
    <p v-if="restoring" class="muted restore">Restoring your seat…</p>

    <main id="main">
      <LobbyView
        v-if="!session.roomId && !restoring"
        :busy="busy"
        :health="health"
        :initial-code="inviteCode"
        @create="onCreate"
        @join="onJoin"
        @open-admin="openQuestionBankAdmin"
      />

      <template v-else-if="session.roomId">
        <Scorebar
          v-if="admitted && phase !== 'LOBBY'"
          :teams="snapshot?.teams || []"
          :phase="phase"
          :buzzed-team-id="snapshot?.activeClue?.buzzedTeamId"
        />

        <ModeratorConsole
          v-if="session.isHost && phase === 'LOBBY'"
          :snapshot="snapshot"
          :busy="busy"
          :ingest-summary="ingestSummary"
          :display-url="displayUrl"
          :join-url="joinUrl"
          :default-repo="defaultRepo"
          @ingest="onIngest"
          @ingest-github="onIngestGithub"
          @ingest-pulls="onIngestPulls"
          @ingest-jira="onIngestJira"
          @browse-github="onBrowseGithub"
          @load-saved="onLoadSavedBoard"
          @open-admin="openQuestionBankAdmin"
          @admit="(playerId) => runAction('ADMIT_PLAYER', { playerId })"
          @admit-all="runAction('ADMIT_ALL')"
          @start="runAction('START')"
          @open-display="openDisplay"
          @copy-code="copyCode"
          @copy-join="copyJoinLink"
          @copy-display="copyDisplayLink"
        />

        <WaitingRoom
          v-else-if="!admitted"
          :code="session.code"
          :display-name="session.displayName"
          :team-name="myTeamName"
          :phase="phase"
        />

        <section v-else-if="admitted && phase === 'LOBBY'" class="waiting-start">
          <p class="kicker">You're in</p>
          <h2>Waiting for kickoff</h2>
          <p class="muted">
            The moderator admitted you
            <template v-if="myTeamName"> on <strong>{{ myTeamName }}</strong></template>.
            Hang tight until the game starts.
          </p>
        </section>

        <BoardView
          v-else-if="showBoard"
          :board="snapshot?.board"
          :cells="snapshot?.cells || []"
          :is-host="session.isHost"
          :finished="phase === 'FINISHED'"
          @select="(clueId) => runAction('SELECT_CLUE', { clueId })"
        />

        <HostCluePreview
          v-else-if="showHostPreview"
          :clue="snapshot?.activeClue"
          @open="runAction('OPEN_BUZZERS')"
          @reveal="runAction('REVEAL')"
          @back="runAction('RETURN_BOARD')"
        />

        <ClueStage
          v-else-if="showClue"
          :clue="snapshot?.activeClue"
          :phase="phase"
          :is-host="session.isHost"
          :can-buzz="admitted && !session.isHost"
          @buzz="runAction('BUZZ')"
          @judge="(correct) => runAction('JUDGE', { correct })"
          @reveal="runAction('REVEAL')"
          @back="runAction('RETURN_BOARD')"
          @open="runAction('OPEN_BUZZERS')"
        />
      </template>
    </main>
  </div>
</template>

<style scoped>
.shell {
  width: min(1180px, calc(100% - 2rem));
  margin: 0 auto;
  padding: 1.35rem 0 3rem;
}

.top {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: end;
  margin-bottom: 1.35rem;
}

.meta {
  display: flex;
  gap: 1rem;
  padding: 0.7rem 1rem;
  border-radius: 14px;
  background: rgba(6, 16, 34, 0.55);
  border: 1px solid rgba(244, 247, 255, 0.08);
  flex-wrap: wrap;
  align-items: end;
}

.meta > div {
  display: grid;
  gap: 0.12rem;
  min-width: 4.5rem;
}

.meta-actions {
  display: flex !important;
  gap: 0.4rem;
  min-width: 0;
}

.slim {
  padding: 0.4rem 0.7rem;
  font-size: 0.82rem;
}

.meta strong.connected {
  color: var(--ok);
}

.meta strong.disconnected,
.meta strong.error {
  color: var(--danger);
}

.error,
.banner {
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
  align-items: center;
  padding: 0.75rem 1rem;
  border-radius: 12px;
  margin-bottom: 1rem;
}

.error {
  background: rgba(232, 93, 76, 0.15);
  border: 1px solid rgba(232, 93, 76, 0.45);
  color: #ffd2cc;
}

.banner.copied {
  background: rgba(62, 207, 142, 0.14);
  border: 1px solid rgba(62, 207, 142, 0.35);
  color: var(--answer-text);
}

.banner.reconnect {
  background: rgba(240, 208, 96, 0.12);
  border: 1px solid rgba(240, 208, 96, 0.4);
  color: var(--gold);
}

.restore {
  margin: 0 0 1rem;
}

.waiting-start {
  min-height: 40vh;
  display: grid;
  align-content: center;
  gap: 0.5rem;
  max-width: 34rem;
}

.waiting-start .kicker {
  margin: 0;
  text-transform: uppercase;
  letter-spacing: 0.16em;
  font-size: 0.72rem;
  color: var(--gold);
}

.waiting-start h2 {
  font-size: clamp(2rem, 5vw, 3rem);
  line-height: 0.95;
}

@media (max-width: 800px) {
  .top,
  .meta {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
