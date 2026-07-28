package com.jmjava.teamjeopardy.game;

import com.jmjava.teamjeopardy.quiz.Board;

import java.time.Instant;
import java.util.List;

public record GameSnapshot(
        String roomId,
        String code,
        String title,
        GamePhase phase,
        String hostPlayerId,
        int revision,
        Instant createdAt,
        Board board,
        ActiveClue activeClue,
        List<Team> teams,
        List<Player> players,
        List<BoardCellState> cells,
        /** Moderator hints used when generating this board (may be blank). */
        String questionHints
) {
}
