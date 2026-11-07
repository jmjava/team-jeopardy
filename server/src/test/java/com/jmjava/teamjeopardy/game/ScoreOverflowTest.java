package com.jmjava.teamjeopardy.game;

import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreOverflowTest {

    private GameRoomService service;

    @BeforeEach
    void setUp() {
        service = new GameRoomService();
        ReflectionTestUtils.setField(service, "maxTeams", 6);
        ReflectionTestUtils.setField(service, "maxPlayersPerTeam", 8);
        ReflectionTestUtils.setField(service, "finalWritingSeconds", 30);
    }

    @Test
    void judgingAFaceValueThatOverflowsLeavesTheScoreUnchanged() {
        var created = service.createRoom("Pat", "Overflow");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        var alex = service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        service.admitAll(roomId, hostId);
        service.installBoard(roomId, hostId, board());
        service.startGame(roomId, hostId);

        service.selectClue(roomId, hostId, "c1");
        service.openBuzzers(roomId, hostId);
        service.buzz(roomId, alex.playerId());
        service.judge(roomId, hostId, true);
        service.returnToBoard(roomId, hostId);

        service.selectClue(roomId, hostId, "c2");
        service.openBuzzers(roomId, hostId);
        service.buzz(roomId, alex.playerId());
        ResponseStatusException overflow = assertThrows(
                ResponseStatusException.class,
                () -> service.judge(roomId, hostId, true)
        );

        assertTrue(overflow.getReason().contains("overflow"));
        assertEquals(200, score(service.hostSnapshot(roomId), "Blue"));
    }

    private static int score(GameSnapshot snapshot, String teamName) {
        return snapshot.teams().stream()
                .filter(team -> team.name().equals(teamName))
                .findFirst()
                .orElseThrow()
                .score();
    }

    private static Board board() {
        return new Board(
                "Overflow",
                "/tmp",
                List.of(new Category(
                        "cat",
                        "TEST",
                        List.of(
                                new Clue("c1", 200, "prompt", "What is x?", null, null, false),
                                new Clue("c2", Integer.MAX_VALUE, "huge", "What is y?", null, null, false)
                        )
                )),
                new Board.GraphDigest(1, 1, 1, 1, 1)
        );
    }
}
