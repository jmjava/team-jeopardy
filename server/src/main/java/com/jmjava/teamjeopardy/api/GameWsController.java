package com.jmjava.teamjeopardy.api;

import com.jmjava.teamjeopardy.game.GameAction;
import com.jmjava.teamjeopardy.game.GameRoomService;
import com.jmjava.teamjeopardy.game.GameSnapshot;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * Bidirectional STOMP channel.
 * Clients send actions to /app/room/{roomId}/action.
 * Public/shared displays subscribe to /topic/room.{roomId}.
 * Moderator consoles also subscribe to /topic/room.{roomId}.host for full clue text.
 */
@Controller
public class GameWsController {

    private final GameRoomService gameRoomService;
    private final SimpMessagingTemplate messagingTemplate;

    public GameWsController(GameRoomService gameRoomService, SimpMessagingTemplate messagingTemplate) {
        this.gameRoomService = gameRoomService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/room/{roomId}/action")
    public void handleAction(@DestinationVariable String roomId, @Payload GameAction action) {
        gameRoomService.applyAction(roomId, action);
        broadcast(roomId);
    }

    private void broadcast(String roomId) {
        GameSnapshot pub = gameRoomService.publicSnapshot(roomId);
        GameSnapshot host = gameRoomService.hostSnapshot(roomId);
        messagingTemplate.convertAndSend("/topic/room." + pub.roomId(), pub);
        messagingTemplate.convertAndSend("/topic/room-code." + pub.code(), pub);
        messagingTemplate.convertAndSend("/topic/room." + host.roomId() + ".host", host);
    }
}
