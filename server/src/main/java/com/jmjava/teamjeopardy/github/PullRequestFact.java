package com.jmjava.teamjeopardy.github;

import java.time.Instant;
import java.util.List;

/**
 * Normalized pull-request fact used for Jeopardy clue generation.
 */
public record PullRequestFact(
        int number,
        String title,
        String author,
        String state,
        boolean merged,
        String body,
        List<String> labels,
        List<String> files,
        int additions,
        int deletions,
        int changedFiles,
        String htmlUrl,
        Instant updatedAt,
        String baseRef,
        String headRef
) {
    public int churn() {
        return Math.max(0, additions) + Math.max(0, deletions);
    }
}
