package com.jmjava.teamjeopardy.api;

import com.jmjava.teamjeopardy.game.GameSnapshot;
import com.jmjava.teamjeopardy.quiz.Board;
import jakarta.validation.constraints.NotBlank;

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
            String repo,
            String branch,
            String commitSha,
            String boardTitle
    ) {
    }

    public record IngestResponse(GameSnapshot snapshot, Board board, Map<String, Object> ingestSummary) {
    }

    public record ActionRequest(
            @NotBlank String playerId,
            @NotBlank String type,
            Map<String, Object> payload
    ) {
    }
}
