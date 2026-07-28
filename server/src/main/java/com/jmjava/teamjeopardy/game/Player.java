package com.jmjava.teamjeopardy.game;

/**
 * @param admitted false while waiting in the lobby; only the moderator can admit players into the game.
 */
public record Player(
        String id,
        String displayName,
        String teamId,
        boolean host,
        boolean admitted,
        String sessionId
) {
    public Player withAdmitted(boolean value) {
        return new Player(id, displayName, teamId, host, value, sessionId);
    }

    public Player withTeam(String newTeamId) {
        return new Player(id, displayName, newTeamId, host, admitted, sessionId);
    }
}
