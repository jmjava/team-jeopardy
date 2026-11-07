package com.jmjava.teamjeopardy.quiz;

import java.util.List;

public record Board(
        String title,
        String sourceRoot,
        List<Category> categories,
        GraphDigest graphDigest,
        Clue finalJeopardy,
        String finalCategoryTitle
) {
    public Board(String title, String sourceRoot, List<Category> categories, GraphDigest graphDigest) {
        this(title, sourceRoot, categories, graphDigest, null, null);
    }

    public Board withFinalJeopardy(Clue clue, String categoryTitle) {
        Clue marked = clue == null ? null : clue.asFinal();
        return new Board(title, sourceRoot, categories, graphDigest, marked, categoryTitle);
    }

    public boolean hasFinalJeopardy() {
        return finalJeopardy != null;
    }

    public String finalCategoryLabel() {
        if (finalCategoryTitle != null && !finalCategoryTitle.isBlank()) {
            return finalCategoryTitle;
        }
        return "Final Jeopardy";
    }

    public record GraphDigest(int nodes, int edges, int files, int types, int functions) {
    }
}
