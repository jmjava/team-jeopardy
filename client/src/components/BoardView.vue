<script setup>
import { computed } from 'vue'

const props = defineProps({
  board: Object,
  cells: {
    type: Array,
    default: () => []
  },
  isHost: Boolean,
  finished: Boolean,
  display: Boolean
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
  <section class="board-wrap" :class="{ display }">
    <div class="heading" v-if="!display">
      <h2>{{ board?.title || 'Board' }}</h2>
      <p class="muted" v-if="isHost && !finished">Select a clue — you’ll see it before players.</p>
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
          :class="{ answered: cellMap.get(clue.id)?.answered }"
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
  margin-bottom: 0.95rem;
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
  gap: 0.5rem;
}

.category {
  display: grid;
  gap: 0.5rem;
}

.cat-title,
.cell {
  background: linear-gradient(180deg, #1a6ae0, var(--board));
  border: 3px solid #071833;
  box-shadow: inset 0 0 0 2px rgba(255, 255, 255, 0.08), var(--shadow);
  min-height: 5rem;
  display: grid;
  place-items: center;
  text-align: center;
  padding: 0.55rem;
}

.display .cat-title,
.display .cell {
  min-height: 5.6rem;
}

.cat-title {
  font-family: "Bebas Neue", sans-serif;
  letter-spacing: 0.05em;
  font-size: clamp(0.95rem, 1.6vw, 1.2rem);
  color: white;
  min-height: 4.2rem;
  line-height: 1.15;
}

.cell {
  position: relative;
  color: var(--gold);
  font-family: "Bebas Neue", sans-serif;
  font-size: clamp(1.55rem, 3vw, 2.15rem);
  border-radius: 0;
}

.cell:disabled {
  opacity: 1;
}

.cell.answered {
  background: #0a274f;
  color: transparent;
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
