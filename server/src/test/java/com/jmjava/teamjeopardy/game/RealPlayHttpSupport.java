package com.jmjava.teamjeopardy.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST driver that plays an installed board the way a real Friday match runs:
 * lowest remaining dollar values left-to-right, mixed miss/hit/reveal outcomes,
 * and a running expected scoreboard.
 */
final class RealPlayHttpSupport {

    enum Style {
        INCORRECT_THEN_CORRECT,
        FIRST_CORRECT,
        REVEAL_NO_BUZZ,
        INCORRECT_THEN_REVEAL
    }

    record Room(String roomId, String code, String hostId, String alexId, String samId) {
    }

    record ClueRef(String id, int value, String categoryTitle, boolean dailyDouble, int categoryIndex) {
    }

    private final TestRestTemplate rest;
    private final ObjectMapper mapper;
    private final int port;

    RealPlayHttpSupport(TestRestTemplate rest, ObjectMapper mapper, int port) {
        this.rest = rest;
        this.mapper = mapper;
        this.port = port;
    }

    Room openMatch(String title) throws Exception {
        JsonNode created = post("/api/rooms", Map.of("hostName", "Pat Host", "title", title));
        String roomId = created.path("snapshot").path("roomId").asText();
        String code = created.path("snapshot").path("code").asText();
        String hostId = created.path("hostPlayerId").asText();
        JsonNode alex = post("/api/rooms/join", Map.of("code", code, "displayName", "Alex", "teamName", "Blue Owls"));
        JsonNode sam = post("/api/rooms/join", Map.of("code", code, "displayName", "Sam", "teamName", "Red Foxes"));
        return new Room(roomId, code, hostId, alex.path("playerId").asText(), sam.path("playerId").asText());
    }

    GameSnapshot playInstalledBoardToFinished(Room room, String label) throws Exception {
        post("/api/rooms/" + room.roomId() + "/actions", action(room.hostId(), "ADMIT_ALL"));
        GameSnapshot board = actionSnap(room.roomId(), room.hostId(), "START", Map.of());
        assertEquals(GamePhase.BOARD, board.phase(), label + " should start on BOARD");
        assertNotNull(board.board());
        assertFalse(board.board().categories().isEmpty(), label + " board empty");

        Map<String, Integer> expected = new HashMap<>();
        expected.put("Blue Owls", 0);
        expected.put("Red Foxes", 0);

        List<ClueRef> order = jeopardyOrder(board);
        assertFalse(order.isEmpty(), label + " has no clues");
        System.out.printf("    %s: %d clues across %d categories%n",
                label, order.size(), board.board().categories().size());

        GameSnapshot snap = board;
        for (int i = 0; i < order.size(); i++) {
            ClueRef clue = order.get(i);
            Style style = Style.values()[i % Style.values().length];
            snap = playClue(room, clue, style, expected, i == 0);
            System.out.printf("    %s clue %d/%d %s $%d %s blue=%d red=%d phase=%s%n",
                    label, i + 1, order.size(), clue.categoryTitle(), clue.value(), style,
                    expected.get("Blue Owls"), expected.get("Red Foxes"), snap.phase());
        }

        assertEquals(GamePhase.FINISHED, snap.phase(), label + " expected FINISHED");
        assertTrue(snap.cells().stream().allMatch(BoardCellState::answered), label + " leftover cells");
        assertScores(snap, expected);
        return snap;
    }

    private GameSnapshot playClue(
            Room room,
            ClueRef clue,
            Style style,
            Map<String, Integer> expected,
            boolean assertRedaction
    ) throws Exception {
        GameSnapshot hostPreview = actionSnap(
                room.roomId(), room.hostId(), "SELECT_CLUE", Map.of("clueId", clue.id()));
        assertEquals(GamePhase.HOST_PREVIEW, hostPreview.phase());
        assertEquals(clue.value(), hostPreview.activeClue().value());
        if (clue.dailyDouble()) {
            assertTrue(hostPreview.activeClue().dailyDouble(), "host should see Daily Double badge");
        }
        assertNotNull(hostPreview.activeClue().prompt());
        assertNotNull(hostPreview.activeClue().response());

        GameSnapshot pub = getRoom(room.roomId(), null);
        if (assertRedaction) {
            assertNull(pub.activeClue().prompt(), "public hides prompt in HOST_PREVIEW");
            assertNull(pub.activeClue().response(), "public hides answer in HOST_PREVIEW");
        }
        if (clue.dailyDouble()) {
            assertTrue(pub.activeClue().dailyDouble(), "display still shows Daily Double badge");
        }

        GameSnapshot open = actionSnap(room.roomId(), room.hostId(), "OPEN_BUZZERS", Map.of());
        assertEquals(GamePhase.CLUE_OPEN, open.phase());
        GameSnapshot publicOpen = getRoom(room.roomId(), null);
        assertNotNull(publicOpen.activeClue().prompt());
        assertNull(publicOpen.activeClue().response());

        GameSnapshot judged = switch (style) {
            case FIRST_CORRECT -> {
                GameSnapshot locked = actionSnap(room.roomId(), room.alexId(), "BUZZ", Map.of());
                assertEquals(GamePhase.BUZZ_LOCKED, locked.phase());
                credit(expected, "Blue Owls", clue.value());
                yield actionSnap(room.roomId(), room.hostId(), "JUDGE", Map.of("correct", true));
            }
            case INCORRECT_THEN_CORRECT -> {
                actionSnap(room.roomId(), room.alexId(), "BUZZ", Map.of());
                credit(expected, "Blue Owls", -clue.value());
                GameSnapshot reopened = actionSnap(room.roomId(), room.hostId(), "JUDGE", Map.of("correct", false));
                assertEquals(GamePhase.CLUE_OPEN, reopened.phase());
                actionSnap(room.roomId(), room.samId(), "BUZZ", Map.of());
                credit(expected, "Red Foxes", clue.value());
                yield actionSnap(room.roomId(), room.hostId(), "JUDGE", Map.of("correct", true));
            }
            case REVEAL_NO_BUZZ -> actionSnap(room.roomId(), room.hostId(), "REVEAL", Map.of());
            case INCORRECT_THEN_REVEAL -> {
                actionSnap(room.roomId(), room.samId(), "BUZZ", Map.of());
                credit(expected, "Red Foxes", -clue.value());
                GameSnapshot reopened = actionSnap(room.roomId(), room.hostId(), "JUDGE", Map.of("correct", false));
                assertEquals(GamePhase.CLUE_OPEN, reopened.phase());
                yield actionSnap(room.roomId(), room.hostId(), "REVEAL", Map.of());
            }
        };

        assertEquals(GamePhase.ANSWER_REVEALED, judged.phase());
        assertTrue(judged.activeClue().responseVisible());
        GameSnapshot after = actionSnap(room.roomId(), room.hostId(), "RETURN_BOARD", Map.of());
        assertTrue(after.phase() == GamePhase.BOARD || after.phase() == GamePhase.FINISHED);
        assertScores(after, expected);
        return after;
    }

    static List<ClueRef> jeopardyOrder(GameSnapshot snap) {
        List<ClueRef> refs = new ArrayList<>();
        List<com.jmjava.teamjeopardy.quiz.Category> cats = snap.board().categories();
        for (int c = 0; c < cats.size(); c++) {
            var cat = cats.get(c);
            for (var clue : cat.clues()) {
                boolean answered = snap.cells().stream()
                        .anyMatch(cell -> cell.clueId().equals(clue.id()) && cell.answered());
                if (!answered) {
                    refs.add(new ClueRef(clue.id(), clue.value(), cat.title(), clue.dailyDouble(), c));
                }
            }
        }
        refs.sort(Comparator.comparingInt(ClueRef::value).thenComparingInt(ClueRef::categoryIndex));
        return refs;
    }

    JsonNode post(String path, Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + path,
                new HttpEntity<>(mapper.writeValueAsString(body), headers),
                String.class
        );
        assertTrue(
                response.getStatusCode().is2xxSuccessful(),
                path + " -> " + response.getStatusCode() + " " + response.getBody()
        );
        return mapper.readTree(response.getBody());
    }

    GameSnapshot getRoom(String roomId, String playerId) {
        String q = playerId == null ? "" : "?playerId=" + playerId;
        return rest.getForObject("http://localhost:" + port + "/api/rooms/" + roomId + q, GameSnapshot.class);
    }

    GameSnapshot actionSnap(String roomId, String playerId, String type, Map<String, ?> payload) throws Exception {
        JsonNode node = post("/api/rooms/" + roomId + "/actions", action(playerId, type, payload));
        return mapper.treeToValue(node, GameSnapshot.class);
    }

    static Map<String, Object> action(String playerId, String type) {
        return action(playerId, type, Map.of());
    }

    static Map<String, Object> action(String playerId, String type, Map<String, ?> payload) {
        Map<String, Object> body = new HashMap<>();
        body.put("playerId", playerId);
        body.put("type", type);
        body.put("payload", payload);
        return body;
    }

    private static void credit(Map<String, Integer> expected, String team, int delta) {
        expected.put(team, expected.getOrDefault(team, 0) + delta);
    }

    private static void assertScores(GameSnapshot snap, Map<String, Integer> expected) {
        Map<String, Integer> actual = new HashMap<>();
        for (Team team : snap.teams()) {
            actual.put(team.name(), team.score());
        }
        assertEquals(expected, actual, "scoreboard drifted from the play script");
    }
}
