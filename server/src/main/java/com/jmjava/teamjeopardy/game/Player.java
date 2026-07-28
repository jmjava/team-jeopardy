package com.jmjava.teamjeopardy.game;

public record Player(
        String id,
        String displayName,
        String teamId,
        boolean host,
        String sessionId
) {
}
