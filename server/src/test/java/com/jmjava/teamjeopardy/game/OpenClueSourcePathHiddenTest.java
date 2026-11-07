package com.jmjava.teamjeopardy.game;

import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenClueSourcePathHiddenTest {

    private static final String SOURCE = "src/main/java/com/example/Singleton.java";

    private GameRoomService service;

    @BeforeEach
    void setUp() {
        service = new GameRoomService();
        ReflectionTestUtils.setField(service, "maxTeams", 6);
        ReflectionTestUtils.setField(service, "maxPlayersPerTeam", 8);
        ReflectionTestUtils.setField(service, "finalWritingSeconds", 30);
    }

    @Test
    void openClueHidesTheSourcePathUntilTheAnswerIsRevealed() {
        var created = service.createRoom("Pat", "Source");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        var alex = service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        service.admitPlayer(roomId, hostId, alex.playerId());
        service.installBoard(roomId, hostId, board());
        service.startGame(roomId, hostId);
        service.selectClue(roomId, hostId, "c1");
        service.openBuzzers(roomId, hostId);

        GameSnapshot pub = service.publicSnapshot(roomId);
        assertEquals("This class lives under `src/main`.", pub.activeClue().prompt());
        assertNull(pub.activeClue().response());
        assertNull(pub.activeClue().sourcePath());

        GameSnapshot player = service.viewFor(roomId, alex.playerId(), null, alex.playerToken());
        assertNull(player.activeClue().sourcePath());
        assertNull(player.activeClue().response());

        GameSnapshot host = service.viewFor(roomId, hostId, created.hostToken(), null);
        assertEquals(SOURCE, host.activeClue().sourcePath());
        assertEquals("What is Singleton?", host.activeClue().response());

        service.buzz(roomId, alex.playerId());
        assertNull(service.publicSnapshot(roomId).activeClue().sourcePath());

        service.judge(roomId, hostId, true);
        GameSnapshot revealed = service.publicSnapshot(roomId);
        assertEquals("What is Singleton?", revealed.activeClue().response());
        assertEquals(SOURCE, revealed.activeClue().sourcePath());
    }

    private static Board board() {
        return new Board(
                "Source",
                "/tmp",
                List.of(new Category(
                        "cat",
                        "NAME THAT TYPE",
                        List.of(new Clue(
                                "c1",
                                200,
                                "This class lives under `src/main`.",
                                "What is Singleton?",
                                "Qualified: com.example.Singleton",
                                SOURCE,
                                false
                        ))
                )),
                new Board.GraphDigest(1, 1, 1, 1, 1)
        );
    }
}
