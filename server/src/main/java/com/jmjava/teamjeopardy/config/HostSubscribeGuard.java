package com.jmjava.teamjeopardy.config;

import com.jmjava.teamjeopardy.game.GameRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * The simple broker treats {@code *} as a wildcard, so a subscribe to
 * {@code /topic/**} receives the unredacted host feed. Pattern subscribes are
 * dropped. The exact host destination requires the create-room token.
 * Returning null drops only that frame, so the same connection can still
 * follow the public topic.
 */
@Component
public class HostSubscribeGuard implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(HostSubscribeGuard.class);
    private static final String HOST_SUFFIX = ".host";
    private static final String ROOM_PREFIX = "/topic/room.";

    private final GameRoomService gameRoomService;

    public HostSubscribeGuard(GameRoomService gameRoomService) {
        this.gameRoomService = gameRoomService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith("/topic/")) {
            return message;
        }
        if (destination.indexOf('*') >= 0 || destination.indexOf('?') >= 0) {
            log.info("Rejected pattern subscribe to {}", destination);
            return null;
        }
        if (destination.endsWith(HOST_SUFFIX) && destination.startsWith(ROOM_PREFIX)) {
            String roomId = destination.substring(ROOM_PREFIX.length(), destination.length() - HOST_SUFFIX.length());
            String playerId = accessor.getFirstNativeHeader("playerId");
            String hostToken = accessor.getFirstNativeHeader("hostToken");
            if (!gameRoomService.hostCredentialMatches(roomId, playerId, hostToken)) {
                log.info("Rejected host subscribe for room {}", roomId);
                return null;
            }
        }
        return message;
    }
}
