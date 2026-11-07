package com.jmjava.teamjeopardy.game;

import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyDoubleLocationHiddenTest {

    private GameRoomService service;

    @BeforeEach
    void setUp() {
        service = new GameRoomService();
        ReflectionTestUtils.setField(service, "maxTeams", 6);
        ReflectionTestUtils.setField(service, "maxPlayersPerTeam", 8);
        ReflectionTestUtils.setField(service, "finalWritingSeconds", 30);
    }

    @Test
    void publicBoardDoesNotMarkAnUnplayedDailyDouble() {
        var created = service.createRoom("Pat", "Daily Double");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        var alex = service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        service.admitPlayer(roomId, hostId, alex.playerId());
        service.installBoard(roomId, hostId, board());
        service.startGame(roomId, hostId);

        assertFalse(marked(service.publicSnapshot(roomId)));
        assertFalse(marked(service.viewFor(roomId, alex.playerId(), null, alex.playerToken())));

        GameSnapshot host = service.viewFor(roomId, hostId, created.hostToken(), null);
        assertTrue(clueMarked(host));
        assertTrue(cellMarked(host));

        service.selectClue(roomId, hostId, "dd");
        GameSnapshot announced = service.publicSnapshot(roomId);
        assertTrue(announced.activeClue().dailyDouble());
        assertNull(announced.activeClue().prompt());
        assertFalse(marked(announced));
    }

    private static boolean marked(GameSnapshot snapshot) {
        return clueMarked(snapshot) || cellMarked(snapshot);
    }

    private static boolean clueMarked(GameSnapshot snapshot) {
        return snapshot.board().categories().stream()
                .flatMap(category -> category.clues().stream())
                .anyMatch(clue -> "dd".equals(clue.id()) && clue.dailyDouble());
    }

    private static boolean cellMarked(GameSnapshot snapshot) {
        return snapshot.cells().stream()
                .anyMatch(cell -> "dd".equals(cell.clueId()) && cell.dailyDouble());
    }

    private static Board board() {
        return new Board(
                "Daily Double",
                "/tmp",
                List.of(new Category(
                        "cat",
                        "TEST",
                        List.of(
                                new Clue("plain", 200, "plain prompt", "What is plain?", null, null, false),
                                new Clue("dd", 400, "secret prompt", "What is hidden?", null, null, true)
                        )
                )),
                new Board.GraphDigest(1, 1, 1, 1, 1)
        );
    }
}
