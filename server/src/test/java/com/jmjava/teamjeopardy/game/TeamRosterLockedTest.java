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

class TeamRosterLockedTest {

    private GameRoomService service;

    @BeforeEach
    void setUp() {
        service = new GameRoomService();
        ReflectionTestUtils.setField(service, "maxTeams", 6);
        ReflectionTestUtils.setField(service, "maxPlayersPerTeam", 8);
        ReflectionTestUtils.setField(service, "finalWritingSeconds", 30);
    }

    @Test
    void teamChoiceIsRefusedAfterTheMatchLeavesTheLobby() {
        var created = service.createRoom("Pat", "Roster");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        String code = created.snapshot().code();
        var alex = service.joinRoom(code, "Alex", "Blue");
        service.joinRoom(code, "Sam", "Red");
        service.admitAll(roomId, hostId);
        service.installBoard(roomId, hostId, board());
        service.startGame(roomId, hostId);

        String alexTeam = teamId(service.hostSnapshot(roomId), alex.playerId());
        ResponseStatusException switched = assertThrows(
                ResponseStatusException.class,
                () -> service.createTeam(roomId, alex.playerId(), "Red")
        );
        assertTrue(switched.getReason().contains("lobby"));
        assertEquals(alexTeam, teamId(service.hostSnapshot(roomId), alex.playerId()));

        ResponseStatusException joined = assertThrows(
                ResponseStatusException.class,
                () -> service.joinRoom(code, "Riley", "Red")
        );
        assertTrue(joined.getReason().contains("lobby"));
        assertEquals(1, playersNamed(service.hostSnapshot(roomId), "Red"));
    }

    private static String teamId(GameSnapshot snapshot, String playerId) {
        String id = snapshot.players().stream()
                .filter(player -> player.id().equals(playerId))
                .findFirst()
                .orElseThrow()
                .teamId();
        return snapshot.teams().stream()
                .filter(team -> team.id().equals(id))
                .findFirst()
                .orElseThrow()
                .name();
    }

    private static long playersNamed(GameSnapshot snapshot, String teamName) {
        String id = snapshot.teams().stream()
                .filter(team -> team.name().equals(teamName))
                .findFirst()
                .orElseThrow()
                .id();
        return snapshot.players().stream().filter(player -> id.equals(player.teamId())).count();
    }

    private static Board board() {
        return new Board(
                "Roster",
                "/tmp",
                List.of(new Category(
                        "cat",
                        "TEST",
                        List.of(new Clue("c1", 200, "prompt", "What is x?", null, null, false))
                )),
                new Board.GraphDigest(1, 1, 1, 1, 1)
        );
    }
}
