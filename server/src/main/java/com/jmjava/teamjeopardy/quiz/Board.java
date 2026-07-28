package com.jmjava.teamjeopardy.quiz;

import java.util.List;

public record Board(
        String title,
        String sourceRoot,
        List<Category> categories,
        GraphDigest graphDigest
) {
    public record GraphDigest(int nodes, int edges, int files, int types, int functions) {
    }
}
