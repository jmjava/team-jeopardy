package com.jmjava.teamjeopardy.game;

import java.util.Map;

public record GameAction(
        String type,
        String playerId,
        String roomCode,
        Map<String, Object> payload
) {
}
