<script setup>
import { reactive } from 'vue'

defineProps({
  busy: Boolean,
  health: Object
})

const emit = defineEmits(['create', 'join'])

const createForm = reactive({
  hostName: 'Host',
  title: 'skgraph Team Jeopardy'
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
      <h2>Code-graph Jeopardy for distributed teams</h2>
      <p>
        Host ingests <strong>Maven</strong>, <strong>Gradle</strong>, or <strong>Vue</strong>
        projects into a shared code graph, auto-builds a Jeopardy board, then teammates
        buzz in over a bidirectional WebSocket channel from anywhere.
      </p>
      <p class="muted" v-if="health">
        Engine {{ health.engine }} · supports {{ (health.supported || []).join(', ') }}
      </p>
    </div>

    <div class="cards">
      <form class="card" @submit.prevent="emit('create', { ...createForm })">
        <h3>Create room</h3>
        <label>
          Host name
          <input v-model="createForm.hostName" required maxlength="40" />
        </label>
        <label>
          Board title
          <input v-model="createForm.title" maxlength="80" />
        </label>
        <button type="submit" :disabled="busy">Create &amp; host</button>
      </form>

      <form class="card" @submit.prevent="emit('join', { ...joinForm })">
        <h3>Join room</h3>
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
        <button type="submit" class="secondary" :disabled="busy">Join game</button>
      </form>
    </div>
  </section>
</template>

<style scoped>
.hero {
  display: grid;
  gap: 1.25rem;
}

.copy {
  max-width: 46rem;
  animation: rise 500ms ease both;
}

.copy h2 {
  font-size: clamp(1.8rem, 4vw, 2.6rem);
  margin-bottom: 0.5rem;
}

.copy p {
  font-size: 1.05rem;
  line-height: 1.5;
  color: #d7e2f8;
}

.cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
}

.card {
  background: var(--panel);
  border: 1px solid var(--fog);
  border-radius: 18px;
  padding: 1.25rem;
  display: grid;
  gap: 0.75rem;
  box-shadow: var(--shadow);
  animation: rise 650ms ease both;
}

.card:nth-child(2) {
  animation-delay: 80ms;
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
