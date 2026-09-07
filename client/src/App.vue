<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  browseGithub,
  createRoom,
  getHealth,
  ingestBoard,
  joinRoom,
  loadSavedBoard,
  postAction
} from './api'
import { connectGameSocket } from './useGameSocket'
import { getGameSfx, sfxEventForTransition } from './useGameSfx'
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
const busy = ref(false)
const ingestSummary = ref(null)
const health = ref(null)
const defaultRepo = ref('jmjava/team-jeopardy')
let socket = null
const sfx = getGameSfx()
let prevSfxPhase = ''
let detachSfxUnlock

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

function bindSocket(roomId, isHost) {
  socket?.disconnect()
  socket = connectGameSocket({
    roomId,
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
    },
    onStatus: (status) => {
      socketStatus.value = status
    }
  })
}

async function onCreate(form) {
  error.value = ''
  busy.value = true
  try {
    const result = await createRoom({
      hostName: form.hostName,
      title: form.title
    })
    session.roomId = result.snapshot.roomId
    session.code = result.snapshot.code
    session.playerId = result.hostPlayerId
    session.hostName = result.hostName
    session.displayName = result.hostName
    session.isHost = true
    session.admitted = true
    snapshot.value = result.snapshot
    bindSocket(session.roomId, true)
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
    session.roomId = result.snapshot.roomId
    session.code = result.snapshot.code
    session.playerId = result.playerId
    session.displayName = form.displayName
    session.isHost = result.snapshot.hostPlayerId === result.playerId
    session.admitted = false
    snapshot.value = result.snapshot
    bindSocket(session.roomId, session.isHost)
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
      boardTitle: snapshot.value?.title || `${sampleType} Team Jeopardy`,
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

// Display mode can attach to an existing room via query params
async function bootDisplay() {
  const room = params.get('room')
  const code = params.get('code')
  if (!room && !code) return
  try {
    const { getRoom, getRoomByCode } = await import('./api')
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

if (viewMode.value === 'display') {
  bootDisplay()
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

watch(
  () => [phase.value, snapshot.value?.revision, snapshot.value?.activeClue?.dailyDouble],
  () => {
    const next = phase.value
    const event = sfxEventForTransition(prevSfxPhase, next, {
      dailyDouble: !!snapshot.value?.activeClue?.dailyDouble
    })
    if (event) sfx.play(event)
    prevSfxPhase = next
  }
)

onMounted(() => {
  detachSfxUnlock = sfx.attachUnlock()
})

onBeforeUnmount(() => {
  socket?.disconnect()
  detachSfxUnlock?.()
})
</script>

<template>
  <SharedDisplay v-if="viewMode === 'display'" :snapshot="snapshot" />

  <div v-else-if="viewMode === 'admin'" class="shell">
    <header class="top">
      <div>
        <p class="eyebrow">SQLite · question bank</p>
        <h1 class="brand">Team Jeopardy</h1>
      </div>
    </header>
    <QuestionBankAdmin @back="leaveAdmin" />
  </div>

  <div v-else class="shell">
    <header class="top">
      <div>
        <p class="eyebrow">Moderator · lobby · live buzzers</p>
        <h1 class="brand">Team Jeopardy</h1>
      </div>
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
      </div>
    </header>

    <p v-if="error" class="error">{{ error }}</p>

    <LobbyView
      v-if="!session.roomId"
      :busy="busy"
      :health="health"
      @create="onCreate"
      @join="onJoin"
      @open-admin="openQuestionBankAdmin"
    />

    <template v-else>
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
        :default-repo="defaultRepo"
        @ingest="onIngest"
        @ingest-github="onIngestGithub"
        @ingest-pulls="onIngestPulls"
        @browse-github="onBrowseGithub"
        @load-saved="onLoadSavedBoard"
        @open-admin="openQuestionBankAdmin"
        @admit="(playerId) => runAction('ADMIT_PLAYER', { playerId })"
        @admit-all="runAction('ADMIT_ALL')"
        @start="runAction('START')"
        @open-display="openDisplay"
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

.eyebrow {
  margin: 0 0 0.2rem;
  text-transform: uppercase;
  letter-spacing: 0.18em;
  font-size: 0.72rem;
  color: var(--gold);
}

.brand {
  font-size: clamp(2.6rem, 7vw, 4.4rem);
  line-height: 0.9;
}

.meta {
  display: flex;
  gap: 1rem;
  padding: 0.7rem 1rem;
  border-radius: 14px;
  background: rgba(6, 16, 34, 0.55);
  border: 1px solid rgba(244, 247, 255, 0.08);
}

.meta > div {
  display: grid;
  gap: 0.12rem;
  min-width: 4.5rem;
}

.meta strong.connected {
  color: var(--ok);
}

.meta strong.disconnected,
.meta strong.error {
  color: var(--danger);
}

.error {
  background: rgba(232, 93, 76, 0.15);
  border: 1px solid rgba(232, 93, 76, 0.45);
  color: #ffd2cc;
  padding: 0.75rem 1rem;
  border-radius: 12px;
  margin-bottom: 1rem;
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
