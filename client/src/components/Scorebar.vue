<script setup>
defineProps({
  teams: {
    type: Array,
    default: () => []
  },
  phase: String
})
</script>

<template>
  <section class="scorebar" v-if="teams.length">
    <div class="phase">{{ phase }}</div>
    <div
      v-for="team in teams"
      :key="team.id"
      class="team"
      :style="{ '--team': team.color }"
    >
      <span>{{ team.name }}</span>
      <strong>${{ team.score }}</strong>
    </div>
  </section>
</template>

<style scoped>
.scorebar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.6rem;
  margin-bottom: 1rem;
}

.phase {
  align-self: center;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  font-size: 0.75rem;
  color: var(--muted);
  margin-right: 0.4rem;
}

.team {
  min-width: 8rem;
  padding: 0.65rem 0.9rem;
  border-radius: 12px;
  background: color-mix(in srgb, var(--team) 18%, rgba(8, 18, 36, 0.9));
  border: 1px solid color-mix(in srgb, var(--team) 55%, transparent);
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
}

.team strong {
  color: var(--gold);
  font-family: "Bebas Neue", sans-serif;
  font-size: 1.4rem;
  letter-spacing: 0.04em;
}
</style>
