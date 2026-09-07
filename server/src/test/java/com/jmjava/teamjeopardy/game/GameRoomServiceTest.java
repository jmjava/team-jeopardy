package com.jmjava.teamjeopardy.game;

import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        assertThrows(ResponseStatusException.class, () -> service.judge(roomId, hostId, true));
        assertEquals(200, service.hostSnapshot(roomId).teams().getFirst().score());
    }

    @Test
    void concurrentBuzzHasSingleWinner() throws Exception {
        var created = service.createRoom("Pat", "Race");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        String code = created.snapshot().code();

        List<String> playerIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            var joined = service.joinRoom(code, "P" + i, i % 2 == 0 ? "Blue" : "Red");
            playerIds.add(joined.playerId());
        }
        service.admitAll(roomId, hostId);
        service.installBoard(roomId, hostId, demoBoard("c1"));
        service.startGame(roomId, hostId);
        service.selectClue(roomId, hostId, "c1");
        service.openBuzzers(roomId, hostId);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (String playerId : playerIds) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    service.buzz(roomId, playerId);
                    wins.incrementAndGet();
                } catch (ResponseStatusException ex) {
                    conflicts.incrementAndGet();
                }
                return null;
            }));
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        go.countDown();
        for (Future<?> future : futures) {
            future.get(3, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(1, wins.get(), "exactly one buzz should win");
        assertEquals(7, conflicts.get());
        GameSnapshot locked = service.publicSnapshot(roomId);
        assertEquals(GamePhase.BUZZ_LOCKED, locked.phase());
        assertNotNull(locked.activeClue().buzzedPlayerId());
        assertTrue(playerIds.contains(locked.activeClue().buzzedPlayerId()));
    }

    private static Board demoBoard(String clueId) {
        return new Board(
                "Demo",
                "/tmp",
                List.of(new Category(
                        "cat",
                        "TEST",
                        List.of(new Clue(clueId, 200, "prompt text", "What is x?", "why", null, false))
                )),
                new Board.GraphDigest(1, 1, 1, 1, 1)
        );
    }
}
