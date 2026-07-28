<script setup>
defineProps({
  teams: {
    type: Array,
    default: () => []
  },
  phase: String,
  compact: Boolean,
  buzzedTeamId: String
})

const phaseLabel = {
  LOBBY: 'Lobby',
  BOARD: 'Board',
  HOST_PREVIEW: 'Host reading',
  CLUE_OPEN: 'Buzzers open',
  BUZZ_LOCKED: 'Buzz locked',
  ANSWER_REVEALED: 'Answer',
  FINISHED: 'Finished'
}
</script>

<template>
  <section class="scorebar" :class="{ compact }" v-if="teams.length">
    <div class="phase">{{ phaseLabel[phase] || phase }}</div>
    <div class="teams">
      <div
        v-for="team in teams"
        :key="team.id"
        class="team"
        :class="{ buzzed: buzzedTeamId && team.id === buzzedTeamId }"
        :style="{ '--team': team.color }"
      >
        <span class="name">{{ team.name }}</span>
        <strong>${{ team.score }}</strong>
      </div>
    </div>
  </section>
</template>

<style scoped>
.scorebar {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 0.85rem 1.25rem;
  align-items: center;
  margin-bottom: 1.25rem;
  padding: 0.65rem 0.85rem;
  border-radius: 16px;
  background: rgba(6, 16, 34, 0.55);
  border: 1px solid rgba(244, 247, 255, 0.08);
}

.scorebar.compact {
  margin-bottom: 0.85rem;
  padding: 0.5rem 0.7rem;
}

.phase {
  text-transform: uppercase;
  letter-spacing: 0.14em;
  font-size: 0.72rem;
  color: var(--muted);
  white-space: nowrap;
}

.teams {
  display: flex;
  flex-wrap: wrap;
  gap: 0.55rem;
  justify-content: flex-end;
}

.team {
  min-width: 7.5rem;
  padding: 0.55rem 0.85rem;
  border-radius: 12px;
  background: color-mix(in srgb, var(--team) 16%, rgba(8, 18, 36, 0.92));
  border: 1px solid color-mix(in srgb, var(--team) 50%, transparent);
  display: flex;
  justify-content: space-between;
  gap: 0.85rem;
  align-items: baseline;
  transition: transform 180ms ease, box-shadow 180ms ease;
}

.team.buzzed {
  transform: translateY(-2px);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--team) 70%, white), 0 12px 28px rgba(0, 0, 0, 0.35);
}

.name {
  font-size: 0.95rem;
}

.team strong {
  color: var(--gold);
  font-family: "Bebas Neue", sans-serif;
  font-size: 1.45rem;
  letter-spacing: 0.04em;
  line-height: 1;
}

@media (max-width: 700px) {
  .scorebar {
    grid-template-columns: 1fr;
  }
  .teams {
    justify-content: stretch;
  }
  .team {
    flex: 1 1 8rem;
  }
}
</style>
