<template>
  <section class="board">
    <ClueButton :value="200" @select="$emit('pick', $event)" />
    <ClueButton :value="400" @select="$emit('pick', $event)" />
  </section>
</template>

<script setup>
import ClueButton from './ClueButton.vue'

defineEmits(['pick'])
</script>
