<script setup>
defineProps({
  clue: Object,
  phase: String,
  isHost: Boolean,
  canBuzz: Boolean
})

defineEmits(['buzz', 'judge', 'reveal', 'back'])
</script>

<template>
  <section class="stage" v-if="clue">
    <div class="banner">
      <span>{{ clue.categoryTitle }}</span>
      <strong>${{ clue.value }}</strong>
      <em v-if="clue.dailyDouble">Daily Double</em>
    </div>

    <article class="prompt">
      <pre>{{ clue.prompt }}</pre>
      <p v-if="clue.sourcePath" class="muted">Source: {{ clue.sourcePath }}</p>
    </article>

    <div v-if="clue.responseVisible" class="answer">
      <h3>{{ clue.response }}</h3>
      <p class="muted">{{ clue.explanation }}</p>
    </div>

    <div v-if="clue.buzzedPlayerName" class="buzzed">
      Buzzed in: <strong>{{ clue.buzzedPlayerName }}</strong>
    </div>

    <div class="controls">
      <button
        v-if="canBuzz && phase === 'CLUE_OPEN'"
        class="buzz"
        @click="$emit('buzz')"
      >
        Buzz
      </button>

      <template v-if="isHost">
        <button
          v-if="phase === 'BUZZ_LOCKED'"
          class="ok"
          @click="$emit('judge', true)"
        >
          Correct
        </button>
        <button
          v-if="phase === 'BUZZ_LOCKED'"
          class="danger"
          @click="$emit('judge', false)"
        >
          Incorrect
        </button>
        <button
          v-if="phase !== 'ANSWER_REVEALED'"
          class="secondary"
          @click="$emit('reveal')"
        >
          Reveal answer
        </button>
        <button
          v-if="phase === 'ANSWER_REVEALED'"
          @click="$emit('back')"
        >
          Back to board
        </button>
      </template>
    </div>
  </section>
</template>

<style scoped>
.stage {
  background: var(--panel);
  border: 1px solid var(--fog);
  border-radius: 20px;
  padding: 1.25rem;
  box-shadow: var(--shadow);
  animation: pop 280ms ease both;
}

.banner {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: baseline;
  margin-bottom: 1rem;
  font-family: "Bebas Neue", sans-serif;
  letter-spacing: 0.05em;
}

.banner span {
  font-size: 1.4rem;
}

.banner strong {
  color: var(--gold);
  font-size: 2rem;
}

.banner em {
  font-style: normal;
  color: #fff;
  background: var(--danger);
  padding: 0.2rem 0.5rem;
  border-radius: 999px;
  font-family: "Source Sans 3", sans-serif;
  font-size: 0.75rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.prompt pre {
  white-space: pre-wrap;
  margin: 0;
  font-family: "Source Sans 3", sans-serif;
  font-size: 1.2rem;
  line-height: 1.45;
}

.answer {
  margin-top: 1rem;
  padding: 1rem;
  border-radius: 14px;
  background: rgba(62, 207, 142, 0.12);
  border: 1px solid rgba(62, 207, 142, 0.35);
}

.buzzed {
  margin-top: 1rem;
  color: var(--gold);
}

.controls {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-top: 1.25rem;
}

.buzz {
  min-width: 8rem;
  font-size: 1.2rem;
  background: radial-gradient(circle at 30% 30%, #ffb4a8, var(--danger));
  color: white;
  animation: pulse 1.1s ease infinite;
}

@keyframes pulse {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.04);
  }
}

@keyframes pop {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}
</style>
