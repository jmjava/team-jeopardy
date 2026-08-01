package com.jmjava.teamjeopardy.persist;

import com.jmjava.teamjeopardy.quiz.Board;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A guide-generated Jeopardy board persisted for reuse across rooms/sessions.
 */
public record SavedBoardRecord(
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
        Map<String, Object> summary,
        Board board,
        Instant createdAt,
        Instant lastUsedAt
) {
    public SavedBoardSummary toSummary() {
        return new SavedBoardSummary(
                id,
                title,
                sourceKind,
                sourceKey,
                fingerprint,
                questionHints,
                questionFocuses,
                sourceRoot,
                graphDigest,
                categoryCount,
                clueCount,
                createdAt,
                lastUsedAt
        );
    }
}
