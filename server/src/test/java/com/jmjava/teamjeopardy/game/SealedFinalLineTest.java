package com.jmjava.teamjeopardy.game;

import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Player ids ride on the public snapshot. That id must not be enough to read
 * or replace a seat's sealed Final wager and answer.
 */
class SealedFinalLineTest {

    private GameRoomService service;

    @BeforeEach
    void setUp() {
        service = new GameRoomService();
        ReflectionTestUtils.setField(service, "maxTeams", 6);
        ReflectionTestUtils.setField(service, "maxPlayersPerTeam", 8);
        ReflectionTestUtils.setField(service, "finalWritingSeconds", 30);
    }

    @Test
    void publicPlayerIdDoesNotRevealOrReplaceASealedFinalLine() {
        var created = service.createRoom("Pat", "Seal");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        var alex = service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        var sam = service.joinRoom(created.snapshot().code(), "Sam", "Red");
        assertFalse(alex.playerToken().isBlank());
        assertFalse(alex.snapshot().toString().contains(alex.playerToken()));
        assertFalse(sam.snapshot().toString().contains(sam.playerToken()));

        service.admitAll(roomId, hostId);
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
        service.applyAction(
                roomId,
                new GameAction("SET_WAGER", sam.playerId(), null, Map.of("amount", 50)),
                null,
                sam.playerToken()
        );

        GameSnapshot wagerPeek = service.viewFor(roomId, alex.playerId(), null, null);
        assertFalse(wagerPeek.finalRound().entries().isEmpty());
        assertTrue(wagerPeek.finalRound().entries().stream().allMatch(entry -> entry.wager() == null));

        service.startFinalWriting(roomId, hostId, 30);
        service.applyAction(
                roomId,
                new GameAction("SET_FINAL_ANSWER", alex.playerId(), null, Map.of("text", "What is PROJ-1?")),
                null,
                alex.playerToken()
        );
        service.applyAction(
                roomId,
                new GameAction("SET_FINAL_ANSWER", sam.playerId(), null, Map.of("text", "What is SHOP-9?")),
                null,
                sam.playerToken()
        );

        GameSnapshot stolen = service.viewFor(roomId, alex.playerId(), null, sam.playerToken());
        assertFalse(stolen.finalRound().entries().isEmpty());
        assertTrue(stolen.finalRound().entries().stream().allMatch(entry ->
                entry.answer() == null || entry.answer().isBlank()));
        assertTrue(stolen.finalRound().entries().stream().allMatch(entry -> entry.wager() == null));
        assertFalse(stolen.toString().contains(alex.playerToken()));

        ResponseStatusException rejected = assertThrows(
                ResponseStatusException.class,
                () -> service.applyAction(
                        roomId,
                        new GameAction("SET_FINAL_ANSWER", alex.playerId(), null, Map.of("text", "replaced")),
                        null,
                        null
                )
        );
        assertTrue(rejected.getReason().toLowerCase().contains("credential"));

        ResponseStatusException crossed = assertThrows(
                ResponseStatusException.class,
                () -> service.applyAction(
                        roomId,
                        new GameAction("SET_FINAL_ANSWER", alex.playerId(), null, Map.of("text", "replaced")),
                        null,
                        sam.playerToken()
                )
        );
        assertTrue(crossed.getReason().toLowerCase().contains("credential"));

        GameSnapshot alexView = service.viewFor(roomId, alex.playerId(), null, alex.playerToken());
        assertEquals("What is PROJ-1?", answerFor(alexView, "Blue"));
        assertTrue(answerFor(alexView, "Red") == null || answerFor(alexView, "Red").isBlank());
        assertEquals(200, wagerFor(alexView, "Blue"));
        assertFalse(alexView.toString().contains(alex.playerToken()));

        GameSnapshot hostView = service.viewFor(roomId, hostId, created.hostToken(), null);
        assertEquals("What is PROJ-1?", answerFor(hostView, "Blue"));
        assertEquals("What is SHOP-9?", answerFor(hostView, "Red"));
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
