package com.jmjava.teamjeopardy.persist;

/** Individual clue row for search / future reuse outside a full board. */
public record SavedClueRecord(
        String id,
        String boardId,
        String categoryId,
        String categoryTitle,
        String clueId,
        int value,
        String prompt,
        String response,
        String explanation,
        String sourcePath,
        boolean dailyDouble,
        boolean finalJeopardy
) {
    public SavedClueRecord(
            String id,
            String boardId,
            String categoryId,
            String categoryTitle,
            String clueId,
            int value,
            String prompt,
            String response,
            String explanation,
            String sourcePath,
            boolean dailyDouble
    ) {
        this(
                id, boardId, categoryId, categoryTitle, clueId, value, prompt, response,
                explanation, sourcePath, dailyDouble, false
        );
    }
}
