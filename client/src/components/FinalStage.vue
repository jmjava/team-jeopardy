<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  snapshot: Object,
  phase: String,
  isHost: Boolean,
  isDisplay: Boolean,
  playerId: String,
  teamId: String
})

const emit = defineEmits([
  'start-wager',
  'start-writing',
  'set-wager',
  'set-answer',
  'end-writing',
  'reveal',
  'judge'
])

const wagerDraft = ref(5)
const answerDraft = ref('')

const round = computed(() => props.snapshot?.finalRound)
const entries = computed(() => round.value?.entries || [])
const myEntry = computed(() => entries.value.find((e) => e.teamId === props.teamId))
const myTeam = computed(() =>
  (props.snapshot?.teams || []).find((t) => t.id === props.teamId)
)
const maxWager = computed(() => {
  const score = myTeam.value?.score ?? 0
  return score < 5 ? 1000 : score
})
const writingLeft = computed(() => {
  const ends = round.value?.writingEndsAt
  if (!ends) return null
  const ms = new Date(ends).getTime() - Date.now()
  return Math.max(0, Math.ceil(ms / 1000))
})

const showPrompt = computed(() =>
  ['FINAL_WRITING', 'FINAL_LOCKED', 'FINAL_REVEALED'].includes(props.phase) && !!round.value?.prompt
)
const showResponse = computed(() => props.phase === 'FINAL_REVEALED' && !!round.value?.response)
const lockedBanner = computed(
  () => props.phase === 'FINAL_LOCKED' || (props.phase === 'FINAL_WRITING' && entries.value.every((e) => e.answerLocked))
)

watch(
  () => myEntry.value?.wager,
  (value) => {
    if (typeof value === 'number') wagerDraft.value = value
  }
)
watch(
  () => myEntry.value?.answer,
  (value) => {
    if (typeof value === 'string') answerDraft.value = value
  }
)

function submitWager() {
  emit('set-wager', Number(wagerDraft.value))
}

function submitAnswer() {
  emit('set-answer', answerDraft.value)
}
</script>

<template>
  <section class="final" :class="{ display: isDisplay }" data-testid="final-stage" v-if="round">
    <p class="kicker">Final Jeopardy</p>
    <h2 data-testid="final-category">{{ round.categoryTitle || 'Final Jeopardy' }}</h2>

    <p v-if="phase === 'FINAL_CATEGORY'" class="muted">
      Category only — wagers come next. The clue stays hidden.
    </p>

    <article v-if="showPrompt" class="prompt" data-testid="final-prompt">
      <pre>{{ round.prompt }}</pre>
    </article>
    <p v-else-if="phase !== 'FINAL_CATEGORY'" class="muted">
      Clue is hidden until every team locks a wager and the host opens writing time.
    </p>

    <div v-if="showResponse" class="answer" data-testid="final-official">
      <p class="kicker">Official response</p>
      <h3>{{ round.response }}</h3>
      <p class="muted">{{ round.explanation }}</p>
    </div>

    <p
      v-if="lockedBanner || phase === 'FINAL_LOCKED'"
      class="locked-banner"
      data-testid="answers-locked"
    >
      Answers locked
    </p>

    <p v-if="phase === 'FINAL_WRITING' && writingLeft !== null" class="timer" data-testid="final-timer">
      Writing time · {{ writingLeft }}s
    </p>

    <div
      v-if="!isHost && !isDisplay && phase === 'FINAL_WAGER' && teamId"
      class="form"
    >
      <label>
        Your wager (${{ 5 }}–${{ maxWager }})
        <input
          v-model.number="wagerDraft"
          type="number"
          min="5"
          :max="maxWager"
          data-testid="wager-input"
        />
      </label>
      <button
        class="ok"
        data-testid="lock-wager"
        :disabled="myEntry?.wagerLocked"
        @click="submitWager"
      >
        {{ myEntry?.wagerLocked ? 'Wager locked' : 'Lock wager' }}
      </button>
    </div>

    <div
      v-if="!isHost && !isDisplay && phase === 'FINAL_WRITING' && teamId"
      class="form"
    >
      <label>
        Your written answer
        <textarea
          v-model="answerDraft"
          rows="3"
          data-testid="final-answer-input"
          :disabled="myEntry?.answerLocked"
        />
      </label>
      <button class="ok" data-testid="lock-answer" :disabled="myEntry?.answerLocked" @click="submitAnswer">
        {{ myEntry?.answerLocked ? 'Answer saved' : 'Save answer' }}
      </button>
    </div>

    <ul class="entries">
      <li v-for="entry in entries" :key="entry.teamId" :style="{ '--team': entry.teamColor || 'var(--gold)' }">
        <strong>{{ entry.teamName }}</strong>
        <span v-if="entry.wagerLocked && (isHost || phase === 'FINAL_REVEALED' || entry.teamId === teamId)">
          ${{ entry.wager }}
        </span>
        <span v-else-if="entry.wagerLocked" class="muted">wager locked</span>
        <span v-else class="muted">wagering…</span>
        <span v-if="phase === 'FINAL_WRITING' || phase === 'FINAL_LOCKED' || phase === 'FINAL_REVEALED'">
          <template v-if="entry.answerLocked && !entry.answer && !isHost">answers locked</template>
          <template v-else-if="entry.answer && (isHost || phase === 'FINAL_REVEALED' || entry.teamId === teamId)">
            “{{ entry.answer }}”
          </template>
          <template v-else-if="entry.answerLocked">answers locked</template>
          <template v-else>writing…</template>
        </span>
        <span v-if="entry.correct === true" class="ok-text">Correct</span>
        <span v-if="entry.correct === false" class="bad-text">Incorrect</span>
        <template v-if="isHost && phase === 'FINAL_REVEALED' && entry.correct == null">
          <button
            class="ok"
            :data-testid="'judge-final-correct-' + entry.teamId"
            @click="emit('judge', { teamId: entry.teamId, correct: true })"
          >
            Correct
          </button>
          <button
            class="danger"
            :data-testid="'judge-final-incorrect-' + entry.teamId"
            @click="emit('judge', { teamId: entry.teamId, correct: false })"
          >
            Incorrect
          </button>
        </template>
      </li>
    </ul>

    <div v-if="isHost" class="controls">
      <button v-if="phase === 'FINAL_CATEGORY'" class="ok" data-testid="open-final-wagers" @click="emit('start-wager')">
        Open wagers
      </button>
      <button
        v-if="phase === 'FINAL_WAGER'"
        class="ok"
        data-testid="start-final-writing"
        @click="emit('start-writing')"
      >
        Show clue &amp; start writing (30s)
      </button>
      <button
        v-if="phase === 'FINAL_WRITING'"
        data-testid="end-writing"
        @click="emit('end-writing')"
      >
        End writing time
      </button>
      <button
        v-if="phase === 'FINAL_LOCKED'"
        class="ok"
        data-testid="reveal-final"
        @click="emit('reveal')"
      >
        Reveal official response
      </button>
    </div>
  </section>
</template>

<style scoped>
.final {
  display: grid;
  gap: 1rem;
  padding: 1.35rem 1.4rem 1.5rem;
  border-radius: 22px;
  background: rgba(10, 24, 48, 0.82);
  border: 1px solid rgba(244, 247, 255, 0.08);
}

.final.display {
  min-height: 52vh;
  text-align: center;
}

.kicker {
  margin: 0;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  font-size: 0.72rem;
  color: var(--gold);
}

h2 {
  margin: 0;
  font-size: clamp(2rem, 5vw, 3.2rem);
  line-height: 0.95;
}

.prompt pre {
  margin: 0;
  white-space: pre-wrap;
  font-family: "Source Sans 3", sans-serif;
  font-size: clamp(1.2rem, 2.6vw, 1.8rem);
}

.answer {
  padding: 1rem 1.15rem;
  border-radius: 14px;
  background: rgba(62, 207, 142, 0.12);
  border: 1px solid rgba(62, 207, 142, 0.35);
}

.locked-banner {
  margin: 0;
  font-weight: 700;
  color: var(--gold);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.form,
.controls {
  display: flex;
  flex-wrap: wrap;
  gap: 0.7rem;
  align-items: end;
}

.form label {
  display: grid;
  gap: 0.35rem;
  min-width: 16rem;
}

.entries {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 0.55rem;
}

.entries li {
  display: flex;
  flex-wrap: wrap;
  gap: 0.55rem 0.85rem;
  align-items: center;
  padding: 0.7rem 0.9rem;
  border-radius: 12px;
  background: color-mix(in srgb, var(--team) 16%, rgba(8, 18, 36, 0.92));
  border: 1px solid color-mix(in srgb, var(--team) 50%, transparent);
}

.ok-text {
  color: #b9f5d4;
  font-weight: 700;
}

.bad-text {
  color: #ffd2cc;
  font-weight: 700;
}
</style>
