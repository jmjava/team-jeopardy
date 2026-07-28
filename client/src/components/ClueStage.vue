<script setup>
import { computed, onMounted, onBeforeUnmount } from 'vue'

const props = defineProps({
  clue: Object,
  phase: String,
  isHost: Boolean,
  canBuzz: Boolean,
  mode: {
    type: String,
    default: 'player' // player | display | host
  }
})

const emit = defineEmits(['buzz', 'judge', 'reveal', 'back', 'open'])

const isPreview = computed(() => props.phase === 'HOST_PREVIEW')
const isOpen = computed(() => props.phase === 'CLUE_OPEN')
const isLocked = computed(() => props.phase === 'BUZZ_LOCKED')
const isRevealed = computed(() => props.phase === 'ANSWER_REVEALED')

function onKey(e) {
  if (e.code === 'Space' && props.canBuzz && isOpen.value) {
    e.preventDefault()
    emit('buzz')
  }
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <section class="stage" :class="[mode, phase]" v-if="clue">
    <header class="banner">
      <span class="cat">{{ clue.categoryTitle }}</span>
      <strong class="value">${{ clue.value }}</strong>
      <em v-if="clue.dailyDouble" class="dd">Daily Double</em>
    </header>

    <!-- Host preview teaser for everyone else / display -->
    <div v-if="isPreview && !isHost" class="teaser">
      <p class="kicker">Get ready</p>
      <h2>Host is reading the clue</h2>
      <p class="muted">Buzzers open when the clue appears.</p>
    </div>

    <article v-else-if="!isPreview || isHost" class="prompt">
      <pre v-if="clue.prompt">{{ clue.prompt }}</pre>
      <p v-else class="muted">Waiting for clue…</p>
      <p v-if="clue.sourcePath && isHost" class="muted source">{{ clue.sourcePath }}</p>
    </article>

    <div
      v-if="isLocked || clue.buzzedPlayerName"
      class="buzz-banner"
      :style="{ '--team': clue.buzzedTeamColor || 'var(--gold)' }"
    >
      <p class="kicker">First buzz</p>
      <h2>{{ clue.buzzedPlayerName }}</h2>
      <p v-if="clue.buzzedTeamName" class="team">{{ clue.buzzedTeamName }}</p>
    </div>

    <div v-if="clue.responseVisible" class="answer">
      <p class="kicker">Answer</p>
      <h3>{{ clue.response }}</h3>
      <p class="muted">{{ clue.explanation }}</p>
    </div>

    <div class="controls">
      <button
        v-if="canBuzz && isOpen"
        class="buzz"
        @click="emit('buzz')"
      >
        Buzz
        <span class="hint">Space</span>
      </button>

      <p v-if="canBuzz && isLocked" class="locked-msg muted">Buzzers locked</p>

      <template v-if="isHost">
        <button v-if="isPreview" class="ok" @click="emit('open')">
          Show clue &amp; open buzzers
        </button>
        <button v-if="isLocked" class="ok" @click="emit('judge', true)">Correct</button>
        <button v-if="isLocked" class="danger" @click="emit('judge', false)">Incorrect</button>
        <button
          v-if="!isRevealed"
          class="secondary"
          @click="emit('reveal')"
        >
          Reveal answer
        </button>
        <button v-if="isRevealed" @click="emit('back')">Back to board</button>
      </template>
    </div>
  </section>
</template>

<style scoped>
.stage {
  display: grid;
  gap: 1.15rem;
  padding: 1.35rem 1.4rem 1.5rem;
  border-radius: 22px;
  background: rgba(10, 24, 48, 0.82);
  border: 1px solid rgba(244, 247, 255, 0.08);
  box-shadow: var(--shadow);
  animation: rise 280ms ease both;
}

.stage.display {
  min-height: 58vh;
  align-content: center;
  text-align: center;
  padding: 2rem 1.75rem;
}

.banner {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: baseline;
  justify-content: center;
  font-family: "Bebas Neue", sans-serif;
  letter-spacing: 0.05em;
}

.stage:not(.display) .banner {
  justify-content: flex-start;
}

.cat {
  font-size: clamp(1.35rem, 3vw, 1.8rem);
}

.value {
  color: var(--gold);
  font-size: clamp(2rem, 5vw, 2.8rem);
  line-height: 1;
}

.dd {
  font-style: normal;
  font-family: "Source Sans 3", sans-serif;
  font-size: 0.72rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  background: var(--danger);
  color: #fff;
  padding: 0.25rem 0.55rem;
  border-radius: 999px;
}

.teaser {
  padding: 2rem 0.5rem;
}

.teaser h2,
.buzz-banner h2 {
  margin: 0.2rem 0;
  font-size: clamp(2rem, 5vw, 3.2rem);
  line-height: 0.95;
}

.kicker {
  margin: 0;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  font-size: 0.72rem;
  color: var(--muted);
}

.prompt pre {
  margin: 0;
  white-space: pre-wrap;
  font-family: "Source Sans 3", sans-serif;
  font-size: clamp(1.25rem, 2.8vw, 1.85rem);
  line-height: 1.45;
}

.stage.display .prompt pre {
  max-width: 48rem;
  margin: 0 auto;
  font-size: clamp(1.5rem, 3.5vw, 2.35rem);
}

.source {
  margin: 0.85rem 0 0;
  font-size: 0.85rem;
}

.buzz-banner {
  margin: 0.25rem 0;
  padding: 1.1rem 1.25rem;
  border-radius: 16px;
  background: color-mix(in srgb, var(--team) 18%, rgba(8, 18, 36, 0.92));
  border: 2px solid color-mix(in srgb, var(--team) 65%, white);
  animation: pop 320ms ease both;
}

.buzz-banner .team {
  margin: 0.15rem 0 0;
  color: color-mix(in srgb, var(--team) 80%, white);
  font-weight: 700;
}

.answer {
  padding: 1rem 1.15rem;
  border-radius: 14px;
  background: rgba(62, 207, 142, 0.12);
  border: 1px solid rgba(62, 207, 142, 0.35);
}

.answer h3 {
  margin: 0.25rem 0 0.4rem;
  font-size: clamp(1.4rem, 3vw, 2rem);
  color: #b9f5d4;
}

.controls {
  display: flex;
  flex-wrap: wrap;
  gap: 0.7rem;
  align-items: center;
  justify-content: center;
}

.stage:not(.display) .controls {
  justify-content: flex-start;
}

.buzz {
  width: min(16rem, 100%);
  min-height: 5.5rem;
  border-radius: 999px;
  font-size: 1.55rem;
  letter-spacing: 0.04em;
  background: radial-gradient(circle at 30% 28%, #ffb4a8, var(--danger) 62%);
  color: white;
  box-shadow: 0 14px 36px rgba(232, 93, 76, 0.35);
  animation: pulse 1.15s ease infinite;
  display: grid;
  place-items: center;
  gap: 0.15rem;
}

.hint {
  display: block;
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  opacity: 0.85;
}

.locked-msg {
  margin: 0;
}

@keyframes pulse {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.03);
  }
}

@keyframes pop {
  from {
    opacity: 0;
    transform: scale(0.96);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
