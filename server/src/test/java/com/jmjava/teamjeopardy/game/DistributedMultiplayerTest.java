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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Separate TCP clients on the native STOMP endpoint (not an in-process service
 * call and not SockJS XHR). Covers a full table through FINISHED, a dropped
 * player catching up, and the host feed staying closed to everyone else.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "team-jeopardy.persistence.path=${java.io.tmpdir}/team-jeopardy-distributed-it.db"
)
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class DistributedMultiplayerTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    private final Map<String, String> playerTokens = new HashMap<>();

    @Test
    void nativeClientsPlayToFinishedWithoutLeakingTheHostFeed() throws Exception {
        JsonNode saved = post("/api/question-bank", Map.of(
                "title", "Distributed drill",
                "sourceKind", "manual",
                "sourceKey", "manual:distributed-" + System.nanoTime(),
                "categories", List.of(
                        Map.of("title", "DEV: Sync", "clues", List.of(
                                Map.of("value", 200, "prompt", "Who may open the host feed?",
                                        "response", "What is the room creator?", "dailyDouble", false),
                                Map.of("value", 400, "prompt", "Daily Double on the wire",
                                        "response", "What is a badge?", "dailyDouble", true)
                        )),
                        Map.of("title", "QA: Catch-up", "clues", List.of(
                                Map.of("value", 200, "prompt", "A dropped player catches up how?",
                                        "response", "What is GET resync?", "dailyDouble", false),
                                Map.of("value", 400, "prompt", "Last clue of the distributed drill",
                                        "response", "What is FINISHED?", "dailyDouble", false)
                        ))
                )
        ), null);
        String savedId = saved.path("id").asText();

        JsonNode created = post("/api/rooms", Map.of("hostName", "Pat Host", "title", "Distributed"), null);
        String roomId = created.path("snapshot").path("roomId").asText();
        String code = created.path("snapshot").path("code").asText();
        String hostId = created.path("hostPlayerId").asText();
        String hostToken = created.path("hostToken").asText();
        assertFalse(hostToken.isBlank());
        assertFalse(created.path("snapshot").toString().contains(hostToken), "snapshot must not carry the host token");

        JsonNode alexJoin = post("/api/rooms/join", Map.of("code", code, "displayName", "Alex", "teamName", "Blue Owls"), null);
        JsonNode samJoin = post("/api/rooms/join", Map.of("code", code, "displayName", "Sam", "teamName", "Red Foxes"), null);
        String alexId = alexJoin.path("playerId").asText();
        String samId = samJoin.path("playerId").asText();
        playerTokens.put(alexId, alexJoin.path("playerToken").asText());
        playerTokens.put(samId, samJoin.path("playerToken").asText());
        assertFalse(alexJoin.path("snapshot").toString().contains(alexJoin.path("playerToken").asText()));

        ResponseEntity<String> stolenLoad = postRaw("/api/rooms/load-board", Map.of(
                "roomId", roomId,
                "playerId", hostId,
                "savedBoardId", savedId
        ), null);
        assertEquals(409, stolenLoad.getStatusCode().value(), stolenLoad.getBody());

        post("/api/rooms/load-board", Map.of(
                "roomId", roomId,
                "playerId", hostId,
                "savedBoardId", savedId
        ), hostToken);

        GameSnapshot publicBoard = getRoom(roomId, hostId, null);
        assertNull(publicBoard.board().categories().getFirst().clues().getFirst().prompt(),
                "knowing the host id must not reveal clue text");
        GameSnapshot hostBoard = getRoom(roomId, hostId, hostToken);
        assertNotNull(hostBoard.board().categories().getFirst().clues().getFirst().prompt());

        Inbox host = connect("/topic/room." + roomId + ".host", hostId, hostToken);
        Inbox display = connect("/topic/room." + roomId, null, null);
        Inbox alex = connect("/topic/room." + roomId, null, null);
        Inbox sam = connect("/topic/room." + roomId, null, null);
        Inbox spy = connect("/topic/**", null, null);
        spy.subscribe("/topic/room." + roomId + ".*", null, null);
        spy.subscribe("/topic/room." + roomId + ".host", hostId, null);
        Thread.sleep(250);

        ResponseEntity<String> stolenStart = postRaw("/api/rooms/" + roomId + "/actions", Map.of(
                "playerId", hostId,
                "type", "START",
                "payload", Map.of()
        ), null);
        assertEquals(409, stolenStart.getStatusCode().value(), stolenStart.getBody());
        assertEquals(GamePhase.LOBBY, getRoom(roomId, null, null).phase());

        send(host.session, roomId, new GameAction("ADMIT_ALL", hostId, null, Map.of()), hostToken);
        await(host, s -> s.players().stream().filter(p -> !p.host() && p.admitted()).count() >= 2);
        send(host.session, roomId, new GameAction("START", hostId, null, Map.of()), hostToken);
        GameSnapshot board = await(host, s -> s.phase() == GamePhase.BOARD);
        await(display, s -> s.phase() == GamePhase.BOARD && s.revision() == board.revision());

        Map<String, Integer> expected = new HashMap<>();
        expected.put("Blue Owls", 0);
        expected.put("Red Foxes", 0);
        List<RealPlayHttpSupport.ClueRef> order = RealPlayHttpSupport.jeopardyOrder(board);
        assertEquals(4, order.size());

        // Clue 1: preview redaction, miss, reopen, other team scores.
        RealPlayHttpSupport.ClueRef first = order.getFirst();
        send(host.session, roomId, new GameAction("SELECT_CLUE", hostId, null, Map.of("clueId", first.id())), hostToken);
        GameSnapshot hostPreview = await(host, s -> s.phase() == GamePhase.HOST_PREVIEW);
        GameSnapshot publicPreview = await(display, s -> s.phase() == GamePhase.HOST_PREVIEW
                && s.revision() == hostPreview.revision());
        assertNotNull(hostPreview.activeClue().response());
        assertNull(publicPreview.activeClue().prompt());
        assertNull(publicPreview.activeClue().response());
        Thread.sleep(300);
        assertTrue(spy.queue.isEmpty(), "pattern and unauthenticated host subscribes must stay silent");
        assertTrue(spy.session.isConnected());

        send(host.session, roomId, new GameAction("OPEN_BUZZERS", hostId, null, Map.of()), hostToken);
        await(alex, s -> s.phase() == GamePhase.CLUE_OPEN);
        alex.disconnect();

        GameSnapshot caughtUp = getRoom(roomId, alexId, null);
        assertEquals(GamePhase.CLUE_OPEN, caughtUp.phase());
        assertNotNull(caughtUp.activeClue().prompt());
        assertNull(caughtUp.activeClue().response());
        Inbox alexAgain = connect("/topic/room." + roomId, null, null);
        Thread.sleep(150);
        send(alexAgain.session, roomId, new GameAction("SYNC", alexId, null, Map.of()), null);
        await(alexAgain, s -> s.phase() == GamePhase.CLUE_OPEN && s.revision() >= caughtUp.revision());

        send(alexAgain.session, roomId, new GameAction("BUZZ", alexId, null, Map.of()), null);
        GameSnapshot missed = await(host, s -> s.phase() == GamePhase.BUZZ_LOCKED && alexId.equals(s.activeClue().buzzedPlayerId()));
        send(host.session, roomId, new GameAction("JUDGE", hostId, null, Map.of("correct", false)), hostToken);
        await(sam, s -> s.phase() == GamePhase.CLUE_OPEN && s.revision() > missed.revision());
        expected.merge("Blue Owls", -first.value(), Integer::sum);
        send(sam.session, roomId, new GameAction("BUZZ", samId, null, Map.of()), null);
        await(host, s -> s.phase() == GamePhase.BUZZ_LOCKED && samId.equals(s.activeClue().buzzedPlayerId()));
        ResponseEntity<String> stolenJudge = postRaw("/api/rooms/" + roomId + "/actions", Map.of(
                "playerId", hostId,
                "type", "JUDGE",
                "payload", Map.of("correct", true)
        ), null);
        assertEquals(409, stolenJudge.getStatusCode().value(), stolenJudge.getBody());
        assertEquals(GamePhase.BUZZ_LOCKED, getRoom(roomId, null, null).phase());
        send(host.session, roomId, new GameAction("JUDGE", hostId, null, Map.of("correct", true)), hostToken);
        expected.merge("Red Foxes", first.value(), Integer::sum);
        await(display, s -> s.phase() == GamePhase.ANSWER_REVEALED && s.activeClue().responseVisible());
        send(host.session, roomId, new GameAction("RETURN_BOARD", hostId, null, Map.of()), hostToken);
        GameSnapshot back = await(host, s -> s.phase() == GamePhase.BOARD);

        // Remaining clues: reveal, raced Daily Double (typed wager), reveal.
        // Second START must not wipe the clue.
        for (int i = 1; i < order.size(); i++) {
            RealPlayHttpSupport.ClueRef clue = order.get(i);
            send(host.session, roomId, new GameAction("SELECT_CLUE", hostId, null, Map.of("clueId", clue.id())), hostToken);
            GameSnapshot preview = await(host, s -> s.phase() == GamePhase.HOST_PREVIEW
                    && clue.id().equals(s.activeClue().clueId()));
            if (clue.dailyDouble()) {
                GameSnapshot pub = await(display, s -> s.phase() == GamePhase.HOST_PREVIEW
                        && s.revision() == preview.revision());
                assertTrue(pub.activeClue().dailyDouble());
                assertNull(pub.activeClue().prompt());
            }
            ResponseEntity<String> restart = postRaw("/api/rooms/" + roomId + "/actions", Map.of(
                    "playerId", hostId,
                    "type", "START",
                    "payload", Map.of()
            ), hostToken);
            assertEquals(409, restart.getStatusCode().value(), restart.getBody());
            assertEquals(GamePhase.HOST_PREVIEW, getRoom(roomId, hostId, hostToken).phase());

            send(host.session, roomId, new GameAction("OPEN_BUZZERS", hostId, null, Map.of()), hostToken);
            await(alexAgain, s -> s.phase() == GamePhase.CLUE_OPEN && clue.id().equals(s.activeClue().clueId()));
            if (i == 2) {
                send(alexAgain.session, roomId, new GameAction("BUZZ", alexId, null, Map.of()), null);
                send(sam.session, roomId, new GameAction("BUZZ", samId, null, Map.of()), null);
                if (clue.dailyDouble()) {
                    GameSnapshot wagering = await(host, s -> s.phase() == GamePhase.DD_WAGER
                            && s.revision() > preview.revision());
                    GameSnapshot pubWager = await(display, s -> s.phase() == GamePhase.DD_WAGER
                            && s.revision() == wagering.revision());
                    assertEquals(wagering.activeClue().buzzedPlayerId(), pubWager.activeClue().buzzedPlayerId());
                    String winner = wagering.activeClue().buzzedPlayerId();
                    assertTrue(alexId.equals(winner) || samId.equals(winner));
                    int wager = WagerRules.MIN_WAGER;
                    StompSession winnerSession = alexId.equals(winner) ? alexAgain.session : sam.session;
                    send(winnerSession, roomId, new GameAction(
                            "SET_WAGER", winner, null, Map.of("amount", wager)), null);
                    GameSnapshot locked = await(host, s -> s.phase() == GamePhase.BUZZ_LOCKED
                            && s.activeClue() != null
                            && Integer.valueOf(wager).equals(s.activeClue().wager()));
                    assertEquals(winner, locked.activeClue().buzzedPlayerId());
                    send(host.session, roomId, new GameAction("JUDGE", hostId, null, Map.of("correct", true)), hostToken);
                    expected.merge(alexId.equals(winner) ? "Blue Owls" : "Red Foxes", wager, Integer::sum);
                } else {
                    GameSnapshot locked = await(host, s -> s.phase() == GamePhase.BUZZ_LOCKED
                            && s.revision() > preview.revision());
                    GameSnapshot pubLocked = await(display, s -> s.phase() == GamePhase.BUZZ_LOCKED
                            && s.revision() == locked.revision());
                    assertEquals(locked.activeClue().buzzedPlayerId(), pubLocked.activeClue().buzzedPlayerId());
                    String winner = locked.activeClue().buzzedPlayerId();
                    assertTrue(alexId.equals(winner) || samId.equals(winner));
                    send(host.session, roomId, new GameAction("JUDGE", hostId, null, Map.of("correct", true)), hostToken);
                    expected.merge(alexId.equals(winner) ? "Blue Owls" : "Red Foxes", clue.value(), Integer::sum);
                }
            } else {
                send(host.session, roomId, new GameAction("REVEAL", hostId, null, Map.of()), hostToken);
            }
            await(display, s -> s.phase() == GamePhase.ANSWER_REVEALED && s.activeClue().responseVisible()
                    && clue.id().equals(s.activeClue().clueId()));
            send(host.session, roomId, new GameAction("RETURN_BOARD", hostId, null, Map.of()), hostToken);
            back = await(host, s -> s.phase() == GamePhase.BOARD || s.phase() == GamePhase.FINISHED);
        }

        GameSnapshot finished = back;
        assertEquals(GamePhase.FINISHED, finished.phase());
        assertTrue(finished.cells().stream().allMatch(BoardCellState::answered));
        Map<String, Integer> actual = new HashMap<>();
        finished.teams().forEach(t -> actual.put(t.name(), t.score()));
        assertEquals(expected, actual);
        GameSnapshot displayFinished = await(display, s -> s.phase() == GamePhase.FINISHED
                && s.revision() == finished.revision());
        assertEquals(finished.revision(), displayFinished.revision());
        assertNull(displayFinished.board().categories().getFirst().clues().getFirst().response());

        host.disconnect();
        display.disconnect();
        alexAgain.disconnect();
        sam.disconnect();
        spy.disconnect();
    }

    private JsonNode post(String path, Map<String, ?> body, String hostToken) throws Exception {
        ResponseEntity<String> response = postRaw(path, body, hostToken);
        assertTrue(response.getStatusCode().is2xxSuccessful(), path + " -> " + response.getStatusCode() + " " + response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    private ResponseEntity<String> postRaw(String path, Map<String, ?> body, String hostToken) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (hostToken != null) {
            headers.set("X-Host-Token", hostToken);
        }
        return rest.exchange(
                "http://127.0.0.1:" + port + path,
                HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                String.class
        );
    }

    private GameSnapshot getRoom(String roomId, String playerId, String hostToken) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        if (hostToken != null) {
            headers.set("X-Host-Token", hostToken);
        }
        String q = playerId == null ? "" : "?playerId=" + playerId;
        ResponseEntity<String> response = rest.exchange(
                "http://127.0.0.1:" + port + "/api/rooms/" + roomId + q,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertTrue(response.getStatusCode().is2xxSuccessful(), response.getBody());
        return objectMapper.readValue(response.getBody(), GameSnapshot.class);
    }

    private Inbox connect(String destination, String playerId, String hostToken) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter json = new MappingJackson2MessageConverter();
        json.setObjectMapper(objectMapper);
        client.setMessageConverter(json);
        BlockingQueue<GameSnapshot> queue = new LinkedBlockingQueue<>();
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        StompSession session = client.connectAsync(
                "ws://127.0.0.1:" + port + "/stomp",
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
        Inbox inbox = new Inbox(client, session, queue, errors);
        inbox.subscribe(destination, playerId, hostToken);
        return inbox;
    }

    private void send(StompSession session, String roomId, GameAction action, String hostToken) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/room/" + roomId + "/action");
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (hostToken != null) {
            headers.add("hostToken", hostToken);
        }
        String playerToken = playerTokens.get(action.playerId());
        if (playerToken != null) {
            headers.add("playerToken", playerToken);
        }
        session.send(headers, action);
    }

    private static GameSnapshot await(Inbox inbox, Predicate<GameSnapshot> match) throws Exception {
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
        assertNotNull(snapshot, "timed out waiting for native STOMP snapshot"
                + (inbox.errors.peek() == null ? "" : "; " + inbox.errors.peek()));
        return snapshot;
    }

    private final class Inbox {
        private final WebSocketStompClient client;
        private final StompSession session;
        private final BlockingQueue<GameSnapshot> queue;
        private final BlockingQueue<Throwable> errors;

        private Inbox(
                WebSocketStompClient client,
                StompSession session,
                BlockingQueue<GameSnapshot> queue,
                BlockingQueue<Throwable> errors
        ) {
            this.client = client;
            this.session = session;
            this.queue = queue;
            this.errors = errors;
        }

        void subscribe(String destination, String playerId, String hostToken) {
            StompHeaders headers = new StompHeaders();
            headers.setDestination(destination);
            if (playerId != null) {
                headers.add("playerId", playerId);
            }
            if (hostToken != null) {
                headers.add("hostToken", hostToken);
            }
            session.subscribe(headers, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return GameSnapshot.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    queue.offer((GameSnapshot) payload);
                }
            });
        }

        void disconnect() {
            if (session.isConnected()) {
                session.disconnect();
            }
            client.stop();
        }
    }
}
