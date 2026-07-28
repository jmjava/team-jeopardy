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
        String buzzedTeamId
) {
}
