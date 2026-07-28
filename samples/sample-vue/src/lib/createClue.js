/**
 * Plain JS factory + tiny EventEmitter-style bus (not Vue-specific).
 */

export function createClue(category, value) {
  return { category, value, answered: false }
}

export function createEventBus() {
  const listeners = new Map()
  return {
    on(event, handler) {
      const list = listeners.get(event) || []
      list.push(handler)
      listeners.set(event, list)
    },
    off(event, handler) {
      const list = listeners.get(event) || []
      listeners.set(
        event,
        list.filter((h) => h !== handler)
      )
    },
    emit(event, payload) {
      for (const handler of listeners.get(event) || []) {
        handler(payload)
      }
    }
  }
}

export const sharedBus = createEventBus()
