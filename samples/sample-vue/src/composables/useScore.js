import { ref } from 'vue'

export function useScore(initial = 0) {
  const score = ref(initial)
  function add(value) {
    score.value += value
  }
  return { score, add }
}
