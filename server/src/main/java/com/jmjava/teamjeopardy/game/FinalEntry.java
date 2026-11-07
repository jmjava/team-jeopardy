package com.jmjava.teamjeopardy.game;

public record FinalEntry(
        String teamId,
        String teamName,
        String teamColor,
        String playerId,
        String playerName,
        Integer wager,
        boolean wagerLocked,
        String answer,
        boolean answerLocked,
        Boolean correct
) {
    public FinalEntry withWager(int amount, String playerId, String playerName) {
        return new FinalEntry(
                teamId, teamName, teamColor, playerId, playerName,
                amount, true, answer, answerLocked, correct
        );
    }

    public FinalEntry withAnswer(String text, String playerId, String playerName) {
        return new FinalEntry(
                teamId, teamName, teamColor, playerId, playerName,
                wager, wagerLocked, text, answerLocked, correct
        );
    }

    public FinalEntry lockAnswer() {
        String text = answer == null ? "" : answer;
        return new FinalEntry(
                teamId, teamName, teamColor, playerId, playerName,
                wager, wagerLocked, text, true, correct
        );
    }

    public FinalEntry withJudgement(boolean correct) {
        return new FinalEntry(
                teamId, teamName, teamColor, playerId, playerName,
                wager, wagerLocked, answer, true, correct
        );
    }

    public FinalEntry redactForPublic(boolean revealText, boolean revealWager) {
        return new FinalEntry(
                teamId,
                teamName,
                teamColor,
                playerId,
                playerName,
                revealWager ? wager : null,
                wagerLocked,
                revealText ? answer : null,
                answerLocked,
                revealText ? correct : null
        );
    }

    public FinalEntry redactKeepingOwnText(boolean revealOthers, boolean isOwnTeam) {
        boolean showText = isOwnTeam || revealOthers;
        boolean showWager = isOwnTeam || revealOthers;
        return new FinalEntry(
                teamId,
                teamName,
                teamColor,
                playerId,
                playerName,
                showWager ? wager : null,
                wagerLocked,
                showText ? answer : null,
                answerLocked,
                revealOthers ? correct : (isOwnTeam ? correct : null)
        );
    }
}
