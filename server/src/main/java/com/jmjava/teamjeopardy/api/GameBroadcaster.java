package com.jmjava.teamjeopardy.api;

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
        GameSnapshot pub = gameRoomService.publicSnapshot(roomId);
        GameSnapshot host = gameRoomService.hostSnapshot(roomId);
        messagingTemplate.convertAndSend("/topic/room." + pub.roomId(), pub);
        messagingTemplate.convertAndSend("/topic/room-code." + pub.code(), pub);
        messagingTemplate.convertAndSend("/topic/room." + host.roomId() + ".host", host);
    }
}
