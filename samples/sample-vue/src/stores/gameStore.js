import { defineStore } from 'pinia'

export const useGameStore = defineStore('game', {
  state: () => ({
    roomCode: ''
  }),
  actions: {
    setRoom(code) {
      this.roomCode = code
    }
  }
})
