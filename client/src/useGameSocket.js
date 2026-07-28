import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

const WS_URL = import.meta.env.VITE_WS_URL || '/ws'

export function connectGameSocket({ roomId, onSnapshot, onStatus }) {
  const client = new Client({
    webSocketFactory: () => new SockJS(WS_URL),
    reconnectDelay: 2000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      onStatus?.('connected')
      client.subscribe(`/topic/room.${roomId}`, (message) => {
        try {
          onSnapshot?.(JSON.parse(message.body))
        } catch (err) {
          console.error('Failed to parse game snapshot', err)
        }
      })
    },
    onDisconnect: () => onStatus?.('disconnected'),
    onStompError: () => onStatus?.('error'),
    onWebSocketClose: () => onStatus?.('disconnected')
  })

  client.activate()

  return {
    sendAction(action) {
      if (!client.connected) {
        throw new Error('Realtime channel not connected')
      }
      client.publish({
        destination: `/app/room/${roomId}/action`,
        body: JSON.stringify(action)
      })
    },
    disconnect() {
      client.deactivate()
    }
  }
}
