import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

const WS_URL = import.meta.env.VITE_WS_URL || '/ws'

/**
 * @param {{
 *   roomId: string,
 *   playerId?: string,
 *   isHost?: boolean,
 *   onSnapshot: Function,
 *   onStatus?: Function,
 *   onError?: Function
 * }} opts
 */
export function connectGameSocket({
  roomId,
  playerId,
  isHost = false,
  onSnapshot,
  onStatus,
  onError
}) {
  const client = new Client({
    webSocketFactory: () => new SockJS(WS_URL),
    reconnectDelay: 2000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      onStatus?.('connected')
      // Shared/public feed (answers redacted until reveal)
      client.subscribe(`/topic/room.${roomId}`, (message) => {
        if (isHost) return // host uses the privileged feed
        try {
          onSnapshot?.(JSON.parse(message.body))
        } catch (err) {
          console.error('Failed to parse game snapshot', err)
        }
      })
      if (isHost) {
        client.subscribe(`/topic/room.${roomId}.host`, (message) => {
          try {
            onSnapshot?.(JSON.parse(message.body))
          } catch (err) {
            console.error('Failed to parse host snapshot', err)
          }
        })
      }
      client.subscribe(`/topic/room.${roomId}.errors`, (message) => {
        try {
          const body = JSON.parse(message.body)
          if (!playerId || body.playerId !== playerId) return
          onError?.(body)
        } catch (err) {
          console.error('Failed to parse action error', err)
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
