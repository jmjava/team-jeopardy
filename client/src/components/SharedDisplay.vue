<script setup>
import { computed } from 'vue'
import Scorebar from './Scorebar.vue'
import BoardView from './BoardView.vue'
import ClueStage from './ClueStage.vue'

const props = defineProps({
  snapshot: Object
})

const phase = computed(() => props.snapshot?.phase || 'LOBBY')
const clue = computed(() => props.snapshot?.activeClue)
const showBoard = computed(() =>
  ['BOARD', 'FINISHED', 'LOBBY'].includes(phase.value) || !clue.value
)
const showClue = computed(() =>
  ['HOST_PREVIEW', 'CLUE_OPEN', 'BUZZ_LOCKED', 'ANSWER_REVEALED'].includes(phase.value) && !!clue.value
)
</script>

<template>
  <section class="display">
    <header class="display-top">
      <div>
        <p class="kicker">Shared screen</p>
        <h1>{{ snapshot?.title || 'Team Jeopardy' }}</h1>
      </div>
      <div class="code" v-if="snapshot?.code">
        <span class="muted">Room</span>
        <strong>{{ snapshot.code }}</strong>
      </div>
    </header>

    <Scorebar
      :teams="snapshot?.teams || []"
      :phase="phase"
      :buzzed-team-id="clue?.buzzedTeamId"
      compact
    />

    <BoardView
      v-if="showBoard"
      :board="snapshot?.board"
      :cells="snapshot?.cells || []"
      :is-host="false"
      :finished="phase === 'FINISHED'"
      display
    />

    <ClueStage
      v-else-if="showClue"
      mode="display"
      :clue="clue"
      :phase="phase"
      :is-host="false"
      :can-buzz="false"
    />

    <p v-else class="lobby-msg muted">
      Waiting for the moderator to start the game…
    </p>
  </section>
</template>

<style scoped>
.display {
  width: min(1280px, calc(100% - 2rem));
  margin: 0 auto;
  padding: 1.25rem 0 2rem;
  display: grid;
  gap: 0.85rem;
  min-height: 100vh;
  align-content: start;
}

.display-top {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: end;
}

.kicker {
  margin: 0 0 0.2rem;
  text-transform: uppercase;
  letter-spacing: 0.16em;
  font-size: 0.72rem;
  color: var(--gold);
}

h1 {
  font-size: clamp(2.2rem, 5vw, 3.4rem);
  line-height: 0.92;
}

.code {
  display: grid;
  gap: 0.1rem;
  text-align: right;
}

.code strong {
  font-family: "Bebas Neue", sans-serif;
  font-size: 1.8rem;
  letter-spacing: 0.12em;
  color: var(--gold);
}

.lobby-msg {
  margin: 3rem 0;
  text-align: center;
  font-size: 1.15rem;
}

@media (max-width: 700px) {
  .display-top {
    flex-direction: column;
    align-items: stretch;
  }
  .code {
    text-align: left;
  }
}
</style>
