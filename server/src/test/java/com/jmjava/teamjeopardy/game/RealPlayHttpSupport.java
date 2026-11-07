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

    record Room(String roomId, String code, String hostId, String hostToken, String alexId, String samId) {
    }

    record ClueRef(String id, int value, String categoryTitle, boolean dailyDouble, int categoryIndex) {
    }

    private final TestRestTemplate rest;
    private final ObjectMapper mapper;
    private final int port;
    private final Map<String, String> playerTokens = new HashMap<>();

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
        String hostToken = created.path("hostToken").asText();
        JsonNode alex = post("/api/rooms/join", Map.of("code", code, "displayName", "Alex", "teamName", "Blue Owls"));
        JsonNode sam = post("/api/rooms/join", Map.of("code", code, "displayName", "Sam", "teamName", "Red Foxes"));
        String alexId = alex.path("playerId").asText();
        String samId = sam.path("playerId").asText();
        playerTokens.put(alexId, alex.path("playerToken").asText());
        playerTokens.put(samId, sam.path("playerToken").asText());
        return new Room(roomId, code, hostId, hostToken, alexId, samId);
    }

    GameSnapshot playInstalledBoardToFinished(Room room, String label) throws Exception {
        post("/api/rooms/" + room.roomId() + "/actions", action(room.hostId(), "ADMIT_ALL"), room.hostToken());
        GameSnapshot board = actionSnap(room.roomId(), room.hostId(), room.hostToken(), "START", Map.of());
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

        if (snap.phase() == GamePhase.FINAL_CATEGORY) {
            snap = playFinal(room, expected);
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
                room.roomId(), room.hostId(), room.hostToken(), "SELECT_CLUE", Map.of("clueId", clue.id()));
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

        GameSnapshot open = actionSnap(room.roomId(), room.hostId(), room.hostToken(), "OPEN_BUZZERS", Map.of());
        assertEquals(GamePhase.CLUE_OPEN, open.phase());
        GameSnapshot publicOpen = getRoom(room.roomId(), null);
        if (clue.dailyDouble()) {
            assertNull(publicOpen.activeClue().prompt(), "Daily Double hides the clue until the wager");
        } else {
            assertNotNull(publicOpen.activeClue().prompt());
        }
        assertNull(publicOpen.activeClue().response());

        GameSnapshot judged = clue.dailyDouble()
                ? playDailyDouble(room, clue, style, expected)
                : switch (style) {
            case FIRST_CORRECT -> {
                GameSnapshot locked = actionSnap(room.roomId(), room.alexId(), "BUZZ", Map.of());
                assertEquals(GamePhase.BUZZ_LOCKED, locked.phase());
                credit(expected, "Blue Owls", clue.value());
                yield actionSnap(room.roomId(), room.hostId(), room.hostToken(), "JUDGE", Map.of("correct", true));
            }
            case INCORRECT_THEN_CORRECT -> {
                actionSnap(room.roomId(), room.alexId(), null, "BUZZ", Map.of());
                credit(expected, "Blue Owls", -clue.value());
                GameSnapshot reopened = actionSnap(room.roomId(), room.hostId(), room.hostToken(), "JUDGE", Map.of("correct", false));
                assertEquals(GamePhase.CLUE_OPEN, reopened.phase());
                actionSnap(room.roomId(), room.samId(), null, "BUZZ", Map.of());
                credit(expected, "Red Foxes", clue.value());
                yield actionSnap(room.roomId(), room.hostId(), room.hostToken(), "JUDGE", Map.of("correct", true));
            }
            case REVEAL_NO_BUZZ -> actionSnap(room.roomId(), room.hostId(), room.hostToken(), "REVEAL", Map.of());
            case INCORRECT_THEN_REVEAL -> {
                actionSnap(room.roomId(), room.samId(), null, "BUZZ", Map.of());
                credit(expected, "Red Foxes", -clue.value());
                GameSnapshot reopened = actionSnap(room.roomId(), room.hostId(), room.hostToken(), "JUDGE", Map.of("correct", false));
                assertEquals(GamePhase.CLUE_OPEN, reopened.phase());
                yield actionSnap(room.roomId(), room.hostId(), room.hostToken(), "REVEAL", Map.of());
            }
        };

        assertEquals(GamePhase.ANSWER_REVEALED, judged.phase());
        assertTrue(judged.activeClue().responseVisible());
        GameSnapshot after = actionSnap(room.roomId(), room.hostId(), room.hostToken(), "RETURN_BOARD", Map.of());
        assertTrue(after.phase() == GamePhase.BOARD
                || after.phase() == GamePhase.FINISHED
                || after.phase() == GamePhase.FINAL_CATEGORY);
        assertScores(after, expected);
        return after;
    }

    private GameSnapshot playDailyDouble(
            Room room,
            ClueRef clue,
            Style style,
            Map<String, Integer> expected
    ) throws Exception {
        if (style == Style.REVEAL_NO_BUZZ) {
            return actionSnap(room.roomId(), room.hostId(), room.hostToken(), "REVEAL", Map.of());
        }
        boolean alexPlays = style != Style.INCORRECT_THEN_REVEAL;
        boolean correct = style == Style.FIRST_CORRECT;
        String playerId = alexPlays ? room.alexId() : room.samId();
        String team = alexPlays ? "Blue Owls" : "Red Foxes";
        GameSnapshot claimed = actionSnap(room.roomId(), playerId, "BUZZ", Map.of());
        assertEquals(GamePhase.DD_WAGER, claimed.phase());
        int wager = dailyDoubleWager(expected.getOrDefault(team, 0), clue.value());
        GameSnapshot locked = actionSnap(room.roomId(), playerId, "SET_WAGER", Map.of("amount", wager));
        assertEquals(GamePhase.BUZZ_LOCKED, locked.phase());
        assertNotNull(getRoom(room.roomId(), null).activeClue().prompt());
        credit(expected, team, correct ? wager : -wager);
        GameSnapshot judged = actionSnap(
                room.roomId(), room.hostId(), room.hostToken(), "JUDGE", Map.of("correct", correct));
        assertEquals(GamePhase.ANSWER_REVEALED, judged.phase());
        return judged;
    }

    private GameSnapshot playFinal(Room room, Map<String, Integer> expected) throws Exception {
        GameSnapshot wagering = actionSnap(
                room.roomId(), room.hostId(), room.hostToken(), "START_FINAL_WAGER", Map.of());
        assertEquals(GamePhase.FINAL_WAGER, wagering.phase());
        int blueWager = WagerRules.MIN_WAGER;
        int redWager = WagerRules.MIN_WAGER;
        actionSnap(room.roomId(), room.alexId(), "SET_WAGER", Map.of("amount", blueWager));
        actionSnap(room.roomId(), room.samId(), "SET_WAGER", Map.of("amount", redWager));
        GameSnapshot writing = actionSnap(
                room.roomId(), room.hostId(), room.hostToken(), "START_FINAL_WRITING", Map.of("seconds", 30));
        assertEquals(GamePhase.FINAL_WRITING, writing.phase());
        assertNotNull(getRoom(room.roomId(), null).finalRound().prompt());
        actionSnap(room.roomId(), room.alexId(), "SET_FINAL_ANSWER", Map.of("text", "What is the release?"));
        actionSnap(room.roomId(), room.samId(), "SET_FINAL_ANSWER", Map.of("text", "What is a red herring?"));
        assertTrue(getRoom(room.roomId(), null).finalRound().entries().stream()
                .allMatch(entry -> entry.answer() == null || entry.answer().isBlank()));
        GameSnapshot locked = actionSnap(
                room.roomId(), room.hostId(), room.hostToken(), "END_WRITING", Map.of());
        assertEquals(GamePhase.FINAL_LOCKED, locked.phase());
        GameSnapshot revealed = actionSnap(room.roomId(), room.hostId(), room.hostToken(), "REVEAL", Map.of());
        assertEquals(GamePhase.FINAL_REVEALED, revealed.phase());
        String blueId = revealed.teams().stream().filter(t -> t.name().equals("Blue Owls")).findFirst().orElseThrow().id();
        String redId = revealed.teams().stream().filter(t -> t.name().equals("Red Foxes")).findFirst().orElseThrow().id();
        actionSnap(room.roomId(), room.hostId(), room.hostToken(), "JUDGE_FINAL", Map.of("teamId", blueId, "correct", true));
        GameSnapshot done = actionSnap(
                room.roomId(), room.hostId(), room.hostToken(), "JUDGE_FINAL", Map.of("teamId", redId, "correct", false));
        credit(expected, "Blue Owls", blueWager);
        credit(expected, "Red Foxes", -redWager);
        assertEquals(GamePhase.FINISHED, done.phase());
        assertScores(done, expected);
        return done;
    }

    private static int dailyDoubleWager(int score, int face) {
        int max = WagerRules.maxWager(score);
        int wager = Math.min(Math.max(WagerRules.MIN_WAGER, face), max);
        return Math.max(WagerRules.MIN_WAGER, Math.min(wager, max));
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
        return post(path, body, null);
    }

    JsonNode post(String path, Object body, String hostToken) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (hostToken != null && !hostToken.isBlank()) {
            headers.set("X-Host-Token", hostToken);
        }
        if (body instanceof Map<?, ?> map && map.get("playerId") instanceof String playerId) {
            String playerToken = playerTokens.get(playerId);
            if (playerToken != null && !playerToken.isBlank()) {
                headers.set("X-Player-Token", playerToken);
            }
        }
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
        return actionSnap(roomId, playerId, null, type, payload);
    }

    GameSnapshot actionSnap(String roomId, String playerId, String hostToken, String type, Map<String, ?> payload) throws Exception {
        JsonNode node = post("/api/rooms/" + roomId + "/actions", action(playerId, type, payload), hostToken);
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
