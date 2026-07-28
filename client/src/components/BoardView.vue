<script setup>
import { computed } from 'vue'

const props = defineProps({
  board: Object,
  cells: {
    type: Array,
    default: () => []
  },
  isHost: Boolean,
  finished: Boolean
})

const emit = defineEmits(['select'])

const cellMap = computed(() => {
  const map = new Map()
  for (const cell of props.cells) {
    map.set(cell.clueId, cell)
  }
  return map
})

const categories = computed(() => props.board?.categories || [])
</script>

<template>
  <section class="board-wrap">
    <div class="heading">
      <h2>{{ board?.title || 'Board' }}</h2>
      <p class="muted" v-if="board?.graphDigest">
        skgraph digest · {{ board.graphDigest.nodes }} nodes ·
        {{ board.graphDigest.edges }} edges ·
        {{ board.graphDigest.types }} types
      </p>
      <p v-if="finished" class="done">Game finished</p>
    </div>

    <div
      class="board"
      :style="{ '--cols': Math.max(categories.length, 1) }"
    >
      <div
        v-for="category in categories"
        :key="category.id"
        class="category"
      >
        <div class="cat-title">{{ category.title }}</div>
        <button
          v-for="clue in category.clues"
          :key="clue.id"
          class="cell"
          :class="{ answered: cellMap.get(clue.id)?.answered, dd: clue.dailyDouble }"
          :disabled="!isHost || finished || cellMap.get(clue.id)?.answered"
          @click="emit('select', clue.id)"
        >
          <span v-if="!cellMap.get(clue.id)?.answered">${{ clue.value }}</span>
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.board-wrap {
  animation: board-in 420ms ease both;
}

.heading {
  margin-bottom: 0.9rem;
}

.heading h2 {
  font-size: clamp(1.8rem, 4vw, 2.4rem);
}

.done {
  color: var(--gold);
  font-weight: 700;
}

.board {
  display: grid;
  grid-template-columns: repeat(var(--cols), minmax(0, 1fr));
  gap: 0.55rem;
}

.category {
  display: grid;
  gap: 0.55rem;
}

.cat-title,
.cell {
  background: linear-gradient(180deg, #1760d6, var(--board));
  border: 3px solid #071833;
  box-shadow: inset 0 0 0 2px rgba(255, 255, 255, 0.08), var(--shadow);
  min-height: 5.2rem;
  display: grid;
  place-items: center;
  text-align: center;
  padding: 0.6rem;
}

.cat-title {
  font-family: "Bebas Neue", sans-serif;
  letter-spacing: 0.06em;
  font-size: 1.15rem;
  color: white;
  min-height: 4.4rem;
}

.cell {
  color: var(--gold);
  font-family: "Bebas Neue", sans-serif;
  font-size: clamp(1.6rem, 3vw, 2.2rem);
  border-radius: 0;
}

.cell:disabled {
  opacity: 1;
}

.cell.answered {
  background: #0a274f;
  color: transparent;
}

.cell.dd:not(.answered)::after {
  content: "DD";
  position: absolute;
  font-size: 0.7rem;
  color: #fff;
}

.cell {
  position: relative;
}

@keyframes board-in {
  from {
    opacity: 0;
    transform: scale(0.985);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

@media (max-width: 900px) {
  .board {
    grid-template-columns: 1fr;
  }
}
</style>
