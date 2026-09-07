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
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
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
class MultiplayerStompIT {

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

        post("/api/rooms/ingest", Map.of(
                "roomId", roomId,
                "playerId", hostId,
                "useSample", true,
                "sampleType", "maven",
                "boardTitle", "STOMP Maven"
        ));
        post("/api/rooms/" + roomId + "/actions", Map.of("playerId", hostId, "type", "ADMIT_ALL"));

        SnapshotInbox hostInbox = connect("/topic/room." + roomId + ".host");
        SnapshotInbox playerInbox = connect("/topic/room." + roomId);
        SnapshotInbox p2Inbox = connect("/topic/room." + roomId);
        SnapshotInbox displayInbox = connect("/topic/room." + roomId);

        hostInbox.session.send("/app/room/" + roomId + "/action", Map.of(
                "type", "START", "playerId", hostId, "payload", Map.of()
        ));
        GameSnapshot board = await(hostInbox, s -> s.phase() == GamePhase.BOARD);
        String clueId = firstOpenClueId(board);
        assertNotNull(clueId);

        hostInbox.session.send("/app/room/" + roomId + "/action", Map.of(
                "type", "SELECT_CLUE", "playerId", hostId, "payload", Map.of("clueId", clueId)
        ));
        GameSnapshot hostPreview = await(hostInbox, s -> s.phase() == GamePhase.HOST_PREVIEW);
        GameSnapshot playerPreview = await(playerInbox, s -> s.phase() == GamePhase.HOST_PREVIEW);
        GameSnapshot displayPreview = await(displayInbox, s -> s.phase() == GamePhase.HOST_PREVIEW);
        assertNotNull(hostPreview.activeClue().prompt());
        assertNotNull(hostPreview.activeClue().response());
        assertNull(playerPreview.activeClue().prompt());
        assertNull(displayPreview.activeClue().response());

        hostInbox.session.send("/app/room/" + roomId + "/action", Map.of(
                "type", "OPEN_BUZZERS", "playerId", hostId, "payload", Map.of()
        ));
        await(playerInbox, s -> s.phase() == GamePhase.CLUE_OPEN);

        playerInbox.session.send("/app/room/" + roomId + "/action", Map.of(
                "type", "BUZZ", "playerId", p1Id, "payload", Map.of()
        ));
        p2Inbox.session.send("/app/room/" + roomId + "/action", Map.of(
                "type", "BUZZ", "playerId", p2Id, "payload", Map.of()
        ));

        GameSnapshot locked = await(hostInbox, s -> s.phase() == GamePhase.BUZZ_LOCKED);
        GameSnapshot publicLocked = await(playerInbox, s -> s.phase() == GamePhase.BUZZ_LOCKED);
        assertEquals(locked.activeClue().buzzedPlayerId(), publicLocked.activeClue().buzzedPlayerId());
        assertTrue(locked.activeClue().buzzedPlayerId().equals(p1Id)
                || locked.activeClue().buzzedPlayerId().equals(p2Id));

        hostInbox.session.send("/app/room/" + roomId + "/action", Map.of(
                "type", "JUDGE", "playerId", hostId, "payload", Map.of("correct", true)
        ));
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
        assertTrue(response.getStatusCode().is2xxSuccessful(), path + " -> " + response.getStatusCode());
        return objectMapper.readTree(response.getBody());
    }

    private SnapshotInbox connect(String destination) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        client.setMessageConverter(converter);
        StompSession session = client.connectAsync(
                "ws://127.0.0.1:" + port + "/stomp",
                new StompSessionHandlerAdapter() {
                }
        ).get(8, TimeUnit.SECONDS);
        BlockingQueue<GameSnapshot> queue = new LinkedBlockingQueue<>();
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
        return new SnapshotInbox(client, session, queue);
    }

    private static GameSnapshot await(SnapshotInbox inbox, Predicate<GameSnapshot> match) throws Exception {
        GameSnapshot snapshot = inbox.queue.poll(12, TimeUnit.SECONDS);
        while (snapshot != null && !match.test(snapshot)) {
            snapshot = inbox.queue.poll(12, TimeUnit.SECONDS);
        }
        assertNotNull(snapshot, "timed out waiting for STOMP snapshot");
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

    private record SnapshotInbox(WebSocketStompClient client, StompSession session, BlockingQueue<GameSnapshot> queue) {
        void disconnect() {
            if (session.isConnected()) {
                session.disconnect();
            }
            client.stop();
        }
    }
}
