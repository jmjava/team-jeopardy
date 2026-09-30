/**
 * Original game-show SFX (Web Audio oscillators). Not the copyrighted Jeopardy theme.
 *
 * Events: select, dailyDouble, open, think, buzz, correct, incorrect, reveal, board, finished
 */

const STORAGE_KEY = 'team-jeopardy-sfx-muted'
const THINK_START = new Set(['open', 'incorrect'])
const THINK_STOP = new Set(['buzz', 'correct', 'reveal', 'board', 'finished', 'select', 'dailyDouble'])

export function sfxEventForTransition(prevPhase, nextPhase, { dailyDouble = false } = {}) {
  if (!nextPhase || prevPhase === nextPhase) {
    return null
  }
  if (nextPhase === 'HOST_PREVIEW') {
    return dailyDouble ? 'dailyDouble' : 'select'
  }
  if (nextPhase === 'CLUE_OPEN') {
    if (prevPhase === 'BUZZ_LOCKED') return 'incorrect'
    return 'open'
  }
  if (nextPhase === 'BUZZ_LOCKED') return 'buzz'
  if (nextPhase === 'ANSWER_REVEALED') {
    return prevPhase === 'BUZZ_LOCKED' ? 'correct' : 'reveal'
  }
  if (nextPhase === 'BOARD' && prevPhase && prevPhase !== 'BOARD') return 'board'
  if (nextPhase === 'FINISHED') return 'finished'
  return null
}

function loadMuted() {
  try {
    return localStorage.getItem(STORAGE_KEY) === '1'
  } catch {
    return false
  }
}

function saveMuted(value) {
  try {
    localStorage.setItem(STORAGE_KEY, value ? '1' : '0')
  } catch {
    // ignore quota / private mode
  }
}

export function createGameSfx() {
  let ctx = null
  let muted = loadMuted()
  let unlocked = false
  let thinkTimer = null
  let thinkGain = null
  const listeners = new Set()

  function notify() {
    for (const fn of listeners) fn(muted)
  }

  function audio() {
    if (!ctx) {
      const Ctor = window.AudioContext || window.webkitAudioContext
      if (!Ctor) return null
      ctx = new Ctor()
    }
    return ctx
  }

  async function unlock() {
    const ac = audio()
    if (!ac) return
    if (ac.state === 'suspended') {
      try {
        await ac.resume()
      } catch {
        return
      }
    }
    unlocked = true
  }

  function attachUnlock() {
    const onFirst = () => {
      unlock()
      window.removeEventListener('pointerdown', onFirst)
      window.removeEventListener('keydown', onFirst)
    }
    window.addEventListener('pointerdown', onFirst)
    window.addEventListener('keydown', onFirst)
    return () => {
      window.removeEventListener('pointerdown', onFirst)
      window.removeEventListener('keydown', onFirst)
    }
  }

  function envGain(ac, start, peak, attack, hold, release) {
    const gain = ac.createGain()
    gain.gain.setValueAtTime(0.0001, start)
    gain.gain.exponentialRampToValueAtTime(Math.max(peak, 0.0002), start + attack)
    gain.gain.setValueAtTime(Math.max(peak, 0.0002), start + attack + hold)
    gain.gain.exponentialRampToValueAtTime(0.0001, start + attack + hold + release)
    return gain
  }

  function tone(ac, { freq, type = 'triangle', when, dur, peak = 0.12, attack = 0.012, dest = ac.destination }) {
    const osc = ac.createOscillator()
    osc.type = type
    osc.frequency.setValueAtTime(freq, when)
    const gain = envGain(ac, when, peak, attack, Math.max(0, dur - attack - 0.08), 0.08)
    osc.connect(gain)
    gain.connect(dest)
    osc.start(when)
    osc.stop(when + dur + 0.05)
  }

  function noiseBurst(ac, { when, dur = 0.16, peak = 0.18, dest = ac.destination }) {
    const length = Math.max(1, Math.floor(ac.sampleRate * dur))
    const buffer = ac.createBuffer(1, length, ac.sampleRate)
    const data = buffer.getChannelData(0)
    for (let i = 0; i < length; i++) {
      data[i] = (Math.random() * 2 - 1) * (1 - i / length)
    }
    const src = ac.createBufferSource()
    src.buffer = buffer
    const filter = ac.createBiquadFilter()
    filter.type = 'bandpass'
    filter.frequency.value = 900
    filter.Q.value = 0.8
    const gain = envGain(ac, when, peak, 0.005, dur * 0.25, dur * 0.7)
    src.connect(filter)
    filter.connect(gain)
    gain.connect(dest)
    src.start(when)
    src.stop(when + dur + 0.02)
  }

  function stopThink() {
    if (thinkTimer) {
      clearInterval(thinkTimer)
      thinkTimer = null
    }
    if (thinkGain && ctx) {
      try {
        thinkGain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.08)
      } catch {
        thinkGain.gain.value = 0
      }
      thinkGain = null
    }
  }

  function startThink(ac) {
    stopThink()
    thinkGain = ac.createGain()
    thinkGain.gain.value = muted ? 0 : 0.035
    thinkGain.connect(ac.destination)
    const notes = [220, 261.63, 329.63, 261.63]
    let i = 0
    const tick = () => {
      if (!thinkGain) return
      tone(ac, {
        freq: notes[i % notes.length],
        type: 'triangle',
        when: ac.currentTime,
        dur: 0.18,
        peak: 0.05,
        dest: thinkGain
      })
      i += 1
    }
    tick()
    thinkTimer = setInterval(tick, 420)
  }

  function playUnlocked(name) {
    const ac = audio()
    if (!ac || ac.state !== 'running') return
    const t = ac.currentTime + 0.01

    if (THINK_STOP.has(name)) stopThink()

    switch (name) {
      case 'select':
        tone(ac, { freq: 392, type: 'square', when: t, dur: 0.09, peak: 0.08 })
        tone(ac, { freq: 523.25, type: 'square', when: t + 0.07, dur: 0.12, peak: 0.1 })
        break
      case 'dailyDouble':
        ;[261.63, 329.63, 392, 523.25].forEach((freq, i) => {
          tone(ac, { freq, type: 'sawtooth', when: t + i * 0.14, dur: 0.28, peak: 0.14 })
        })
        break
      case 'open':
        tone(ac, { freq: 392, type: 'triangle', when: t, dur: 0.18, peak: 0.12 })
        tone(ac, { freq: 523.25, type: 'triangle', when: t + 0.16, dur: 0.28, peak: 0.14 })
        break
      case 'buzz':
        noiseBurst(ac, { when: t, dur: 0.2, peak: 0.28 })
        tone(ac, { freq: 140, type: 'square', when: t, dur: 0.22, peak: 0.16 })
        break
      case 'correct':
        ;[523.25, 659.25, 783.99, 1046.5].forEach((freq, i) => {
          tone(ac, { freq, type: 'triangle', when: t + i * 0.09, dur: 0.22, peak: 0.13 })
        })
        break
      case 'incorrect':
        tone(ac, { freq: 220, type: 'sawtooth', when: t, dur: 0.22, peak: 0.14 })
        tone(ac, { freq: 174.61, type: 'sawtooth', when: t + 0.16, dur: 0.28, peak: 0.12 })
        noiseBurst(ac, { when: t, dur: 0.18, peak: 0.1 })
        break
      case 'reveal':
        tone(ac, { freq: 659.25, type: 'sine', when: t, dur: 0.35, peak: 0.1 })
        tone(ac, { freq: 830.61, type: 'sine', when: t + 0.12, dur: 0.4, peak: 0.08 })
        break
      case 'board':
        tone(ac, { freq: 329.63, type: 'triangle', when: t, dur: 0.12, peak: 0.07 })
        break
      case 'finished':
        ;[392, 523.25, 659.25, 783.99].forEach((freq, i) => {
          tone(ac, { freq, type: 'triangle', when: t + i * 0.12, dur: 0.3, peak: 0.12 })
        })
        break
      default:
        break
    }

    if (THINK_START.has(name) && !muted) {
      startThink(ac)
    }
  }

  function play(name) {
    if (!name || muted) {
      if (THINK_STOP.has(name)) stopThink()
      return
    }
    unlock().then(() => playUnlocked(name))
  }

  return {
    play,
    attachUnlock,
    unlock,
    get muted() {
      return muted
    },
    setMuted(value) {
      muted = !!value
      saveMuted(muted)
      if (muted) stopThink()
      notify()
    },
    toggleMuted() {
      this.setMuted(!muted)
    },
    onMuteChange(fn) {
      listeners.add(fn)
      return () => listeners.delete(fn)
    }
  }
}

let shared
export function getGameSfx() {
  if (!shared) shared = createGameSfx()
  return shared
}
