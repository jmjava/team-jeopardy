<script setup>
import { reactive } from 'vue'

defineProps({
  busy: Boolean,
  health: Object
})

const emit = defineEmits(['create', 'join'])

const createForm = reactive({
  hostName: 'Host',
  title: 'Team Jeopardy'
})

const joinForm = reactive({
  code: '',
  displayName: '',
  teamName: ''
})
</script>

<template>
  <section class="hero">
    <div class="copy">
      <h2>Team Jeopardy for distributed coders &amp; QA</h2>
      <p>
        The moderator builds a board from your codebase, admits players from the lobby,
        then runs clues with a host preview and live buzzers on a shared screen.
      </p>
      <p class="muted" v-if="health">
        Engine {{ health.engine }} · {{ (health.supported || []).join(' · ') }}
      </p>
    </div>

    <div class="cards">
      <form class="panel" @submit.prevent="emit('create', { ...createForm })">
        <h3>Moderator</h3>
        <p class="muted tiny">Create a room and manage admissions.</p>
        <label>
          Host name
          <input v-model="createForm.hostName" required maxlength="40" />
        </label>
        <label>
          Board title
          <input v-model="createForm.title" maxlength="80" />
        </label>
        <button type="submit" :disabled="busy">Create room</button>
      </form>

      <form class="panel" @submit.prevent="emit('join', { ...joinForm })">
        <h3>Player</h3>
        <p class="muted tiny">Join the lobby — the host lets you in.</p>
        <label>
          Room code
          <input v-model="joinForm.code" required maxlength="8" placeholder="ABC123" />
        </label>
        <label>
          Display name
          <input v-model="joinForm.displayName" required maxlength="40" />
        </label>
        <label>
          Team name
          <input v-model="joinForm.teamName" required maxlength="40" placeholder="Blue Owls" />
        </label>
        <button type="submit" class="secondary" :disabled="busy">Join lobby</button>
      </form>
    </div>
  </section>
</template>

<style scoped>
.hero {
  display: grid;
  gap: 1.5rem;
  padding-top: 0.5rem;
}

.copy {
  max-width: 40rem;
  animation: rise 480ms ease both;
}

.copy h2 {
  font-size: clamp(2rem, 5vw, 3rem);
  line-height: 0.95;
  margin-bottom: 0.65rem;
}

.copy p {
  margin: 0;
  font-size: 1.08rem;
  line-height: 1.5;
  color: #d7e2f8;
}

.cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
}

.panel {
  background: rgba(12, 28, 58, 0.78);
  border: 1px solid rgba(244, 247, 255, 0.08);
  border-radius: 18px;
  padding: 1.25rem 1.3rem;
  display: grid;
  gap: 0.7rem;
  box-shadow: var(--shadow);
  animation: rise 620ms ease both;
}

.panel:nth-child(2) {
  animation-delay: 70ms;
}

.panel h3 {
  margin: 0;
  font-size: 1.55rem;
}

.tiny {
  margin: -0.15rem 0 0.15rem;
  font-size: 0.92rem;
}

label {
  display: grid;
  gap: 0.35rem;
  color: var(--muted);
  font-size: 0.92rem;
}

@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 800px) {
  .cards {
    grid-template-columns: 1fr;
  }
}
</style>
