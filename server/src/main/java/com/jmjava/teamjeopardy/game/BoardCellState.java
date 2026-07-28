package com.jmjava.teamjeopardy.game;

public record BoardCellState(
        String clueId,
        String categoryId,
        int value,
        boolean answered,
        boolean dailyDouble
) {
}
