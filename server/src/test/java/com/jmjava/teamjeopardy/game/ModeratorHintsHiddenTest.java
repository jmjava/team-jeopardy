package com.jmjava.teamjeopardy.game;

import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ModeratorHintsHiddenTest {

    private static final String HINTS = "Singleton clue: the answer is EagerSingleton";

    private GameRoomService service;

    @BeforeEach
    void setUp() {
        service = new GameRoomService();
        ReflectionTestUtils.setField(service, "maxTeams", 6);
        ReflectionTestUtils.setField(service, "maxPlayersPerTeam", 8);
        ReflectionTestUtils.setField(service, "finalWritingSeconds", 30);
    }

    @Test
    void publicSnapshotsOmitModeratorHints() {
        var created = service.createRoom("Pat", "Hints");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        var alex = service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        service.admitPlayer(roomId, hostId, alex.playerId());
        service.installBoard(roomId, hostId, board(), HINTS);
        service.startGame(roomId, hostId);

        assertFalse(service.publicSnapshot(roomId).questionHints().contains("EagerSingleton"));
        assertEquals("", service.publicSnapshot(roomId).questionHints());

        GameSnapshot player = service.viewFor(roomId, alex.playerId(), null, alex.playerToken());
        assertEquals("", player.questionHints());

        GameSnapshot host = service.viewFor(roomId, hostId, created.hostToken(), null);
        assertEquals(HINTS, host.questionHints());
    }

    private static Board board() {
        return new Board(
                "Hints",
                "/tmp",
                List.of(new Category(
                        "cat",
                        "TEST",
                        List.of(new Clue("c1", 200, "Name this class.", "What is EagerSingleton?", null, null, false))
                )),
                new Board.GraphDigest(1, 1, 1, 1, 1)
        );
    }
}
