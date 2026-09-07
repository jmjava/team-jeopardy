package com.jmjava.teamjeopardy.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "team-jeopardy.persistence.path=${java.io.tmpdir}/team-jeopardy-stomp-it.db"
)
class MultiplayerStompTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void hostPlayersAndDisplayShareOneRealtimeTable() throws Exception {
        JsonNode created = post("/api/rooms", Map.of("hostName", "Pat Host", "title", "STOMP Table"));
        String roomId = created.path("snapshot").path("roomId").asText();
        String code = created.path("snapshot").path("code").asText();
        String hostId = created.path("hostPlayerId").asText();

        JsonNode p1 = post("/api/rooms/join", Map.of("code", code, "displayName", "Alex", "teamName", "Blue Owls"));
        JsonNode p2 = post("/api/rooms/join", Map.of("code", code, "displayName", "Sam", "teamName", "Red Foxes"));
        String p1Id = p1.path("playerId").asText();
        String p2Id = p2.path("playerId").asText();

        SnapshotInbox hostInbox = connect("/topic/room." + roomId + ".host");
        SnapshotInbox playerInbox = connect("/topic/room." + roomId);
        SnapshotInbox p2Inbox = connect("/topic/room." + roomId);
        SnapshotInbox displayInbox = connect("/topic/room." + roomId);
        Thread.sleep(250);

        post("/api/rooms/ingest", Map.of(
                "roomId", roomId,
                "playerId", hostId,
                "useSample", true,
                "sampleType", "maven",
                "boardTitle", "STOMP Maven"
        ));
        await(hostInbox, s -> s.board() != null && s.board().categories() != null && !s.board().categories().isEmpty());

        send(hostInbox.session, roomId, new GameAction("ADMIT_ALL", hostId, null, Map.of()));
        await(hostInbox, s -> s.players().stream().filter(p -> !p.host() && p.admitted()).count() >= 2);

        send(hostInbox.session, roomId, new GameAction("START", hostId, null, Map.of()));
        GameSnapshot board = await(hostInbox, s -> s.phase() == GamePhase.BOARD);
        String clueId = firstOpenClueId(board);
        assertNotNull(clueId);

        send(hostInbox.session, roomId, new GameAction("SELECT_CLUE", hostId, null, Map.of("clueId", clueId)));
        GameSnapshot hostPreview = await(hostInbox, s -> s.phase() == GamePhase.HOST_PREVIEW);
        GameSnapshot playerPreview = await(playerInbox, s -> s.phase() == GamePhase.HOST_PREVIEW);
        GameSnapshot displayPreview = await(displayInbox, s -> s.phase() == GamePhase.HOST_PREVIEW);
        assertNotNull(hostPreview.activeClue().prompt());
        assertNotNull(hostPreview.activeClue().response());
        assertNull(playerPreview.activeClue().prompt());
        assertNull(displayPreview.activeClue().response());

        send(hostInbox.session, roomId, new GameAction("OPEN_BUZZERS", hostId, null, Map.of()));
        await(playerInbox, s -> s.phase() == GamePhase.CLUE_OPEN);

        send(playerInbox.session, roomId, new GameAction("BUZZ", p1Id, null, Map.of()));
        send(p2Inbox.session, roomId, new GameAction("BUZZ", p2Id, null, Map.of()));

        GameSnapshot locked = await(hostInbox, s -> s.phase() == GamePhase.BUZZ_LOCKED);
        GameSnapshot publicLocked = await(playerInbox, s -> s.phase() == GamePhase.BUZZ_LOCKED);
        assertEquals(locked.activeClue().buzzedPlayerId(), publicLocked.activeClue().buzzedPlayerId());
        assertTrue(locked.activeClue().buzzedPlayerId().equals(p1Id)
                || locked.activeClue().buzzedPlayerId().equals(p2Id));

        send(hostInbox.session, roomId, new GameAction("JUDGE", hostId, null, Map.of("correct", true)));
        GameSnapshot revealed = await(displayInbox, s -> s.phase() == GamePhase.ANSWER_REVEALED);
        assertTrue(revealed.activeClue().responseVisible());
        assertEquals(1, revealed.teams().stream().filter(t -> t.score() != 0).count());

        hostInbox.disconnect();
        playerInbox.disconnect();
        p2Inbox.disconnect();
        displayInbox.disconnect();
    }

    private JsonNode post(String path, Map<String, ?> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + path,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                String.class
        );
        assertTrue(response.getStatusCode().is2xxSuccessful(), path + " -> " + response.getStatusCode() + " " + response.getBody());
        return objectMapper.readTree(response.getBody());
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
        Throwable conversionError = inbox.errors.peek();
        assertNotNull(
                snapshot,
                "timed out waiting for STOMP snapshot"
                        + (conversionError == null ? "" : "; conversion error: " + conversionError)
        );
        return snapshot;
    }

    private static String firstOpenClueId(GameSnapshot snapshot) {
        if (snapshot.board() == null) {
            return null;
        }
        return snapshot.board().categories().stream()
                .flatMap(cat -> cat.clues().stream())
                .map(clue -> clue.id())
                .filter(id -> snapshot.cells().stream().noneMatch(c -> c.clueId().equals(id) && c.answered()))
                .findFirst()
                .orElse(null);
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
