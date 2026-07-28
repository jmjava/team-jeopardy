<template>
  <main class="app">
    <h1>{{ title }}</h1>
    <ScoreCard :score="score" @reset="score = 0" />
    <BoardPanel @pick="score += $event" />
    <RouterView />
  </main>
</template>

<script setup>
import { ref } from 'vue'
import ScoreCard from './components/ScoreCard.vue'
import BoardPanel from './components/BoardPanel.vue'

const title = ref('Sample Vue Jeopardy')
const score = ref(200)
</script>
