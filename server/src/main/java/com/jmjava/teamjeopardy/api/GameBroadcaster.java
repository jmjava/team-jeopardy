package com.jmjava.teamjeopardy.api;

import com.jmjava.teamjeopardy.game.ActionReceipt;
import com.jmjava.teamjeopardy.game.GameRoomService;
import com.jmjava.teamjeopardy.game.GameSnapshot;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Fan-out of public vs host snapshots after every room mutation.
 * Copies are taken under the room lock inside {@link GameRoomService}.
 */
@Component
public class GameBroadcaster {

    private final GameRoomService gameRoomService;
    private final SimpMessagingTemplate messagingTemplate;

    public GameBroadcaster(GameRoomService gameRoomService, SimpMessagingTemplate messagingTemplate) {
        this.gameRoomService = gameRoomService;
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcast(String roomId) {
        GameRoomService.SnapshotPair views = gameRoomService.snapshotPair(roomId);
        GameSnapshot pub = views.pub();
        GameSnapshot host = views.host();
        messagingTemplate.convertAndSend("/topic/room." + pub.roomId(), pub);
        messagingTemplate.convertAndSend("/topic/room-code." + pub.code(), pub);
        messagingTemplate.convertAndSend("/topic/room." + host.roomId() + ".host", host);
    }

    public static String playerReceiptDestination(String roomId, String playerId) {
        return "/topic/room." + roomId + ".player." + playerId;
    }

    public void sendReceipt(ActionReceipt receipt) {
        if (receipt == null || receipt.playerId() == null || receipt.playerId().isBlank()) {
            return;
        }
        messagingTemplate.convertAndSend(playerReceiptDestination(receipt.roomId(), receipt.playerId()), receipt);
    }
}
