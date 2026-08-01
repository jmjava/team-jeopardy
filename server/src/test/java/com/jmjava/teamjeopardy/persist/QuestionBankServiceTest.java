package com.jmjava.teamjeopardy.persist;

import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import com.jmjava.teamjeopardy.quiz.QuestionHints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionBankServiceTest {

    @TempDir
    Path tempDir;

    private QuestionBankService service;

    @BeforeEach
    void setUp() {
        Path db = tempDir.resolve("questions.db");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        populator.execute(ds);
        QuestionBankRepository repository = new QuestionBankRepository(jdbc, new ObjectMapper());
        service = new QuestionBankService(repository, true);
    }

    @Test
    void savesAndReloadsGuideGeneratedBoard() {
        Board board = sampleBoard();
        SavedBoardRecord saved = service.saveGenerated(
                board,
                Map.of("projectKind", "MAVEN"),
                "sample",
                QuestionBankService.sourceKeyForSample("maven"),
                QuestionHints.of("focus on patterns", List.of("patterns"))
        ).orElseThrow();

        assertFalse(saved.id().isBlank());
        assertEquals(1, service.list(10).size());

        Board loaded = service.loadBoard(saved.id());
        assertEquals(board.title(), loaded.title());
        assertEquals(1, loaded.categories().size());
        assertEquals("What is Strategy?", loaded.categories().getFirst().clues().getFirst().prompt());

        List<SavedClueRecord> clues = service.searchClues("Strategy", 20);
        assertEquals(1, clues.size());
        assertEquals("Strategy", clues.getFirst().response());
    }

    @Test
    void fingerprintStableForSameSourceAndHints() {
        QuestionHints hints = QuestionHints.of("QA blast radius", List.of("qa"));
        String a = QuestionBankService.fingerprint("sample", "sample:vue", hints);
        String b = QuestionBankService.fingerprint("sample", "sample:vue", hints);
        assertEquals(a, b);
        assertTrue(a.length() >= 32);
    }

    @Test
    void deleteRemovesBoardAndClues() {
        SavedBoardRecord saved = service.saveGenerated(
                sampleBoard(),
                Map.of(),
                "sample",
                "sample:maven",
                QuestionHints.empty()
        ).orElseThrow();
        assertTrue(service.delete(saved.id()));
        assertEquals(0, service.list(10).size());
        assertTrue(service.searchClues("Strategy", 10).isEmpty());
    }

    @Test
    void manualCreateAndClueMaintenance() {
        SavedBoardRecord created = service.createManual(
                "Manual Board",
                "manual",
                "manual:test",
                "/tmp/example",
                QuestionHints.of("patterns", List.of("patterns")),
                List.of(new Category(
                        "dev-manual",
                        "DEV: Manual",
                        List.of(new Clue("c1", 200, "Starter?", "Yes", "", "", false))
                )),
                new Board.GraphDigest(1, 1, 1, 1, 1)
        );
        assertEquals(1, service.listCluesForBoard(created.id()).size());

        SavedClueRecord added = service.addClue(
                created.id(),
                null,
                "QA: Risk",
                400,
                "What needs a fake?",
                "Repository",
                "Persistence boundary",
                "repo.py",
                false
        );
        assertEquals(2, service.listCluesForBoard(created.id()).size());

        assertTrue(service.deleteClue(added.id()));
        assertEquals(1, service.listCluesForBoard(created.id()).size());
        assertEquals(1, service.deleteAll());
        assertEquals(0, service.list(10).size());
    }

    @Test
    void bulkUploadCreatesAndSkipsDuplicates() {
        QuestionBankService.ManualBoardSpec spec = new QuestionBankService.ManualBoardSpec(
                "Bulk Board",
                "manual",
                "manual:bulk-one",
                "",
                QuestionHints.empty(),
                List.of(new Category(
                        "dev",
                        "DEV: Bulk",
                        List.of(new Clue("c1", 200, "Prompt?", "Answer", "", "", false))
                )),
                new Board.GraphDigest(0, 0, 0, 0, 0)
        );

        QuestionBankService.BulkResult first = service.bulkCreate(List.of(spec), true);
        assertEquals(1, first.created());
        assertEquals(0, first.skipped());
        assertEquals(0, first.failed());

        QuestionBankService.BulkResult second = service.bulkCreate(List.of(spec), true);
        assertEquals(0, second.created());
        assertEquals(1, second.skipped());

        QuestionBankService.BulkResult mixed = service.bulkCreate(
                List.of(
                        spec,
                        new QuestionBankService.ManualBoardSpec(
                                "Broken",
                                "manual",
                                "manual:broken",
                                "",
                                QuestionHints.empty(),
                                List.of(),
                                null
                        )
                ),
                false
        );
        assertEquals(1, mixed.created());
        assertEquals(1, mixed.failed());
    }

    private static Board sampleBoard() {
        return new Board(
                "Maven Jeopardy",
                "/samples/sample-reactor",
                List.of(new Category(
                        "dev-patterns",
                        "DEV: Patterns",
                        List.of(new Clue(
                                "c1",
                                200,
                                "What is Strategy?",
                                "Strategy",
                                "Behavioral pattern",
                                "src/Strategy.java",
                                false
                        ))
                )),
                new Board.GraphDigest(10, 12, 5, 3, 8)
        );
    }
}
