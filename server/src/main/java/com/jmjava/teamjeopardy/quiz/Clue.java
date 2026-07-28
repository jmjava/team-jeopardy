package com.jmjava.teamjeopardy.quiz;

public record Clue(
        String id,
        int value,
        String prompt,
        String response,
        String explanation,
        String sourcePath,
        boolean dailyDouble
) {
}
