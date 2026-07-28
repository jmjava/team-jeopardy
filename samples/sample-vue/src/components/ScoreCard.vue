<template>
  <section class="score">
    <p>Score: {{ score }}</p>
    <button @click="$emit('reset')">Reset</button>
  </section>
</template>

<script setup>
defineProps({
  score: {
    type: Number,
    required: true
  }
})

defineEmits(['reset'])
</script>
