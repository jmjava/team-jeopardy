package com.jmjava.teamjeopardy.game;

/**
 * Per-player confirmation after a STOMP action. Losing a buzz race stays
 * connected and receives {@code accepted=false} instead of silence.
 */
public record ActionReceipt(
        String roomId,
        String playerId,
        String type,
        boolean accepted,
        String reason,
        int revision,
        String gamePhase
) {
    public static ActionReceipt accepted(String playerId, String type, GameSnapshot snapshot) {
        return new ActionReceipt(
                snapshot.roomId(),
                playerId,
                type,
                true,
                null,
                snapshot.revision(),
                snapshot.phase() == null ? null : snapshot.phase().name()
        );
    }

    public static ActionReceipt rejected(String roomId, String playerId, String type, String reason, GameSnapshot snapshot) {
        return new ActionReceipt(
                roomId,
                playerId,
                type,
                false,
                reason,
                snapshot == null ? 0 : snapshot.revision(),
                snapshot == null || snapshot.phase() == null ? null : snapshot.phase().name()
        );
    }
}
