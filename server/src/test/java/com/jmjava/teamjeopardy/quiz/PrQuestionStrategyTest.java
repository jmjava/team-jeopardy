package com.jmjava.teamjeopardy.quiz;

import com.jmjava.teamjeopardy.github.PullRequestFact;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.ProjectKind;
import com.jmjava.teamjeopardy.quiz.enrich.PassthroughQuestionEnricher;
import com.jmjava.teamjeopardy.quiz.strategy.PrQuestionStrategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrQuestionStrategyTest {

    @Test
    void buildsPrCategoriesFromFacts() {
        CodeGraph graph = new CodeGraph("github:acme/demo");
        graph.setProjectKind(ProjectKind.GENERIC);
        graph.setProjectName("acme/demo");
        graph.setPullRequests(List.of(
                new PullRequestFact(
                        12, "Add buzzer lock", "alex", "closed", true,
                        "Introduces first-buzz locking for multiplayer.",
                        List.of("feature", "game"),
                        List.of("server/src/main/java/game/GameRoomService.java", "client/src/App.vue"),
                        120, 40, 2,
                        "https://github.com/acme/demo/pull/12",
                        Instant.parse("2026-07-01T12:00:00Z"),
                        "main", "feat/buzzer"
                ),
                new PullRequestFact(
                        15, "Fix Vue hierarchy enricher", "sam", "open", false,
                        "Regression around USES edges.",
                        List.of("bug"),
                        List.of("server/src/main/java/ingest/enrich/VueComponentHierarchyEnricher.java"),
                        35, 10, 1,
                        "https://github.com/acme/demo/pull/15",
                        Instant.parse("2026-07-20T12:00:00Z"),
                        "main", "fix/vue-uses"
                )
        ));

        QuestionGenerator generator = new QuestionGenerator(
                List.of(new PrQuestionStrategy()),
                new PassthroughQuestionEnricher(),
                "coder,qa",
                6
        );
        Board board = generator.generate(graph, "PR Board");
        assertFalse(board.categories().isEmpty());
        assertTrue(board.categories().stream().anyMatch(c -> c.title().startsWith("PR:")));
        assertTrue(board.categories().stream()
                .flatMap(c -> c.clues().stream())
                .anyMatch(c -> c.response().contains("#12") || c.prompt().contains("buzzer")));
    }
}
