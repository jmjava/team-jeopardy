package com.jmjava.teamjeopardy.persist;

import com.jmjava.teamjeopardy.quiz.Board;

import java.time.Instant;
import java.util.List;

/** Lightweight listing row for the SQLite question bank. */
public record SavedBoardSummary(
        String id,
        String title,
        String sourceKind,
        String sourceKey,
        String fingerprint,
        String questionHints,
        List<String> questionFocuses,
        String sourceRoot,
        Board.GraphDigest graphDigest,
        int categoryCount,
        int clueCount,
        Instant createdAt,
        Instant lastUsedAt
) {
}
