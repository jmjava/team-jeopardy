package com.jmjava.teamjeopardy.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.RestTemplateXhrTransport;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-shaped races: concurrent HTTP/STOMP buzz, shared revision across
 * remote subscribers, and a dropped client catching up via GET (the same
 * pull the Vue client now does on reconnect).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "team-jeopardy.persistence.path=${java.io.tmpdir}/team-jeopardy-sync-it.db"
)
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class RemoteClientSyncTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void concurrentHttpBuzzHasOneWinnerAndSharedRevision() throws Exception {
        Table table = openTable(6);
        openFirstClue(table);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (String playerId : table.playerIds) {
            futures.add(pool.submit(() -> {
                go.await();
                return postRaw("/api/rooms/" + table.roomId + "/actions", Map.of(
                        "playerId", playerId,
                        "type", "BUZZ",
                        "payload", Map.of()
                ));
            }));
        }
        go.countDown();
        int wins = 0;
        int rejects = 0;
        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> response = future.get(5, TimeUnit.SECONDS);
            if (response.getStatusCode().is2xxSuccessful()) {
                wins++;
            } else {
                rejects++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, wins);
        assertEquals(5, rejects);

        GameSnapshot host = getRoom(table.roomId, table.hostId);
        GameSnapshot pub = getRoom(table.roomId, table.playerIds.getFirst());
        assertEquals(GamePhase.BUZZ_LOCKED, host.phase());
        assertEquals(host.revision(), pub.revision());
        assertEquals(host.activeClue().buzzedPlayerId(), pub.activeClue().buzzedPlayerId());
        assertTrue(table.playerIds.contains(host.activeClue().buzzedPlayerId()));
    }

    @Test
    void stompRaceLeavesEverySubscriberOnTheSameWinner() throws Exception {
        Table table = openTable(2);
        SnapshotInbox hostInbox = connect("/topic/room." + table.roomId + ".host");
        SnapshotInbox p1 = connect("/topic/room." + table.roomId);
        SnapshotInbox p2 = connect("/topic/room." + table.roomId);
        SnapshotInbox display = connect("/topic/room." + table.roomId);
        Thread.sleep(200);

        openFirstClue(table);
        await(hostInbox, s -> s.phase() == GamePhase.CLUE_OPEN);

        send(p1.session, table.roomId, new GameAction("BUZZ", table.playerIds.get(0), null, Map.of()));
        send(p2.session, table.roomId, new GameAction("BUZZ", table.playerIds.get(1), null, Map.of()));

        GameSnapshot hostLocked = await(hostInbox, s -> s.phase() == GamePhase.BUZZ_LOCKED);
        GameSnapshot p1Locked = await(p1, s -> s.phase() == GamePhase.BUZZ_LOCKED
                && s.revision() >= hostLocked.revision());
        GameSnapshot p2Locked = await(p2, s -> s.phase() == GamePhase.BUZZ_LOCKED
                && s.revision() >= hostLocked.revision());
        GameSnapshot displayLocked = await(display, s -> s.phase() == GamePhase.BUZZ_LOCKED
                && s.revision() >= hostLocked.revision());

        assertEquals(hostLocked.activeClue().buzzedPlayerId(), p1Locked.activeClue().buzzedPlayerId());
        assertEquals(hostLocked.activeClue().buzzedPlayerId(), p2Locked.activeClue().buzzedPlayerId());
        assertEquals(hostLocked.activeClue().buzzedPlayerId(), displayLocked.activeClue().buzzedPlayerId());
        assertEquals(hostLocked.revision(), p1Locked.revision());
        assertEquals(hostLocked.revision(), p2Locked.revision());
        assertEquals(hostLocked.revision(), displayLocked.revision());
        assertTrue(p1.session.isConnected());
        assertTrue(p2.session.isConnected());

        hostInbox.disconnect();
        p1.disconnect();
        p2.disconnect();
        display.disconnect();
    }

    @Test
    void droppedClientResyncsMissedOpenBuzzers() throws Exception {
        Table table = openTable(2);
        SnapshotInbox hostInbox = connect("/topic/room." + table.roomId + ".host");
        SnapshotInbox playerInbox = connect("/topic/room." + table.roomId);
        Thread.sleep(200);

        post("/api/rooms/" + table.roomId + "/actions", Map.of(
                "playerId", table.hostId, "type", "SELECT_CLUE", "payload", Map.of("clueId", table.clueId)));
        await(hostInbox, s -> s.phase() == GamePhase.HOST_PREVIEW);
        await(playerInbox, s -> s.phase() == GamePhase.HOST_PREVIEW);

        playerInbox.disconnect();

        post("/api/rooms/" + table.roomId + "/actions", Map.of(
                "playerId", table.hostId, "type", "OPEN_BUZZERS", "payload", Map.of()));
        GameSnapshot hostOpen = await(hostInbox, s -> s.phase() == GamePhase.CLUE_OPEN);

        GameSnapshot resync = getRoom(table.roomId, table.playerIds.getFirst());
        assertEquals(GamePhase.CLUE_OPEN, resync.phase(), "GET after drop must catch OPEN_BUZZERS");
        assertEquals(hostOpen.revision(), resync.revision());
        assertEquals(hostOpen.activeClue().clueId(), resync.activeClue().clueId());
        assertNotNull(resync.activeClue().prompt());

        SnapshotInbox reconnected = connect("/topic/room." + table.roomId);
        Thread.sleep(150);
        post("/api/rooms/" + table.roomId + "/actions", Map.of(
                "playerId", table.playerIds.getFirst(), "type", "SYNC", "payload", Map.of()));
        GameSnapshot live = await(reconnected, s -> s.phase() == GamePhase.CLUE_OPEN
                && s.revision() >= hostOpen.revision());
        assertEquals(hostOpen.activeClue().clueId(), live.activeClue().clueId());

        hostInbox.disconnect();
        reconnected.disconnect();
    }

    private Table openTable(int players) throws Exception {
        JsonNode created = post("/api/rooms", Map.of("hostName", "Pat Host", "title", "Sync table"));
        String roomId = created.path("snapshot").path("roomId").asText();
        String code = created.path("snapshot").path("code").asText();
        String hostId = created.path("hostPlayerId").asText();
        List<String> playerIds = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            JsonNode joined = post("/api/rooms/join", Map.of(
                    "code", code,
                    "displayName", "P" + i,
                    "teamName", i % 2 == 0 ? "Blue Owls" : "Red Foxes"
            ));
            playerIds.add(joined.path("playerId").asText());
        }
        JsonNode saved = post("/api/question-bank", Map.of(
                "title", "Sync drill",
                "sourceKind", "manual",
                "sourceKey", "manual:sync-" + System.nanoTime(),
                "categories", List.of(Map.of(
                        "title", "DEV: Sync",
                        "clues", List.of(Map.of(
                                "value", 200,
                                "prompt", "Who won the buzz race?",
                                "response", "What is one player?",
                                "dailyDouble", false
                        ))
                ))
        ));
        post("/api/rooms/load-board", Map.of(
                "roomId", roomId,
                "playerId", hostId,
                "savedBoardId", saved.path("id").asText()
        ));
        post("/api/rooms/" + roomId + "/actions", Map.of("playerId", hostId, "type", "ADMIT_ALL", "payload", Map.of()));
        post("/api/rooms/" + roomId + "/actions", Map.of("playerId", hostId, "type", "START", "payload", Map.of()));
        GameSnapshot board = getRoom(roomId, hostId);
        String clueId = board.board().categories().getFirst().clues().getFirst().id();
        return new Table(roomId, hostId, playerIds, clueId);
    }

    private void openFirstClue(Table table) throws Exception {
        post("/api/rooms/" + table.roomId + "/actions", Map.of(
                "playerId", table.hostId, "type", "SELECT_CLUE", "payload", Map.of("clueId", table.clueId)));
        post("/api/rooms/" + table.roomId + "/actions", Map.of(
                "playerId", table.hostId, "type", "OPEN_BUZZERS", "payload", Map.of()));
    }

    private JsonNode post(String path, Map<String, ?> body) throws Exception {
        ResponseEntity<String> response = postRaw(path, body);
        assertTrue(response.getStatusCode().is2xxSuccessful(), path + " -> " + response.getStatusCode() + " " + response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    private ResponseEntity<String> postRaw(String path, Map<String, ?> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(
                "http://localhost:" + port + path,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                String.class
        );
    }

    private GameSnapshot getRoom(String roomId, String playerId) {
        String q = playerId == null ? "" : "?playerId=" + playerId;
        return rest.getForObject("http://localhost:" + port + "/api/rooms/" + roomId + q, GameSnapshot.class);
    }

    private SnapshotInbox connect(String destination) throws Exception {
        List<Transport> transports = List.of(new RestTemplateXhrTransport());
        WebSocketStompClient client = new WebSocketStompClient(new SockJsClient(transports));
        MappingJackson2MessageConverter json = new MappingJackson2MessageConverter();
        json.setObjectMapper(objectMapper);
        client.setMessageConverter(json);

        BlockingQueue<GameSnapshot> queue = new LinkedBlockingQueue<>();
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        StompSession session = client.connectAsync(
                "http://127.0.0.1:" + port + "/ws",
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(StompSession sess, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
                        errors.offer(exception);
                    }

                    @Override
                    public void handleTransportError(StompSession sess, Throwable exception) {
                        errors.offer(exception);
                    }
                }
        ).get(8, TimeUnit.SECONDS);
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return GameSnapshot.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer((GameSnapshot) payload);
            }
        });
        return new SnapshotInbox(client, session, queue, errors);
    }

    private static void send(StompSession session, String roomId, GameAction action) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/room/" + roomId + "/action");
        headers.setContentType(MediaType.APPLICATION_JSON);
        session.send(headers, action);
    }

    private static GameSnapshot await(SnapshotInbox inbox, Predicate<GameSnapshot> match) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        GameSnapshot snapshot = inbox.queue.poll(15, TimeUnit.SECONDS);
        while (snapshot != null && !match.test(snapshot)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                snapshot = null;
                break;
            }
            snapshot = inbox.queue.poll(remaining, TimeUnit.NANOSECONDS);
        }
        assertNotNull(snapshot, "timed out waiting for STOMP snapshot"
                + (inbox.errors.peek() == null ? "" : "; " + inbox.errors.peek()));
        return snapshot;
    }

    private record Table(String roomId, String hostId, List<String> playerIds, String clueId) {
    }

    private record SnapshotInbox(
            WebSocketStompClient client,
            StompSession session,
            BlockingQueue<GameSnapshot> queue,
            BlockingQueue<Throwable> errors
    ) {
        void disconnect() {
            if (session.isConnected()) {
                session.disconnect();
            }
            client.stop();
        }
    }
}
