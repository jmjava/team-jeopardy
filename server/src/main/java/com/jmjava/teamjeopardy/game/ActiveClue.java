package com.jmjava.teamjeopardy.game;

public record ActiveClue(
        String clueId,
        String categoryId,
        String categoryTitle,
        int value,
        String prompt,
        String response,
        String explanation,
        String sourcePath,
        boolean dailyDouble,
        boolean responseVisible,
        String buzzedPlayerId,
        String buzzedPlayerName,
        String buzzedTeamId,
        String buzzedTeamName,
        String buzzedTeamColor
) {
    public ActiveClue withoutAnswer() {
        return new ActiveClue(
                clueId, categoryId, categoryTitle, value,
                prompt, null, null, sourcePath, dailyDouble, false,
                buzzedPlayerId, buzzedPlayerName, buzzedTeamId, buzzedTeamName, buzzedTeamColor
        );
    }

    public ActiveClue asHostPreviewTeaser() {
        return new ActiveClue(
                clueId, categoryId, categoryTitle, value,
                null, null, null, null, dailyDouble, false,
                null, null, null, null, null
        );
    }
}
