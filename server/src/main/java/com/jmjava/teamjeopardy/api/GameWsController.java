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
 * Bidirectional STOMP channel for worldwide team play.
 * Clients send actions to /app/room/{roomId}/action and subscribe to /topic/room.{roomId}.
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
        GameSnapshot snapshot = gameRoomService.applyAction(roomId, action);
        messagingTemplate.convertAndSend("/topic/room." + snapshot.roomId(), snapshot);
        messagingTemplate.convertAndSend("/topic/room-code." + snapshot.code(), snapshot);
    }
}
