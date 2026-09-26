package com.jmjava.teamjeopardy.api;

import com.jmjava.teamjeopardy.game.ActionReceipt;
import com.jmjava.teamjeopardy.game.GameAction;
import com.jmjava.teamjeopardy.game.GameRoomService;
import com.jmjava.teamjeopardy.game.GameSnapshot;
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
        String playerId = action == null ? null : action.playerId();
        String type = action == null ? null : action.type();
        try {
            GameSnapshot applied = gameRoomService.applyAction(roomId, action);
            broadcaster.broadcast(roomId);
            broadcaster.sendReceipt(ActionReceipt.accepted(playerId, type, applied));
        } catch (ResponseStatusException ex) {
            // Keep the STOMP session open: a lost buzz race must not kick the player.
            log.info("Rejected {} in room {}: {}", type, roomId, ex.getReason());
            GameSnapshot current = null;
            try {
                current = gameRoomService.publicSnapshot(roomId);
            } catch (RuntimeException ignored) {
                // room vanished
            }
            broadcaster.sendReceipt(ActionReceipt.rejected(roomId, playerId, type, ex.getReason(), current));
        }
    }
}
