package com.jmjava.teamjeopardy.quiz;

public record Clue(
        String id,
        int value,
        String prompt,
        String response,
        String explanation,
        String sourcePath,
        boolean dailyDouble,
        boolean finalJeopardy
) {
    public Clue(
            String id,
            int value,
            String prompt,
            String response,
            String explanation,
            String sourcePath,
            boolean dailyDouble
    ) {
        this(id, value, prompt, response, explanation, sourcePath, dailyDouble, false);
    }

    public Clue asFinal() {
        return new Clue(id, value, prompt, response, explanation, sourcePath, false, true);
    }

    public Clue withId(String newId) {
        return new Clue(newId, value, prompt, response, explanation, sourcePath, dailyDouble, finalJeopardy);
    }
}
