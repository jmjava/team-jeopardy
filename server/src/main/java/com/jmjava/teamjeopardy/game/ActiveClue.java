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
        String buzzedTeamColor,
        Integer wager
) {
    public ActiveClue(
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
        this(
                clueId, categoryId, categoryTitle, value, prompt, response, explanation, sourcePath,
                dailyDouble, responseVisible, buzzedPlayerId, buzzedPlayerName, buzzedTeamId,
                buzzedTeamName, buzzedTeamColor, null
        );
    }

    public int scoreDelta() {
        return wager != null ? wager : value;
    }

    public ActiveClue withoutAnswer() {
        return new ActiveClue(
                clueId, categoryId, categoryTitle, value,
                prompt, null, null, null, dailyDouble, false,
                buzzedPlayerId, buzzedPlayerName, buzzedTeamId, buzzedTeamName, buzzedTeamColor, wager
        );
    }

    public ActiveClue asHostPreviewTeaser() {
        return new ActiveClue(
                clueId, categoryId, categoryTitle, value,
                null, null, null, null, dailyDouble, false,
                null, null, null, null, null, wager
        );
    }

    public ActiveClue asDailyDoubleTeaser() {
        return new ActiveClue(
                clueId, categoryId, categoryTitle, value,
                null, null, null, null, true, false,
                buzzedPlayerId, buzzedPlayerName, buzzedTeamId, buzzedTeamName, buzzedTeamColor, wager
        );
    }

    public ActiveClue withBuzz(
            String playerId,
            String playerName,
            String teamId,
            String teamName,
            String teamColor
    ) {
        return new ActiveClue(
                clueId, categoryId, categoryTitle, value, prompt, response, explanation, sourcePath,
                dailyDouble, responseVisible, playerId, playerName, teamId, teamName, teamColor, wager
        );
    }

    public ActiveClue clearBuzz() {
        return new ActiveClue(
                clueId, categoryId, categoryTitle, value, prompt, response, explanation, sourcePath,
                dailyDouble, false, null, null, null, null, null, wager
        );
    }

    public ActiveClue withWager(int amount) {
        return new ActiveClue(
                clueId, categoryId, categoryTitle, value, prompt, response, explanation, sourcePath,
                dailyDouble, responseVisible, buzzedPlayerId, buzzedPlayerName, buzzedTeamId,
                buzzedTeamName, buzzedTeamColor, amount
        );
    }

    public ActiveClue withResponseVisible(boolean visible) {
        return new ActiveClue(
                clueId, categoryId, categoryTitle, value, prompt, response, explanation, sourcePath,
                dailyDouble, visible, buzzedPlayerId, buzzedPlayerName, buzzedTeamId,
                buzzedTeamName, buzzedTeamColor, wager
        );
    }
}
