<script setup>
defineProps({
  clue: Object
})

defineEmits(['open', 'reveal', 'back'])
</script>

<template>
  <section class="preview" v-if="clue">
    <div class="meta">
      <span>{{ clue.categoryTitle }}</span>
      <strong>${{ clue.value }}</strong>
      <em v-if="clue.dailyDouble">Daily Double</em>
    </div>

    <div class="layout">
      <article class="prompt-pane">
        <p class="label">Clue — read this first</p>
        <pre>{{ clue.prompt }}</pre>
        <p v-if="clue.sourcePath" class="muted source">{{ clue.sourcePath }}</p>
      </article>

      <aside class="answer-pane">
        <p class="label">Correct response</p>
        <h3>{{ clue.response }}</h3>
        <p class="muted">{{ clue.explanation }}</p>
        <p class="note">Players only see category and value until you open buzzers.</p>
      </aside>
    </div>

    <div class="actions">
      <button class="ok large" @click="$emit('open')">Show clue &amp; open buzzers</button>
      <button class="secondary" @click="$emit('reveal')">Skip to reveal</button>
      <button class="secondary" @click="$emit('back')">Back to board</button>
    </div>
  </section>
</template>

<style scoped>
.preview {
  display: grid;
  gap: 1.25rem;
  animation: rise 280ms ease both;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: baseline;
  font-family: "Bebas Neue", sans-serif;
  letter-spacing: 0.05em;
}

.meta span {
  font-size: 1.5rem;
}

.meta strong {
  color: var(--gold);
  font-size: 2.2rem;
}

.meta em {
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

.layout {
  display: grid;
  grid-template-columns: 1.55fr 1fr;
  gap: 1rem;
  align-items: stretch;
}

.prompt-pane,
.answer-pane {
  border-radius: 18px;
  padding: 1.35rem 1.4rem;
  border: 1px solid rgba(244, 247, 255, 0.08);
}

.prompt-pane {
  background: rgba(12, 28, 58, 0.78);
  min-height: 16rem;
}

.answer-pane {
  background: rgba(62, 207, 142, 0.1);
  border-color: rgba(62, 207, 142, 0.28);
}

.label {
  margin: 0 0 0.65rem;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  font-size: 0.72rem;
  color: var(--muted);
}

.prompt-pane pre {
  margin: 0;
  white-space: pre-wrap;
  font-family: "Source Sans 3", sans-serif;
  font-size: clamp(1.25rem, 2.4vw, 1.65rem);
  line-height: 1.45;
}

.answer-pane h3 {
  margin: 0 0 0.5rem;
  font-size: clamp(1.4rem, 2.5vw, 1.9rem);
  color: #b9f5d4;
}

.note {
  margin: 1rem 0 0;
  font-size: 0.9rem;
  color: var(--muted);
}

.source {
  margin: 1rem 0 0;
  font-size: 0.85rem;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.65rem;
}

.large {
  font-size: 1.05rem;
  padding: 0.9rem 1.35rem;
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

@media (max-width: 860px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
