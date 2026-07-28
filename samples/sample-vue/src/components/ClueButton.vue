<template>
  <button class="clue" @click="$emit('select', value)">${{ value }}</button>
</template>

<script setup>
defineProps({
  value: {
    type: Number,
    default: 200
  }
})

defineEmits(['select'])
</script>
