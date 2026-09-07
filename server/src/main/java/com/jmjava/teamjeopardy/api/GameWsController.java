package com.jmjava.teamjeopardy.api;

import com.jmjava.teamjeopardy.game.GameAction;
import com.jmjava.teamjeopardy.game.GameRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bidirectional STOMP channel.
 * Clients send actions to /app/room/{roomId}/action.
 * Public/shared displays subscribe to /topic/room.{roomId}.
 * Moderator consoles also subscribe to /topic/room.{roomId}.host for full clue text.
 */
@Controller
public class GameWsController {

    private static final Logger log = LoggerFactory.getLogger(GameWsController.class);

    private final GameRoomService gameRoomService;
    private final GameBroadcaster broadcaster;

    public GameWsController(GameRoomService gameRoomService, GameBroadcaster broadcaster) {
        this.gameRoomService = gameRoomService;
        this.broadcaster = broadcaster;
    }

    @MessageMapping("/room/{roomId}/action")
    public void handleAction(@DestinationVariable String roomId, @Payload GameAction action) {
        try {
            gameRoomService.applyAction(roomId, action);
            broadcaster.broadcast(roomId);
        } catch (ResponseStatusException ex) {
            // Keep the STOMP session open: a lost buzz race must not kick the player.
            log.info("Rejected {} in room {}: {}", action == null ? null : action.type(), roomId, ex.getReason());
        }
    }
}
