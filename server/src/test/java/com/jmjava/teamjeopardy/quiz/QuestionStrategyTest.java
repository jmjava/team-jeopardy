package com.jmjava.teamjeopardy.quiz;

import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeGraphIngester;
import com.jmjava.teamjeopardy.graph.ProjectDetector;
import com.jmjava.teamjeopardy.graph.ProjectKind;
import com.jmjava.teamjeopardy.ingest.enrich.JavaClassHierarchyEnricher;
import com.jmjava.teamjeopardy.ingest.enrich.VueComponentHierarchyEnricher;
import com.jmjava.teamjeopardy.ingest.gradle.GradleProjectIngester;
import com.jmjava.teamjeopardy.ingest.maven.MavenReactorIngester;
import com.jmjava.teamjeopardy.ingest.osgi.OsgiManifestParser;
import com.jmjava.teamjeopardy.pattern.JavaDesignPatternStrategy;
import com.jmjava.teamjeopardy.pattern.JavaScriptDesignPatternStrategy;
import com.jmjava.teamjeopardy.pattern.PatternScanner;
import com.jmjava.teamjeopardy.pattern.PythonDesignPatternStrategy;
import com.jmjava.teamjeopardy.pattern.VueDesignPatternStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionStrategyTest {

    private CodeGraphIngester ingester;
    private QuestionGenerator questions;

    @BeforeEach
    void setUp() {
        PatternScanner scanner = new PatternScanner(List.of(
                new JavaDesignPatternStrategy(),
                new VueDesignPatternStrategy(),
                new PythonDesignPatternStrategy(),
                new JavaScriptDesignPatternStrategy()
        ));
        ingester = new CodeGraphIngester(
                new ProjectDetector(),
                new MavenReactorIngester(),
                new GradleProjectIngester(),
                new OsgiManifestParser(),
                new JavaClassHierarchyEnricher(),
                new VueComponentHierarchyEnricher(),
                scanner
        );
        questions = new QuestionGenerator();
    }

    @Test
    void gradleBoardMixesCoderAndQaCategories() throws Exception {
        Path root = Path.of("..", "samples", "sample-gradle").toAbsolutePath().normalize();
        CodeGraph graph = ingester.ingest(root, ProjectKind.GRADLE);
        Board board = questions.generate(graph, "Gradle coder/QA");
        assertFalse(board.categories().isEmpty());
        assertTrue(board.categories().stream().anyMatch(c -> c.title().startsWith("DEV:")
                || c.title().contains("DESIGN PATTERNS")
                || c.title().contains("API")));
        assertTrue(board.categories().stream().anyMatch(c -> c.title().startsWith("QA:")),
                "expected a QA persona category, got " + board.categories().stream()
                        .map(Category::title).toList());
    }

    @Test
    void vueBoardIncludesQaBlastOrContracts() throws Exception {
        Path root = Path.of("..", "samples", "sample-vue").toAbsolutePath().normalize();
        CodeGraph graph = ingester.ingest(root, ProjectKind.VUE);
        Board board = questions.generate(graph, "Vue coder/QA");
        assertTrue(board.categories().stream().anyMatch(c ->
                c.title().contains("QA:")
                        || c.title().contains("DEV:")
                        || c.title().contains("COMPONENT")));
    }

    @Test
    void pythonBoardSurfacesPatternRiskOrTestMatrix() throws Exception {
        Path root = Path.of("..", "samples", "sample-python").toAbsolutePath().normalize();
        CodeGraph graph = ingester.ingest(root, ProjectKind.PYTHON);
        Board board = questions.generate(graph, "Python coder/QA");
        assertFalse(board.categories().isEmpty());
        assertTrue(board.categories().stream()
                .flatMap(c -> c.clues().stream())
                .anyMatch(clue -> clue.prompt().toLowerCase().contains("strategy")
                        || clue.response().toLowerCase().contains("strategy")
                        || clue.prompt().toLowerCase().contains("repository")
                        || clue.prompt().toLowerCase().contains("risk")
                        || clue.prompt().toLowerCase().contains("matrix")
                        || clue.prompt().toLowerCase().contains("pattern")));
    }
}
