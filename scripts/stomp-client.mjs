/**
 * Minimal STOMP 1.2 client over a native WebSocket (Node 22+ global WebSocket).
 * Used by the multiplayer simulator to drive the same channel as Vue clients.
 */
export class StompClient {
  constructor(url) {
    this.url = url
    this.ws = null
    this.connected = false
    this.subscriptions = new Map()
    this.subSeq = 0
    this.buffer = ''
    this._connect = null
    this.snapshots = []
    this.latest = null
    this.waiters = []
  }

  connect(timeoutMs = 8000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`STOMP connect timeout: ${this.url}`)), timeoutMs)
      this.ws = new WebSocket(this.url)
      this.ws.addEventListener('open', () => {
        this._sendRaw('CONNECT', { 'accept-version': '1.2,1.1,1.0', 'heart-beat': '0,0' })
      })
      this.ws.addEventListener('message', (event) => this._onData(event.data))
      this.ws.addEventListener('error', (err) => {
        clearTimeout(timer)
        reject(err)
      })
      this.ws.addEventListener('close', () => {
        this.connected = false
      })
      this._connect = {
        resolve: (value) => {
          clearTimeout(timer)
          resolve(value)
        },
        reject: (err) => {
          clearTimeout(timer)
          reject(err)
        }
      }
    })
  }

  subscribe(destination, handler) {
    const id = `sub-${++this.subSeq}`
    this.subscriptions.set(id, { destination, handler })
    this._sendRaw('SUBSCRIBE', { id, destination })
    return id
  }

  send(destination, body) {
    const payload = typeof body === 'string' ? body : JSON.stringify(body)
    this._sendRaw(
      'SEND',
      { destination, 'content-type': 'application/json' },
      payload
    )
  }

  disconnect() {
    try {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this._sendRaw('DISCONNECT', {})
        this.ws.close()
      }
    } catch {
      // ignore
    }
    this.connected = false
  }

  async waitFor(predicate, timeoutMs = 8000, label = 'snapshot') {
    const start = Date.now()
    if (this.latest && predicate(this.latest)) {
      return this.latest
    }
    return new Promise((resolve, reject) => {
      const waiter = {
        predicate,
        resolve,
        timer: setTimeout(() => {
          this.waiters = this.waiters.filter((w) => w !== waiter)
          reject(new Error(`Timed out waiting for ${label}`))
        }, timeoutMs)
      }
      this.waiters.push(waiter)
      if (Date.now() - start > timeoutMs) {
        clearTimeout(waiter.timer)
        reject(new Error(`Timed out waiting for ${label}`))
      }
    })
  }

  _onData(data) {
    const text = typeof data === 'string' ? data : Buffer.from(data).toString('utf8')
    this.buffer += text
    const frames = this.buffer.split('\0')
    this.buffer = frames.pop() || ''
    for (const raw of frames) {
      const frame = raw.replace(/^\n+/, '')
      if (!frame.trim()) continue
      this._handleFrame(parseFrame(frame))
    }
  }

  _handleFrame(frame) {
    if (frame.command === 'CONNECTED') {
      this.connected = true
      this._connect?.resolve(this)
      this._connect = null
      return
    }
    if (frame.command === 'ERROR') {
      const err = new Error(frame.body || frame.headers.message || 'STOMP ERROR')
      if (this._connect) {
        this._connect.reject(err)
        this._connect = null
      } else {
        console.error(err.message)
      }
      return
    }
    if (frame.command !== 'MESSAGE') return
    let payload = frame.body
    try {
      payload = frame.body ? JSON.parse(frame.body) : null
    } catch {
      // keep raw
    }
    if (payload && typeof payload === 'object' && payload.phase) {
      this.snapshots.push(payload)
      this.latest = payload
      const pending = this.waiters
      this.waiters = []
      for (const waiter of pending) {
        if (waiter.predicate(payload)) {
          clearTimeout(waiter.timer)
          waiter.resolve(payload)
        } else {
          this.waiters.push(waiter)
        }
      }
    }
    const dest = frame.headers.destination
    for (const sub of this.subscriptions.values()) {
      if (sub.destination === dest && sub.handler) {
        sub.handler(payload, frame)
      }
    }
  }

  _sendRaw(command, headers, body = '') {
    let frame = `${command}\n`
    for (const [key, value] of Object.entries(headers)) {
      frame += `${key}:${value}\n`
    }
    if (body) {
      frame += `content-length:${Buffer.byteLength(body)}\n`
    }
    frame += `\n${body}\0`
    this.ws.send(frame)
  }
}

function parseFrame(raw) {
  const nl = raw.indexOf('\n')
  const command = (nl === -1 ? raw : raw.slice(0, nl)).trim()
  const rest = nl === -1 ? '' : raw.slice(nl + 1)
  const split = rest.indexOf('\n\n')
  const headerBlock = split === -1 ? rest : rest.slice(0, split)
  const body = split === -1 ? '' : rest.slice(split + 2)
  const headers = {}
  for (const line of headerBlock.split('\n')) {
    if (!line) continue
    const colon = line.indexOf(':')
    if (colon === -1) continue
    headers[line.slice(0, colon)] = line.slice(colon + 1)
  }
  return { command, headers, body }
}

export async function connectStomp(url, destination) {
  const client = new StompClient(url)
  await client.connect()
  if (destination) {
    client.subscribe(destination)
  }
  return client
}
