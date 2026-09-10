package com.jmjava.teamjeopardy.api;

import com.jmjava.teamjeopardy.game.GameSnapshot;
import com.jmjava.teamjeopardy.quiz.Board;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public final class Dto {

    private Dto() {
    }

    public record CreateRoomRequest(String hostName, String title) {
    }

    public record CreateRoomResponse(
            GameSnapshot snapshot,
            String hostPlayerId,
            String hostName
    ) {
    }

    public record JoinRoomRequest(
            @NotBlank String code,
            @NotBlank String displayName,
            String teamName
    ) {
    }

    public record JoinRoomResponse(GameSnapshot snapshot, String playerId) {
    }

    public record IngestRequest(
            @NotBlank String roomId,
            @NotBlank String playerId,
            String path,
            Boolean useSample,
            /**
             * Sample/project flavor: maven | gradle | vue | npm | python | pulls | github
             */
            String sampleType,
            /** GitHub repo owner/name for repo ingest and/or PR boards. */
            String repo,
            /** Branch, tag, or commit SHA (default HEAD / default branch). */
            String ref,
            /** Optional subfolders to include (empty = whole repository). */
            List<String> folders,
            /** When true with github ingest, also attach recent PR facts. */
            Boolean includePulls,
            /** Max recent PRs to fetch (default from config). */
            Integer prLimit,
            String branch,
            String commitSha,
            String boardTitle,
            /**
             * Free-text moderator hints for the kinds of questions wanted this game
             * (e.g. "focus on design patterns and QA blast radius").
             */
            String questionHints,
            /** Optional structured focus chips: patterns, pull-requests, qa, apis, architecture, … */
            List<String> questionFocuses
    ) {
    }

    public record GitHubBrowseRequest(
            @NotBlank String repo,
            String ref,
            String path,
            Boolean recursive
    ) {
    }

    public record IngestResponse(GameSnapshot snapshot, Board board, Map<String, Object> ingestSummary) {
    }

    public record JiraIngestRequest(
            @NotBlank String roomId,
            @NotBlank String playerId,
            List<String> projects,
            String release,
            String jql,
            Boolean useFixture,
            /** multi | one-project — used when {@code useFixture} is true. */
            String fixture,
            String boardTitle,
            String questionHints,
            List<String> questionFocuses
    ) {
    }

    public record LoadBoardRequest(
            @NotBlank String roomId,
            @NotBlank String playerId,
            @NotBlank String savedBoardId
    ) {
    }

    public record CreateSavedBoardRequest(
            @NotBlank String title,
            String sourceKind,
            String sourceKey,
            String sourceRoot,
            String questionHints,
            List<String> questionFocuses,
            List<CategoryInput> categories
    ) {
    }

    public record CategoryInput(
            String id,
            String title,
            List<ClueInput> clues
    ) {
    }

    public record ClueInput(
            String id,
            Integer value,
            @NotBlank String prompt,
            @NotBlank String response,
            String explanation,
            String sourcePath,
            Boolean dailyDouble
    ) {
    }

    public record AddClueRequest(
            String categoryId,
            String categoryTitle,
            Integer value,
            @NotBlank String prompt,
            @NotBlank String response,
            String explanation,
            String sourcePath,
            Boolean dailyDouble
    ) {
    }

    /**
     * Bulk import payload. Accept either {@code boards: [...]} or a top-level array
     * deserialized into {@code boards}.
     */
    public record BulkUploadRequest(
            List<CreateSavedBoardRequest> boards,
            Boolean skipDuplicates
    ) {
    }

    public record BulkUploadItemResult(
            int index,
            String title,
            String status,
            String id,
            String message
    ) {
    }

    public record BulkUploadResponse(
            int created,
            int skipped,
            int failed,
            List<BulkUploadItemResult> results
    ) {
    }

    public record ActionRequest(
            @NotBlank String playerId,
            @NotBlank String type,
            Map<String, Object> payload
    ) {
    }
}
