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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameRoomServiceTest {

    private GameRoomService service;

    @BeforeEach
    void setUp() {
        service = new GameRoomService();
        ReflectionTestUtils.setField(service, "maxTeams", 6);
        ReflectionTestUtils.setField(service, "maxPlayersPerTeam", 8);
    }

    @Test
    void moderatorAdmitsThenHostPreviewThenBuzz() {
        var created = service.createRoom("Pat", "Demo");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();

        var joined = service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        assertFalse(joined.snapshot().players().stream()
                .filter(p -> p.id().equals(joined.playerId()))
                .findFirst()
                .orElseThrow()
                .admitted());

        service.admitPlayer(roomId, hostId, joined.playerId());

        Board board = new Board(
                "Demo",
                "/tmp",
                List.of(new Category(
                        "cat",
                        "TEST",
                        List.of(new Clue("c1", 200, "prompt text", "What is x?", "why", null, false))
                )),
                new Board.GraphDigest(1, 1, 1, 1, 1)
        );
        service.installBoard(roomId, hostId, board);
        service.startGame(roomId, hostId);

        GameSnapshot preview = service.selectClue(roomId, hostId, "c1");
        assertEquals(GamePhase.HOST_PREVIEW, preview.phase());
        assertEquals("prompt text", preview.activeClue().prompt());
        assertEquals("What is x?", preview.activeClue().response());

        GameSnapshot publicPreview = service.publicSnapshot(roomId);
        assertNull(publicPreview.activeClue().prompt());
        assertNull(publicPreview.activeClue().response());

        GameSnapshot open = service.openBuzzers(roomId, hostId);
        assertEquals(GamePhase.CLUE_OPEN, open.phase());

        GameSnapshot buzzed = service.buzz(roomId, joined.playerId());
        assertEquals(GamePhase.BUZZ_LOCKED, buzzed.phase());
        assertNotNull(buzzed.activeClue().buzzedPlayerId());
        assertEquals("Blue", buzzed.activeClue().buzzedTeamName());

        GameSnapshot judged = service.judge(roomId, hostId, true);
        assertEquals(200, judged.teams().getFirst().score());
        assertEquals(GamePhase.ANSWER_REVEALED, judged.phase());
        assertTrue(judged.activeClue().responseVisible());
    }
}
