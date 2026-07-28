<script setup>
defineProps({
  code: String,
  displayName: String,
  teamName: String,
  phase: String
})
</script>

<template>
  <section class="waiting">
    <p class="kicker">Lobby</p>
    <h2>Waiting for the moderator</h2>
    <p class="lede">
      You’re in room <strong>{{ code }}</strong> as <strong>{{ displayName }}</strong>
      <template v-if="teamName"> on <strong>{{ teamName }}</strong></template>.
      The host will admit you before the game starts.
    </p>
    <div class="pulse" aria-hidden="true"></div>
    <p class="muted status">
      {{ phase === 'LOBBY' ? 'Lobby open — hang tight.' : 'Game in progress — waiting for admission.' }}
    </p>
  </section>
</template>

<style scoped>
.waiting {
  min-height: 52vh;
  display: grid;
  align-content: center;
  justify-items: start;
  gap: 0.65rem;
  padding: 2rem 0;
  max-width: 36rem;
}

.kicker {
  margin: 0;
  text-transform: uppercase;
  letter-spacing: 0.16em;
  font-size: 0.72rem;
  color: var(--gold);
}

h2 {
  font-size: clamp(2.2rem, 6vw, 3.4rem);
  line-height: 0.95;
}

.lede {
  margin: 0;
  font-size: 1.1rem;
  line-height: 1.5;
  color: #d7e2f8;
}

.pulse {
  width: 3.2rem;
  height: 3.2rem;
  margin: 0.75rem 0 0.25rem;
  border-radius: 50%;
  border: 3px solid rgba(240, 208, 96, 0.35);
  box-shadow: 0 0 0 0 rgba(240, 208, 96, 0.35);
  animation: ping 1.6s ease infinite;
}

.status {
  margin: 0;
}

@keyframes ping {
  0% {
    box-shadow: 0 0 0 0 rgba(240, 208, 96, 0.4);
  }
  70% {
    box-shadow: 0 0 0 16px rgba(240, 208, 96, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(240, 208, 96, 0);
  }
}
</style>
