package com.jmjava.teamjeopardy.ingest;

import com.jmjava.teamjeopardy.graph.CodeEdge;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeGraphIngester;
import com.jmjava.teamjeopardy.graph.ProjectDetector;
import com.jmjava.teamjeopardy.graph.ProjectKind;
import com.jmjava.teamjeopardy.ingest.enrich.JavaClassHierarchyEnricher;
import com.jmjava.teamjeopardy.ingest.enrich.VueComponentHierarchyEnricher;
import com.jmjava.teamjeopardy.ingest.gradle.GradleProjectIngester;
import com.jmjava.teamjeopardy.ingest.maven.MavenReactorIngester;
import com.jmjava.teamjeopardy.ingest.osgi.OsgiManifestParser;
import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.QuestionGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchyEnricherTest {

    private CodeGraphIngester ingester;
    private QuestionGenerator questions;

    @BeforeEach
    void setUp() {
        ingester = new CodeGraphIngester(
                new ProjectDetector(),
                new MavenReactorIngester(),
                new GradleProjectIngester(),
                new OsgiManifestParser(),
                new JavaClassHierarchyEnricher(),
                new VueComponentHierarchyEnricher()
        );
        questions = new QuestionGenerator();
    }

    @Test
    void javaHierarchyCapturesExtendsAndImplements() throws Exception {
        Path root = Path.of("..", "samples", "sample-gradle").toAbsolutePath().normalize();
        CodeGraph graph = ingester.ingest(root, ProjectKind.GRADLE);

        assertTrue(graph.edgesOf(CodeEdge.Relation.EXTENDS).stream().anyMatch(e ->
                e.fromId().contains("AppMain") && e.toId().contains("BaseService")));
        assertTrue(graph.edgesOf(CodeEdge.Relation.IMPLEMENTS).stream().anyMatch(e ->
                e.fromId().contains("AppMain") && e.toId().contains("GreetingService")));

        Board board = questions.generate(graph, "Hierarchy");
        assertTrue(board.categories().stream().anyMatch(c -> "CLASS HIERARCHY".equals(c.title())));
    }

    @Test
    void vueHierarchyCapturesComponentUses() throws Exception {
        Path root = Path.of("..", "samples", "sample-vue").toAbsolutePath().normalize();
        CodeGraph graph = ingester.ingest(root, ProjectKind.VUE);

        assertTrue(graph.edgesOf(CodeEdge.Relation.USES).stream().anyMatch(e ->
                e.fromId().contains("App.vue") && e.toId().contains("ScoreCard")));
        assertTrue(graph.edgesOf(CodeEdge.Relation.USES).stream().anyMatch(e ->
                e.fromId().contains("BoardPanel") && e.toId().contains("ClueButton")));

        Board board = questions.generate(graph, "Vue Hierarchy");
        assertTrue(board.categories().stream().anyMatch(c -> "COMPONENT TREE".equals(c.title())));
    }
}
