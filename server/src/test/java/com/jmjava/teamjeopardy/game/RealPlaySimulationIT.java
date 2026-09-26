package com.jmjava.teamjeopardy.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP simulations of real Friday play against generated boards: Maven sample,
 * JIRA release fixture, and a question-bank load. Runs as part of {@code mvn test}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "team-jeopardy.persistence.path=${java.io.tmpdir}/team-jeopardy-real-play-it.db"
)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class RealPlaySimulationIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    private RealPlayHttpSupport play;

    @BeforeEach
    void setUp() {
        play = new RealPlayHttpSupport(rest, objectMapper, port);
    }

    @Test
    void mavenSampleBoardPlaysToFinished() throws Exception {
        RealPlayHttpSupport.Room room = play.openMatch("Maven Friday");
        JsonNode ingest = play.post("/api/rooms/ingest", Map.of(
                "roomId", room.roomId(),
                "playerId", room.hostId(),
                "useSample", true,
                "sampleType", "maven",
                "boardTitle", "Maven Friday",
                "questionHints", "Emphasize architecture and QA risk",
                "questionFocuses", List.of("architecture", "qa", "patterns")
        ));
        assertTrue(ingest.path("snapshot").path("board").path("categories").size() > 0);
        GameSnapshot finished = play.playInstalledBoardToFinished(room, "maven");
        assertTrue(finished.questionHints() == null || finished.questionHints().contains("architecture")
                || !finished.questionHints().isBlank());
    }

    @Test
    void jiraFixtureBoardPlaysToFinished() throws Exception {
        RealPlayHttpSupport.Room room = play.openMatch("JIRA 2.4.0");
        JsonNode ingest = play.post("/api/rooms/ingest-jira", Map.of(
                "roomId", room.roomId(),
                "playerId", room.hostId(),
                "projects", List.of("PROJ"),
                "release", "2.4.0",
                "useFixture", true,
                "fixture", "one-project",
                "boardTitle", "Storefront 2.4.0"
        ));
        assertEquals("jira-fixture", ingest.path("ingestSummary").path("source").asText());
        int categories = ingest.path("snapshot").path("board").path("categories").size();
        assertTrue(categories >= 5 && categories <= 6, "JIRA board categories=" + categories);
        play.playInstalledBoardToFinished(room, "jira-fixture");
    }

    @Test
    void questionBankLoadBoardPlaysToFinished() throws Exception {
        JsonNode saved = play.post("/api/question-bank", Map.of(
                "title", "Compact office drill",
                "sourceKind", "manual",
                "sourceKey", "manual:real-play-compact-" + System.nanoTime(),
                "questionHints", "Keep the clues short for a live drill",
                "categories", List.of(
                        Map.of(
                                "title", "DEV: Patterns",
                                "clues", List.of(
                                        Map.of("value", 200, "prompt", "Reusable Vue logic lives here",
                                                "response", "What is a composable?", "dailyDouble", false),
                                        Map.of("value", 400, "prompt", "This store often leaks state across tests",
                                                "response", "What is Pinia?", "dailyDouble", false),
                                        Map.of("value", 600, "prompt", "Daily Double: name the factory cousin",
                                                "response", "What is Factory Method?", "dailyDouble", true)
                                )
                        ),
                        Map.of(
                                "title", "QA: Blast Radius",
                                "clues", List.of(
                                        Map.of("value", 200, "prompt", "Score chips go stale after this mutation",
                                                "response", "What is the shared display?", "dailyDouble", false),
                                        Map.of("value", 400, "prompt", "Unadmitted players must not do this",
                                                "response", "What is buzz?", "dailyDouble", false),
                                        Map.of("value", 600, "prompt", "Last clue of the drill",
                                                "response", "What is FINISHED?", "dailyDouble", false)
                                )
                        )
                )
        ));
        String savedId = saved.path("id").asText();
        assertTrue(savedId != null && !savedId.isBlank());

        RealPlayHttpSupport.Room room = play.openMatch("Bank load Friday");
        JsonNode loaded = play.post("/api/rooms/load-board", Map.of(
                "roomId", room.roomId(),
                "playerId", room.hostId(),
                "savedBoardId", savedId
        ));
        assertEquals("question-bank", loaded.path("ingestSummary").path("source").asText());
        assertEquals(6, loaded.path("ingestSummary").path("clues").asInt());
        play.playInstalledBoardToFinished(room, "question-bank");
    }
}
