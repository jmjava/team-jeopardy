package com.jmjava.teamjeopardy.game;

import java.time.Instant;
import java.util.List;

public record FinalRound(
        String categoryTitle,
        String prompt,
        String response,
        String explanation,
        String sourcePath,
        Instant writingEndsAt,
        Integer writingSeconds,
        List<FinalEntry> entries
) {
    public FinalRound {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public FinalRound withEntries(List<FinalEntry> next) {
        return new FinalRound(
                categoryTitle, prompt, response, explanation, sourcePath,
                writingEndsAt, writingSeconds, next
        );
    }

    public FinalRound withWriting(Instant endsAt, int seconds) {
        return new FinalRound(
                categoryTitle, prompt, response, explanation, sourcePath,
                endsAt, seconds, entries
        );
    }

    public FinalRound redact(GamePhase phase, String viewerTeamId, boolean host) {
        boolean revealed = host || phase == GamePhase.FINAL_REVEALED || phase == GamePhase.FINISHED;
        boolean showPrompt = revealed || phase == GamePhase.FINAL_WRITING || phase == GamePhase.FINAL_LOCKED;
        boolean showResponse = revealed;
        boolean revealOthers = revealed;
        List<FinalEntry> redacted = entries.stream()
                .map(entry -> {
                    if (host) {
                        return entry;
                    }
                    boolean own = viewerTeamId != null && viewerTeamId.equals(entry.teamId());
                    return entry.redactKeepingOwnText(revealOthers, own);
                })
                .toList();
        return new FinalRound(
                categoryTitle,
                showPrompt ? prompt : null,
                showResponse ? response : null,
                showResponse ? explanation : null,
                host ? sourcePath : null,
                writingEndsAt,
                writingSeconds,
                redacted
        );
    }
}
