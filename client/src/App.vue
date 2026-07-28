<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { createRoom, getHealth, ingestBoard, joinRoom, postAction } from './api'
import { connectGameSocket } from './useGameSocket'
import LobbyView from './components/LobbyView.vue'
import BoardView from './components/BoardView.vue'
import ClueStage from './components/ClueStage.vue'
import Scorebar from './components/Scorebar.vue'

const session = reactive({
  roomId: '',
  code: '',
  playerId: '',
  hostName: '',
  displayName: '',
  isHost: false
})

const snapshot = ref(null)
const socketStatus = ref('idle')
const error = ref('')
const busy = ref(false)
const ingestSummary = ref(null)
const health = ref(null)
let socket = null

const phase = computed(() => snapshot.value?.phase || 'LOBBY')
const showClue = computed(() =>
  ['CLUE_OPEN', 'BUZZ_LOCKED', 'ANSWER_REVEALED'].includes(phase.value)
)

async function refreshHealth() {
  try {
    health.value = await getHealth()
  } catch {
    health.value = null
  }
}

refreshHealth()

function bindSocket(roomId) {
  socket?.disconnect()
  socket = connectGameSocket({
    roomId,
    onSnapshot: (next) => {
      snapshot.value = next
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
    snapshot.value = result.snapshot
    bindSocket(session.roomId)
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
    snapshot.value = result.snapshot
    bindSocket(session.roomId)
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

async function onIngest() {
  error.value = ''
  busy.value = true
  try {
    const result = await ingestBoard({
      roomId: session.roomId,
      playerId: session.playerId,
      useSample: true,
      boardTitle: snapshot.value?.title || 'skgraph Team Jeopardy'
    })
    snapshot.value = result.snapshot
    ingestSummary.value = result.ingestSummary
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
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

onBeforeUnmount(() => socket?.disconnect())
</script>

<template>
  <div class="shell">
    <header class="top">
      <div>
        <p class="eyebrow">skgraph → live board</p>
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
    />

    <template v-else>
      <Scorebar :teams="snapshot?.teams || []" :phase="phase" />

      <section v-if="phase === 'LOBBY'" class="lobby-panel">
        <div>
          <h2>Host lobby</h2>
          <p class="muted">
            Ingest the skgraph sample reactor (or any Maven reactor path on the server)
            to generate categories from modules, dependencies, Java AST, propositions, and OSGi.
          </p>
          <div class="actions">
            <button v-if="session.isHost" :disabled="busy" @click="onIngest">
              {{ busy ? 'Ingesting…' : 'Ingest sample-reactor via skgraph' }}
            </button>
            <button
              v-if="session.isHost && snapshot?.board"
              class="ok"
              :disabled="busy || !(snapshot?.teams?.length)"
              @click="runAction('START')"
            >
              Start game
            </button>
            <button
              v-if="!session.isHost"
              class="secondary"
              @click="runAction('CREATE_TEAM', { teamName: session.displayName + ' Team' })"
            >
              Ensure my team
            </button>
          </div>
          <pre v-if="ingestSummary" class="summary">{{ ingestSummary }}</pre>
        </div>
        <aside>
          <h3>Players</h3>
          <ul>
            <li v-for="player in snapshot?.players || []" :key="player.id">
              {{ player.displayName }}
              <span v-if="player.host" class="tag">host</span>
              <span v-if="player.teamId" class="tag">teamed</span>
            </li>
          </ul>
          <h3>Teams</h3>
          <ul>
            <li v-for="team in snapshot?.teams || []" :key="team.id">
              <i :style="{ background: team.color }"></i>
              {{ team.name }}
            </li>
          </ul>
        </aside>
      </section>

      <BoardView
        v-else-if="phase === 'BOARD' || phase === 'FINISHED'"
        :board="snapshot?.board"
        :cells="snapshot?.cells || []"
        :is-host="session.isHost"
        :finished="phase === 'FINISHED'"
        @select="(clueId) => runAction('SELECT_CLUE', { clueId })"
      />

      <ClueStage
        v-else-if="showClue"
        :clue="snapshot?.activeClue"
        :phase="phase"
        :is-host="session.isHost"
        :can-buzz="!session.isHost"
        @buzz="runAction('BUZZ')"
        @judge="(correct) => runAction('JUDGE', { correct })"
        @reveal="runAction('REVEAL')"
        @back="runAction('RETURN_BOARD')"
      />
    </template>
  </div>
</template>

<style scoped>
.shell {
  width: min(1180px, calc(100% - 2rem));
  margin: 0 auto;
  padding: 1.5rem 0 3rem;
}

.top {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: end;
  margin-bottom: 1.25rem;
}

.eyebrow {
  margin: 0 0 0.2rem;
  text-transform: uppercase;
  letter-spacing: 0.18em;
  font-size: 0.75rem;
  color: var(--gold);
}

.brand {
  font-size: clamp(2.8rem, 8vw, 4.8rem);
  line-height: 0.9;
}

.meta {
  display: flex;
  gap: 1rem;
  padding: 0.75rem 1rem;
  border-radius: 14px;
  background: var(--panel);
  box-shadow: var(--shadow);
}

.meta > div {
  display: grid;
  gap: 0.15rem;
  min-width: 5rem;
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
}

.lobby-panel {
  display: grid;
  grid-template-columns: 1.5fr 1fr;
  gap: 1rem;
}

.lobby-panel > div,
.lobby-panel > aside {
  background: var(--panel);
  border: 1px solid var(--fog);
  border-radius: 18px;
  padding: 1.25rem;
  box-shadow: var(--shadow);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-top: 1rem;
}

.summary {
  margin-top: 1rem;
  padding: 0.9rem;
  border-radius: 12px;
  background: rgba(0, 0, 0, 0.25);
  overflow: auto;
  font-size: 0.85rem;
}

ul {
  list-style: none;
  padding: 0;
  margin: 0.4rem 0 1rem;
}

li {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.35rem 0;
}

.tag {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  padding: 0.15rem 0.4rem;
  border-radius: 999px;
  background: rgba(240, 208, 96, 0.15);
  color: var(--gold);
}

li i {
  width: 0.7rem;
  height: 0.7rem;
  border-radius: 50%;
  display: inline-block;
}

@media (max-width: 800px) {
  .top,
  .lobby-panel,
  .meta {
    grid-template-columns: 1fr;
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
