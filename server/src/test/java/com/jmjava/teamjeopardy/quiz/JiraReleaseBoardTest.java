package com.jmjava.teamjeopardy.quiz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmjava.teamjeopardy.api.BoardFactory;
import com.jmjava.teamjeopardy.github.GitHubPullRequestClient;
import com.jmjava.teamjeopardy.github.GitHubRepoFetcher;
import com.jmjava.teamjeopardy.graph.CodeGraphIngester;
import com.jmjava.teamjeopardy.jira.JiraReleaseCategorizer;
import com.jmjava.teamjeopardy.jira.JiraReleaseClient;
import com.jmjava.teamjeopardy.persist.QuestionBankService;
import com.jmjava.teamjeopardy.quiz.enrich.PassthroughQuestionEnricher;
import com.jmjava.teamjeopardy.quiz.strategy.JiraReleaseQuestionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class JiraReleaseBoardTest {

    private BoardFactory factory;
    private QuestionBankService questionBank;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        JiraReleaseClient client = new JiraReleaseClient(
                mapper, "", "", "", 10, 50,
                "../samples/jira-release-multi-project.json",
                "../samples/jira-release-one-project.json",
                "", ""
        );
        factory = new BoardFactory(
                mock(CodeGraphIngester.class),
                new QuestionGenerator(List.of(), new PassthroughQuestionEnricher(), "coder,qa", 6),
                mock(GitHubPullRequestClient.class),
                mock(GitHubRepoFetcher.class),
                client,
                new JiraReleaseCategorizer(),
                new JiraReleaseQuestionStrategy()
        );

        Path db = tempDir.resolve("questions.db");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        questionBank = new QuestionBankService(
                new com.jmjava.teamjeopardy.persist.QuestionBankRepository(jdbc, mapper),
                true
        );
    }

    @Test
    void oneProjectFixtureProducesFiveOrSixSpecRelCategoriesOffline() throws Exception {
        BoardFactory.BuiltBoard built = factory.fromJiraRelease(
                List.of("PROJ"),
                "2.4.0",
                null,
                true,
                "one-project",
                "Storefront 2.4.0",
                QuestionHints.empty()
        );
        assertPlayableJiraBoard(built.board());
        assertTrue(built.summary().get("source").toString().contains("jira"));
        assertEquals(Boolean.TRUE, built.summary().get("fixture"));
        assertTrue(built.board().categories().stream().anyMatch(c -> c.title().contains("Checkout")));
        assertTrue(built.board().categories().stream().anyMatch(c -> c.title().contains("Also in this release")));
    }

    @Test
    void multiProjectFixtureProducesFiveOrSixProjectCategoriesOffline() throws Exception {
        BoardFactory.BuiltBoard built = factory.fromJiraRelease(
                List.of("SHOP", "PAY", "INV", "NOTE", "ADMIN"),
                "2.4.0",
                null,
                true,
                "multi",
                "Release 2.4.0",
                QuestionHints.empty()
        );
        assertPlayableJiraBoard(built.board());
        Set<String> titles = Set.copyOf(built.board().categories().stream().map(Category::title).toList());
        assertTrue(titles.stream().anyMatch(t -> t.contains("Shop")));
        assertTrue(titles.stream().anyMatch(t -> t.contains("Payments Service")));
        assertTrue(titles.stream().anyMatch(t -> t.contains("Inventory")));
    }

    @Test
    void openaiOffStillPersistsJiraSourceKind() throws Exception {
        BoardFactory.BuiltBoard built = factory.fromJiraRelease(
                List.of("PROJ"),
                "2.4.0",
                null,
                true,
                "one-project",
                "Offline JIRA",
                QuestionHints.empty()
        );
        var saved = questionBank.saveGenerated(
                built.board(),
                built.summary(),
                "jira",
                QuestionBankService.sourceKeyForJira(List.of("PROJ"), "2.4.0"),
                QuestionHints.empty()
        ).orElseThrow();
        assertEquals("jira", saved.sourceKind());
        assertEquals("jira:PROJ@2.4.0", saved.sourceKey());
        Board loaded = questionBank.loadBoard(saved.id());
        assertEquals(built.board().categories().size(), loaded.categories().size());
        assertEquals(
                built.board().categories().getFirst().clues().getFirst().response(),
                loaded.categories().getFirst().clues().getFirst().response()
        );
    }

    private static void assertPlayableJiraBoard(Board board) {
        int cats = board.categories().size();
        assertTrue(cats >= 5 && cats <= 6, "categories=" + cats + " " + board.categories().stream().map(Category::title).toList());
        for (Category category : board.categories()) {
            assertTrue(category.title().startsWith("SPEC:") || category.title().startsWith("REL:"), category.title());
            assertEquals(5, category.clues().size(), category.title());
            for (int i = 0; i < category.clues().size(); i++) {
                Clue clue = category.clues().get(i);
                assertEquals(200 * (i + 1), clue.value());
                assertFalse(clue.prompt().isBlank());
                assertFalse(clue.response().isBlank());
                assertTrue(clue.response().startsWith("What is "));
            }
        }
    }
}
