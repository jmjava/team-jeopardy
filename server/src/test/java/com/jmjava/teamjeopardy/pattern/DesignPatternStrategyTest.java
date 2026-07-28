package com.jmjava.teamjeopardy.pattern;

import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeGraphIngester;
import com.jmjava.teamjeopardy.graph.ProjectDetector;
import com.jmjava.teamjeopardy.graph.ProjectKind;
import com.jmjava.teamjeopardy.ingest.enrich.JavaClassHierarchyEnricher;
import com.jmjava.teamjeopardy.ingest.enrich.VueComponentHierarchyEnricher;
import com.jmjava.teamjeopardy.ingest.gradle.GradleProjectIngester;
import com.jmjava.teamjeopardy.ingest.maven.MavenReactorIngester;
import com.jmjava.teamjeopardy.ingest.osgi.OsgiManifestParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignPatternStrategyTest {

    private CodeGraphIngester ingester;

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
    }

    @Test
    void detectsJavaStrategyRepositoryAndSingleton() throws Exception {
        Path root = Path.of("..", "samples", "sample-gradle").toAbsolutePath().normalize();
        CodeGraph graph = ingester.ingest(root, ProjectKind.GRADLE);
        assertTrue(hasPattern(graph, "java", "Strategy"));
        assertTrue(hasPattern(graph, "java", "Repository"));
        assertTrue(hasPattern(graph, "java", "Singleton"));
    }

    @Test
    void detectsVueComposableStoreAndContainer() throws Exception {
        Path root = Path.of("..", "samples", "sample-vue").toAbsolutePath().normalize();
        CodeGraph graph = ingester.ingest(root, ProjectKind.VUE);
        assertTrue(hasPattern(graph, "vue", "Composable"));
        assertTrue(graph.getPatternFacts().stream().anyMatch(f ->
                "vue".equals(f.language())
                        && String.valueOf(f.attributes().get("pattern")).contains("Store")));
        assertTrue(graph.getPatternFacts().stream().anyMatch(f -> {
            if (!"vue".equals(f.language())) {
                return false;
            }
            String pattern = String.valueOf(f.attributes().get("pattern"));
            return pattern.contains("Container")
                    || "Presentational Component".equals(pattern)
                    || "Composition API SFC".equals(pattern);
        }));
    }

    @Test
    void detectsPythonStrategyDataclassAndRepository() throws Exception {
        Path root = Path.of("..", "samples", "sample-python").toAbsolutePath().normalize();
        CodeGraph graph = ingester.ingest(root, ProjectKind.PYTHON);
        assertTrue(hasPattern(graph, "python", "Strategy"));
        assertTrue(hasPattern(graph, "python", "Dataclass"));
        assertTrue(hasPattern(graph, "python", "Repository"));
        assertTrue(graph.getPatternFacts().stream().anyMatch(f -> {
            if (!"python".equals(f.language())) {
                return false;
            }
            String pattern = String.valueOf(f.attributes().get("pattern"));
            return "Context Manager".equals(pattern)
                    || "Decorator".equals(pattern)
                    || "Singleton".equals(pattern);
        }));
    }

    @Test
    void detectsPlainJavaScriptFactoryAndObserverWithoutClaimingVue() throws Exception {
        Path root = Path.of("..", "samples", "sample-vue").toAbsolutePath().normalize();
        CodeGraph graph = ingester.ingest(root, ProjectKind.VUE);
        assertTrue(graph.getPatternFacts().stream().anyMatch(f ->
                "javascript".equals(f.language())
                        && "Factory Method".equals(f.attributes().get("pattern"))
                        && f.evidenceFile() != null
                        && f.evidenceFile().contains("createClue")));
        assertTrue(hasPattern(graph, "javascript", "Observer"));
        assertTrue(graph.getPatternFacts().stream().noneMatch(f ->
                "javascript".equals(f.language())
                        && "Composable".equals(f.attributes().get("pattern"))));
    }

    private static boolean hasPattern(CodeGraph graph, String language, String pattern) {
        return graph.getPatternFacts().stream().anyMatch(f ->
                language.equals(f.language()) && pattern.equals(f.attributes().get("pattern")));
    }
}
