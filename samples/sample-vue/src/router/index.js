import { createRouter, createWebHistory } from 'vue-router'
import ScoreCard from '../components/ScoreCard.vue'

const routes = [
  { path: '/', name: 'home', component: ScoreCard },
  { path: '/board', name: 'board', component: ScoreCard }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
