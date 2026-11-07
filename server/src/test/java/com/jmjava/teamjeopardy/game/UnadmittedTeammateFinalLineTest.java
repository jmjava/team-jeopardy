package com.jmjava.teamjeopardy.game;

import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Joining a team does not admit the player. A waiting teammate still has a
 * join secret, and that secret must not reveal the admitted seat's sealed line.
 */
class UnadmittedTeammateFinalLineTest {

    private GameRoomService service;

    @BeforeEach
    void setUp() {
        service = new GameRoomService();
        ReflectionTestUtils.setField(service, "maxTeams", 6);
        ReflectionTestUtils.setField(service, "maxPlayersPerTeam", 8);
        ReflectionTestUtils.setField(service, "finalWritingSeconds", 30);
    }

    @Test
    void waitingTeammateCannotReadTheAdmittedSeatsSealedFinalLine() {
        var created = service.createRoom("Pat", "Waiting");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        var alex = service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        var riley = service.joinRoom(created.snapshot().code(), "Riley", "Blue");
        service.admitPlayer(roomId, hostId, alex.playerId());

        assertFalse(riley.snapshot().players().stream()
                .filter(player -> player.id().equals(riley.playerId()))
                .findFirst()
                .orElseThrow()
                .admitted());

        service.installBoard(roomId, hostId, finalBoard());
        service.startGame(roomId, hostId);
        service.startFinal(roomId, hostId);
        service.startFinalWager(roomId, hostId);
        service.applyAction(
                roomId,
                new GameAction("SET_WAGER", alex.playerId(), null, Map.of("amount", 200)),
                null,
                alex.playerToken()
        );
        service.startFinalWriting(roomId, hostId, 30);
        service.applyAction(
                roomId,
                new GameAction("SET_FINAL_ANSWER", alex.playerId(), null, Map.of("text", "What is PROJ-1?")),
                null,
                alex.playerToken()
        );

        GameSnapshot waiting = service.viewFor(roomId, riley.playerId(), null, riley.playerToken());
        assertFalse(waiting.finalRound().entries().isEmpty());
        assertTrue(waiting.finalRound().entries().stream().allMatch(entry ->
                entry.answer() == null || entry.answer().isBlank()));
        assertTrue(waiting.finalRound().entries().stream().allMatch(entry -> entry.wager() == null));

        GameSnapshot alexView = service.viewFor(roomId, alex.playerId(), null, alex.playerToken());
        assertEquals("What is PROJ-1?", answerFor(alexView, "Blue"));
        assertEquals(200, wagerFor(alexView, "Blue"));
    }

    private static String answerFor(GameSnapshot snapshot, String teamName) {
        return snapshot.finalRound().entries().stream()
                .filter(entry -> teamName.equals(entry.teamName()))
                .findFirst()
                .orElseThrow()
                .answer();
    }

    private static Integer wagerFor(GameSnapshot snapshot, String teamName) {
        return snapshot.finalRound().entries().stream()
                .filter(entry -> teamName.equals(entry.teamName()))
                .findFirst()
                .orElseThrow()
                .wager();
    }

    private static Board finalBoard() {
        return new Board(
                "Demo",
                "/tmp",
                List.of(new Category(
                        "cat",
                        "TEST",
                        List.of(new Clue("c1", 200, "grid prompt", "What is grid?", "why", null, false))
                )),
                new Board.GraphDigest(1, 1, 1, 1, 1),
                new Clue("final-1", 0, "Final prompt about the release", "What is PROJ-1?", "grounded", "PROJ-1", false, true),
                "Release epic"
        );
    }
}
