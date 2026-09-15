<script setup>
import { appName, logoUrl } from '../theme'

defineProps({
  eyebrow: {
    type: String,
    default: ''
  },
  compact: Boolean
})
</script>

<template>
  <div class="brand-block" :class="{ compact }">
    <p v-if="eyebrow" class="eyebrow">{{ eyebrow }}</p>
    <div class="wordmark">
      <img v-if="logoUrl" class="logo" :src="logoUrl" :alt="appName" />
      <h1 class="brand">{{ appName }}</h1>
    </div>
  </div>
</template>

<style scoped>
.eyebrow {
  margin: 0 0 0.2rem;
  text-transform: uppercase;
  letter-spacing: 0.18em;
  font-size: 0.72rem;
  color: var(--gold);
}

.wordmark {
  display: flex;
  align-items: center;
  gap: 0.7rem;
}

.logo {
  width: 2.6rem;
  height: 2.6rem;
  object-fit: contain;
  flex: 0 0 auto;
}

.compact .logo {
  width: 2rem;
  height: 2rem;
}

.brand {
  font-size: clamp(2.6rem, 7vw, 4.4rem);
  line-height: 0.9;
}

.compact .brand {
  font-size: clamp(2.2rem, 5vw, 3.4rem);
}
</style>
