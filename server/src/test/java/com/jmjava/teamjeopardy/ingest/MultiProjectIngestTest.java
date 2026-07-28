package com.jmjava.teamjeopardy.ingest;

import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeGraphIngester;
import com.jmjava.teamjeopardy.graph.CodeNode;
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
import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.QuestionGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiProjectIngestTest {

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
    void detectsAndIngestsMavenSample() throws Exception {
        Path root = Path.of("..", "samples", "sample-reactor").toAbsolutePath().normalize();
        assertEquals(ProjectKind.MAVEN, new ProjectDetector().detect(root));
        CodeGraph graph = ingester.ingest(root);
        assertTrue(graph.nodesOfKind(CodeNode.NodeKind.MODULE).size() >= 3);
        Board board = questions.generate(graph, "Maven Board");
        assertFalse(board.categories().isEmpty());
    }

    @Test
    void detectsAndIngestsGradleSample() throws Exception {
        Path root = Path.of("..", "samples", "sample-gradle").toAbsolutePath().normalize();
        assertEquals(ProjectKind.GRADLE, new ProjectDetector().detect(root));
        CodeGraph graph = ingester.ingest(root);
        assertTrue(graph.nodesOfKind(CodeNode.NodeKind.MODULE).size() >= 2);
        assertTrue(graph.nodesOfKind(CodeNode.NodeKind.DEPENDENCY).size() >= 1);
        Board board = questions.generate(graph, "Gradle Board");
        assertTrue(board.categories().stream().anyMatch(c ->
                c.title().contains("MODULE")
                        || c.title().startsWith("DEV:")
                        || c.title().startsWith("QA:")));
    }

    @Test
    void detectsAndIngestsVueSample() throws Exception {
        Path root = Path.of("..", "samples", "sample-vue").toAbsolutePath().normalize();
        assertEquals(ProjectKind.VUE, new ProjectDetector().detect(root));
        CodeGraph graph = ingester.ingest(root);
        assertTrue(graph.nodesOfKind(CodeNode.NodeKind.COMPONENT).size() >= 2);
        assertTrue(graph.nodesOfKind(CodeNode.NodeKind.DEPENDENCY).stream()
                .anyMatch(d -> "vue".equals(d.name())));
        // Vue sample has no OSGi markers — ingest must succeed without Maven/OSGi.
        assertTrue(graph.nodesOfKind(CodeNode.NodeKind.COMPONENT).stream()
                .noneMatch(c -> "osgi".equals(c.language())));
        Board board = questions.generate(graph, "Vue Board");
        assertTrue(board.categories().stream().anyMatch(c ->
                c.title().contains("COMPONENT")
                        || c.title().startsWith("DEV:")
                        || c.title().startsWith("QA:")));
    }

    @Test
    void gradleWorksWithoutMavenOrOsgi() throws Exception {
        Path root = Path.of("..", "samples", "sample-gradle").toAbsolutePath().normalize();
        CodeGraph graph = ingester.ingest(root, ProjectKind.GRADLE);
        assertEquals(ProjectKind.GRADLE, graph.getProjectKind());
        assertTrue(graph.nodesOfKind(CodeNode.NodeKind.MODULE).size() >= 2);
        assertTrue(graph.nodesOfKind(CodeNode.NodeKind.COMPONENT).stream()
                .noneMatch(c -> "osgi".equals(c.language())));
        assertFalse(Files.exists(root.resolve("pom.xml")));
    }
}
