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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level simulation of a full two-team match: admit gate, host preview
 * redaction, incorrect reopen, Daily Double badge, host reveal, late admit,
 * and FINISHED after the last clue.
 */
class FullGamePlayTest {

    private GameRoomService service;
    private String roomId;
    private String hostId;
    private String alexId;
    private String samId;
    private String rileyId;

    @BeforeEach
    void setUp() {
        service = new GameRoomService();
        ReflectionTestUtils.setField(service, "maxTeams", 6);
        ReflectionTestUtils.setField(service, "maxPlayersPerTeam", 8);
    }

    @Test
    void twoTeamsPlayEveryClueToFinishedWithRealisticOutcomes() {
        var created = service.createRoom("Pat", "Friday office Jeopardy");
        roomId = created.snapshot().roomId();
        hostId = created.hostPlayerId();

        alexId = service.joinRoom(created.snapshot().code(), "Alex", "Blue Owls").playerId();
        samId = service.joinRoom(created.snapshot().code(), "Sam", "Red Foxes").playerId();
        rileyId = service.joinRoom(created.snapshot().code(), "Riley", "Blue Owls").playerId();

        assertFalse(player(alexId).admitted());
        service.admitPlayer(roomId, hostId, alexId);
        service.admitPlayer(roomId, hostId, samId);
        // Riley waits in lobby — late admit after the first clue.

        service.installBoard(roomId, hostId, officeBoard());
        GameSnapshot started = service.startGame(roomId, hostId);
        assertEquals(GamePhase.BOARD, started.phase());
        assertEquals(10, started.cells().size());

        // 1. Patterns $200 — unadmitted Riley cannot buzz; Alex misses, Sam takes it.
        openClue("p200");
        assertThrows(ResponseStatusException.class, () -> service.buzz(roomId, rileyId));
        assertThrows(ResponseStatusException.class, () -> service.buzz(roomId, hostId));
        buzzAndJudge(alexId, false);
        assertEquals(GamePhase.CLUE_OPEN, phase());
        buzzAndJudge(samId, true);
        returnBoard();
        assertScores(-200, 200);

        // 2. Contracts $400 — Alex gets it on the first buzz.
        openClue("q400");
        buzzAndJudge(alexId, true);
        returnBoard();
        assertScores(200, 200);

        // Late admit — Riley can now play for Blue.
        service.admitPlayer(roomId, hostId, rileyId);
        assertTrue(player(rileyId).admitted());

        // 3. Patterns $600 Daily Double — badge is public; Riley converts.
        GameSnapshot preview = service.selectClue(roomId, hostId, "p600");
        assertTrue(preview.activeClue().dailyDouble());
        assertTrue(service.publicSnapshot(roomId).activeClue().dailyDouble());
        assertEquals("prompt text p600", preview.activeClue().prompt());
        assertNull(service.publicSnapshot(roomId).activeClue().prompt());
        service.openBuzzers(roomId, hostId);
        assertEquals("prompt text p600", service.publicSnapshot(roomId).activeClue().prompt());
        assertNull(service.publicSnapshot(roomId).activeClue().response());
        buzzAndJudge(rileyId, true);
        assertTrue(service.publicSnapshot(roomId).activeClue().responseVisible());
        returnBoard();
        assertScores(800, 200);

        // 4. Contracts $200 — nobody knows it; host reveals.
        openClue("q200");
        GameSnapshot revealed = service.revealAnswer(roomId, hostId);
        assertEquals(GamePhase.ANSWER_REVEALED, revealed.phase());
        assertTrue(revealed.activeClue().responseVisible());
        returnBoard();
        assertScores(800, 200);

        // 5. Patterns $800 — Sam misses and the host closes the clue.
        openClue("p800");
        buzzAndJudge(samId, false);
        service.revealAnswer(roomId, hostId);
        assertThrows(ResponseStatusException.class, () -> service.judge(roomId, hostId, true));
        returnBoard();
        assertScores(800, -600);

        // 6–10. Remaining board: mix of conversions down the columns.
        playCorrect("p400", alexId);
        assertScores(1200, -600);
        playCorrect("p1000", rileyId);
        assertScores(2200, -600);
        playCorrect("q600", samId);
        assertScores(2200, 0);

        openClue("q800");
        buzzAndJudge(alexId, false);
        buzzAndJudge(samId, true);
        returnBoard();
        assertScores(1400, 800);

        openClue("q1000");
        buzzAndJudge(rileyId, true);
        GameSnapshot finished = service.returnToBoard(roomId, hostId);
        assertEquals(GamePhase.FINISHED, finished.phase());
        assertTrue(finished.cells().stream().allMatch(BoardCellState::answered));
        assertScores(2400, 800);

        assertThrows(ResponseStatusException.class, () -> service.selectClue(roomId, hostId, "p200"));
        assertThrows(ResponseStatusException.class, () ->
                service.joinRoom(finished.code(), "Late", "Green Beans"));
    }

    private void playCorrect(String clueId, String playerId) {
        openClue(clueId);
        buzzAndJudge(playerId, true);
        returnBoard();
    }

    private void openClue(String clueId) {
        GameSnapshot hostPreview = service.selectClue(roomId, hostId, clueId);
        assertEquals(GamePhase.HOST_PREVIEW, hostPreview.phase());
        assertNotNull(hostPreview.activeClue().prompt());
        assertNotNull(hostPreview.activeClue().response());

        GameSnapshot pub = service.publicSnapshot(roomId);
        assertEquals(GamePhase.HOST_PREVIEW, pub.phase());
        assertNull(pub.activeClue().prompt());
        assertNull(pub.activeClue().response());
        assertEquals(hostPreview.activeClue().value(), pub.activeClue().value());

        GameSnapshot open = service.openBuzzers(roomId, hostId);
        assertEquals(GamePhase.CLUE_OPEN, open.phase());
        assertEquals(hostPreview.activeClue().prompt(), service.publicSnapshot(roomId).activeClue().prompt());
        assertNull(service.publicSnapshot(roomId).activeClue().response());
    }

    private void buzzAndJudge(String playerId, boolean correct) {
        GameSnapshot locked = service.buzz(roomId, playerId);
        assertEquals(GamePhase.BUZZ_LOCKED, locked.phase());
        assertEquals(playerId, locked.activeClue().buzzedPlayerId());
        GameSnapshot judged = service.judge(roomId, hostId, correct);
        if (correct) {
            assertEquals(GamePhase.ANSWER_REVEALED, judged.phase());
            assertTrue(judged.activeClue().responseVisible());
        } else {
            assertEquals(GamePhase.CLUE_OPEN, judged.phase());
            assertNull(judged.activeClue().buzzedPlayerId());
        }
    }

    private void returnBoard() {
        GameSnapshot snap = service.returnToBoard(roomId, hostId);
        assertTrue(snap.phase() == GamePhase.BOARD || snap.phase() == GamePhase.FINISHED);
    }

    private GamePhase phase() {
        return service.hostSnapshot(roomId).phase();
    }

    private Player player(String playerId) {
        return service.hostSnapshot(roomId).players().stream()
                .filter(p -> p.id().equals(playerId))
                .findFirst()
                .orElseThrow();
    }

    private void assertScores(int blue, int red) {
        Map<String, Integer> scores = teamScores();
        assertEquals(blue, scores.getOrDefault("Blue Owls", 0), "Blue Owls");
        assertEquals(red, scores.getOrDefault("Red Foxes", 0), "Red Foxes");
    }

    private Map<String, Integer> teamScores() {
        return service.hostSnapshot(roomId).teams().stream()
                .collect(java.util.stream.Collectors.toMap(Team::name, Team::score));
    }

    private static Board officeBoard() {
        return new Board(
                "Friday office Jeopardy",
                "/tmp/office",
                List.of(
                        new Category("patterns", "DEV: Patterns", List.of(
                                clue("p200", 200, false),
                                clue("p400", 400, false),
                                clue("p600", 600, true),
                                clue("p800", 800, false),
                                clue("p1000", 1000, false)
                        )),
                        new Category("contracts", "QA: Contracts", List.of(
                                clue("q200", 200, false),
                                clue("q400", 400, false),
                                clue("q600", 600, false),
                                clue("q800", 800, false),
                                clue("q1000", 1000, false)
                        ))
                ),
                new Board.GraphDigest(8, 6, 4, 3, 2)
        );
    }

    private static Clue clue(String id, int value, boolean dailyDouble) {
        return new Clue(
                id,
                value,
                "prompt text " + id,
                "What is " + id + "?",
                "because " + id,
                "src/" + id + ".java",
                dailyDouble
        );
    }
}
