package com.jmjava.teamjeopardy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * The simple broker forwards a client SEND on {@code /topic} or {@code /queue}
 * to every subscriber. Displays listen on those prefixes, so a spectator could
 * replace the live board. Only the server publishes there. App destinations
 * under {@code /app} still go through the controllers.
 */
@Component
public class BrokerPublishGuard implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BrokerPublishGuard.class);

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (!StompCommand.SEND.equals(accessor.getCommand())) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination != null && (destination.startsWith("/topic") || destination.startsWith("/queue"))) {
            log.info("Rejected client publish to {}", destination);
            return null;
        }
        return message;
    }
}
