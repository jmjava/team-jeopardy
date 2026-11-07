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
import java.util.Map;
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
        ReflectionTestUtils.setField(service, "finalWritingSeconds", 30);
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
    void incorrectPlayerIsLockedOutForTheRestOfTheClue() {
        var created = service.createRoom("Pat", "Lockout");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        var alex = service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        var sam = service.joinRoom(created.snapshot().code(), "Sam", "Red");
        service.admitAll(roomId, hostId);
        service.installBoard(roomId, hostId, demoBoard("c1"));
        service.startGame(roomId, hostId);
        service.selectClue(roomId, hostId, "c1");
        service.openBuzzers(roomId, hostId);

        service.buzz(roomId, alex.playerId());
        GameSnapshot reopened = service.judge(roomId, hostId, false);
        assertEquals(GamePhase.CLUE_OPEN, reopened.phase());
        assertTrue(reopened.lockedOutPlayerIds().contains(alex.playerId()));

        ResponseStatusException locked = assertThrows(
                ResponseStatusException.class,
                () -> service.buzz(roomId, alex.playerId())
        );
        assertTrue(locked.getReason().contains("already answered"));

        GameSnapshot second = service.buzz(roomId, sam.playerId());
        assertEquals(sam.playerId(), second.activeClue().buzzedPlayerId());
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

    @Test
    void hostAndPublicSnapshotsShareOneRevision() {
        var created = service.createRoom("Pat", "Pair");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        var joined = service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        service.admitAll(roomId, hostId);
        service.installBoard(roomId, hostId, demoBoard("c1"));
        service.startGame(roomId, hostId);
        service.selectClue(roomId, hostId, "c1");

        GameRoomService.SnapshotPair preview = service.snapshotPair(roomId);
        assertEquals(preview.pub().revision(), preview.host().revision());
        assertEquals(GamePhase.HOST_PREVIEW, preview.pub().phase());
        assertEquals(GamePhase.HOST_PREVIEW, preview.host().phase());
        assertNull(preview.pub().activeClue().prompt());
        assertNotNull(preview.host().activeClue().prompt());

        service.openBuzzers(roomId, hostId);
        service.buzz(roomId, joined.playerId());
        GameRoomService.SnapshotPair locked = service.snapshotPair(roomId);
        assertEquals(locked.pub().revision(), locked.host().revision());
        assertEquals(GamePhase.BUZZ_LOCKED, locked.pub().phase());
        assertEquals(locked.pub().activeClue().buzzedPlayerId(), locked.host().activeClue().buzzedPlayerId());
    }

    @Test
    void concurrentMutationsNeverSplitHostAndPublicRevisions() throws Exception {
        var created = service.createRoom("Pat", "Burst");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        String code = created.snapshot().code();
        List<String> playerIds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            playerIds.add(service.joinRoom(code, "P" + i, i % 2 == 0 ? "Blue" : "Red").playerId());
        }
        service.admitAll(roomId, hostId);
        service.installBoard(roomId, hostId, demoBoard("c1"));
        service.startGame(roomId, hostId);
        service.selectClue(roomId, hostId, "c1");
        service.openBuzzers(roomId, hostId);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<GameRoomService.SnapshotPair>> pairs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String playerId = playerIds.get(i);
            pool.submit(() -> {
                go.await();
                try {
                    service.buzz(roomId, playerId);
                } catch (ResponseStatusException ignored) {
                    // losing buzz
                }
                return null;
            });
        }
        for (int i = 0; i < 12; i++) {
            pairs.add(pool.submit(() -> {
                go.await();
                return service.snapshotPair(roomId);
            }));
        }
        go.countDown();
        for (Future<GameRoomService.SnapshotPair> future : pairs) {
            GameRoomService.SnapshotPair pair = future.get(3, TimeUnit.SECONDS);
            assertEquals(pair.pub().revision(), pair.host().revision(), "broadcast pair split");
            assertEquals(pair.pub().phase(), pair.host().phase());
        }
        pool.shutdownNow();
        GameRoomService.SnapshotPair end = service.snapshotPair(roomId);
        assertEquals(end.pub().revision(), end.host().revision());
        assertEquals(GamePhase.BUZZ_LOCKED, end.pub().phase());
    }

    @Test
    void hostIdAloneDoesNotUnlockAnswersOrModeratorActions() {
        var created = service.createRoom("Pat", "Secret");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        service.installBoard(roomId, hostId, demoBoard("c1"));
        service.admitAll(roomId, hostId);
        service.startGame(roomId, hostId);
        service.selectClue(roomId, hostId, "c1");

        GameSnapshot leaked = service.viewFor(roomId, hostId, null);
        assertNull(leaked.activeClue().prompt());
        assertNull(leaked.activeClue().response());
        GameSnapshot host = service.viewFor(roomId, hostId, created.hostToken());
        assertEquals("prompt text", host.activeClue().prompt());
        assertEquals("What is x?", host.activeClue().response());

        assertThrows(ResponseStatusException.class, () -> service.applyAction(
                roomId,
                new GameAction("OPEN_BUZZERS", hostId, null, Map.of()),
                null
        ));
        assertEquals(GamePhase.HOST_PREVIEW, service.publicSnapshot(roomId).phase());
        service.applyAction(
                roomId,
                new GameAction("OPEN_BUZZERS", hostId, null, Map.of()),
                created.hostToken()
        );
        assertEquals(GamePhase.CLUE_OPEN, service.publicSnapshot(roomId).phase());
    }

    @Test
    void startIsRejectedOnceTheMatchLeavesTheLobby() {
        var created = service.createRoom("Pat", "Once");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        service.installBoard(roomId, hostId, demoBoard("c1"));
        service.admitAll(roomId, hostId);
        service.startGame(roomId, hostId);
        service.selectClue(roomId, hostId, "c1");

        ResponseStatusException again = assertThrows(
                ResponseStatusException.class,
                () -> service.startGame(roomId, hostId)
        );
        assertTrue(again.getReason().toLowerCase().contains("already started"));
        assertEquals(GamePhase.HOST_PREVIEW, service.publicSnapshot(roomId).phase());
        assertNotNull(service.publicSnapshot(roomId).activeClue());
    }

    @Test
    void joinAcceptsLowercaseRoomCode() {
        var created = service.createRoom("Pat", "Demo");
        String code = created.snapshot().code();
        var joined = service.joinRoom(code.toLowerCase(), "Alex", "Blue");
        assertNotNull(joined.playerId());
        assertEquals(created.snapshot().roomId(), joined.snapshot().roomId());
        assertTrue(service.findByCode("  " + code.toLowerCase() + " ").isPresent());
    }

    @Test
    void dailyDoubleUsesTypedWagerNotFaceValue() {
        var created = service.createRoom("Pat", "DD");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        var joined = service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        service.admitPlayer(roomId, hostId, joined.playerId());
        service.installBoard(roomId, hostId, dailyDoubleBoard("c1"));
        service.startGame(roomId, hostId);
        service.selectClue(roomId, hostId, "c1");
        service.openBuzzers(roomId, hostId);

        GameSnapshot claimed = service.buzz(roomId, joined.playerId());
        assertEquals(GamePhase.DD_WAGER, claimed.phase());
        assertNull(service.publicSnapshot(roomId).activeClue().prompt());

        GameSnapshot wagered = service.setWager(roomId, joined.playerId(), 400);
        assertEquals(GamePhase.BUZZ_LOCKED, wagered.phase());
        assertEquals(400, wagered.activeClue().wager());
        assertEquals("prompt text", service.publicSnapshot(roomId).activeClue().prompt());

        GameSnapshot judged = service.judge(roomId, hostId, true);
        assertEquals(400, judged.teams().getFirst().score());
        assertEquals(GamePhase.ANSWER_REVEALED, judged.phase());
    }

    @Test
    void dailyDoubleMissAppliesWagerAndDoesNotReopen() {
        var created = service.createRoom("Pat", "DD miss");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        var alex = service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        var sam = service.joinRoom(created.snapshot().code(), "Sam", "Red");
        service.admitAll(roomId, hostId);
        service.installBoard(roomId, hostId, dailyDoubleBoard("c1"));
        service.startGame(roomId, hostId);
        service.selectClue(roomId, hostId, "c1");
        service.selectWagerer(roomId, hostId, alex.playerId());
        service.setWager(roomId, alex.playerId(), 5);
        GameSnapshot judged = service.judge(roomId, hostId, false);
        assertEquals(-5, judged.teams().stream().filter(t -> t.name().equals("Blue")).findFirst().orElseThrow().score());
        assertEquals(GamePhase.ANSWER_REVEALED, judged.phase());
        assertThrows(ResponseStatusException.class, () -> service.buzz(roomId, sam.playerId()));
    }

    @Test
    void zeroScoreDailyDoubleAllowsUpToOneThousand() {
        var created = service.createRoom("Pat", "DD zero");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        var alex = service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        service.admitPlayer(roomId, hostId, alex.playerId());
        service.installBoard(roomId, hostId, dailyDoubleBoard("c1"));
        service.startGame(roomId, hostId);
        service.selectClue(roomId, hostId, "c1");
        service.openBuzzers(roomId, hostId);
        service.buzz(roomId, alex.playerId());
        assertThrows(ResponseStatusException.class, () -> service.setWager(roomId, alex.playerId(), 4));
        GameSnapshot ok = service.setWager(roomId, alex.playerId(), 1000);
        assertEquals(1000, ok.activeClue().wager());
    }

    @Test
    void typedFinalOneRightOneWrongAppliesWagersAndHidesTextUntilReveal() {
        var created = service.createRoom("Pat", "Final");
        String roomId = created.snapshot().roomId();
        String hostId = created.hostPlayerId();
        var alex = service.joinRoom(created.snapshot().code(), "Alex", "Blue");
        var sam = service.joinRoom(created.snapshot().code(), "Sam", "Red");
        service.admitAll(roomId, hostId);
        service.installBoard(roomId, hostId, finalBoard());
        service.startGame(roomId, hostId);

        GameSnapshot category = service.startFinal(roomId, hostId);
        assertEquals(GamePhase.FINAL_CATEGORY, category.phase());
        assertEquals("Release epic", category.finalRound().categoryTitle());
        assertNull(service.publicSnapshot(roomId).finalRound().prompt());

        service.startFinalWager(roomId, hostId);
        service.setWager(roomId, alex.playerId(), 200);
        service.setWager(roomId, sam.playerId(), 50);

        GameSnapshot writing = service.startFinalWriting(roomId, hostId, 30);
        assertEquals(GamePhase.FINAL_WRITING, writing.phase());
        assertNotNull(service.publicSnapshot(roomId).finalRound().prompt());

        service.setFinalAnswer(roomId, alex.playerId(), "What is PROJ-1?");
        service.setFinalAnswer(roomId, sam.playerId(), "What is SHOP-9?");

        GameSnapshot publicWriting = service.publicSnapshot(roomId);
        assertTrue(publicWriting.finalRound().entries().stream().noneMatch(e ->
                e.answer() != null && !e.answer().isBlank()));

        GameSnapshot alexView = service.requireSnapshot(roomId, alex.playerId());
        assertEquals("What is PROJ-1?", alexView.finalRound().entries().stream()
                .filter(e -> e.teamName().equals("Blue"))
                .findFirst().orElseThrow().answer());
        assertTrue(alexView.finalRound().entries().stream()
                .filter(e -> e.teamName().equals("Red"))
                .findFirst().orElseThrow().answer() == null
                || alexView.finalRound().entries().stream()
                .filter(e -> e.teamName().equals("Red"))
                .findFirst().orElseThrow().answer().isBlank());

        service.lockFinalAnswers(roomId, hostId);
        GameSnapshot locked = service.publicSnapshot(roomId);
        assertEquals(GamePhase.FINAL_LOCKED, locked.phase());
        assertTrue(locked.finalRound().entries().stream().allMatch(FinalEntry::answerLocked));
        assertTrue(locked.finalRound().entries().stream().allMatch(e -> e.answer() == null || e.answer().isBlank()));

        service.revealAnswer(roomId, hostId);
        String blueId = service.hostSnapshot(roomId).teams().stream()
                .filter(t -> t.name().equals("Blue")).findFirst().orElseThrow().id();
        String redId = service.hostSnapshot(roomId).teams().stream()
                .filter(t -> t.name().equals("Red")).findFirst().orElseThrow().id();
        service.judgeFinal(roomId, hostId, blueId, true);
        GameSnapshot done = service.judgeFinal(roomId, hostId, redId, false);
        assertEquals(GamePhase.FINISHED, done.phase());
        assertEquals(200, done.teams().stream().filter(t -> t.name().equals("Blue")).findFirst().orElseThrow().score());
        assertEquals(-50, done.teams().stream().filter(t -> t.name().equals("Red")).findFirst().orElseThrow().score());
        assertNotNull(service.publicSnapshot(roomId).finalRound().response());
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

    private static Board dailyDoubleBoard(String clueId) {
        return new Board(
                "Demo",
                "/tmp",
                List.of(new Category(
                        "cat",
                        "TEST",
                        List.of(new Clue(clueId, 800, "prompt text", "What is x?", "why", null, true))
                )),
                new Board.GraphDigest(1, 1, 1, 1, 1)
        );
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
