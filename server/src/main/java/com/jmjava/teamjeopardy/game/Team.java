package com.jmjava.teamjeopardy.game;

public record Team(
        String id,
        String name,
        int score,
        String color
) {
    public Team withScore(int newScore) {
        return new Team(id, name, newScore, color);
    }
}
