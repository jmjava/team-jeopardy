package com.jmjava.teamjeopardy.skgraph;

import com.jmjava.teamjeopardy.quiz.Board;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkgraphQuestionGeneratorTest {

    @Test
    void generatesBoardFromSampleReactor() {
        Path sample = Path.of("..", "samples", "sample-reactor").toAbsolutePath().normalize();
        SkgraphIngestService ingestService = new SkgraphIngestService();
        // inject sample path via reflection-free direct ingest
        var result = ingestService.ingestPath(sample, "sample-reactor", "release/7.1", "test");
        Board board = new SkgraphQuestionGenerator().generate(result, "Test Board");

        assertFalse(board.categories().isEmpty());
        assertTrue(board.categories().size() <= 6);
        assertTrue(board.categories().stream().allMatch(c -> !c.clues().isEmpty()));
        assertTrue(board.categories().stream()
                .flatMap(c -> c.clues().stream())
                .anyMatch(clue -> clue.value() == 200));
        assertTrue(board.graphDigest().edges() >= 0);
    }
}
