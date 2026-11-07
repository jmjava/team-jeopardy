package com.jmjava.teamjeopardy.ingest;

import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeGraphIngester;
import com.jmjava.teamjeopardy.graph.ProjectDetector;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymlinkSourceEscapeTest {

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
    void ingestDoesNotReadASymlinkOutsideTheTree(@TempDir Path root, @TempDir Path outside) throws Exception {
        Files.writeString(outside.resolve("Secret.java"), """
                public class SentinelOutsideSecret {
                    // SENTINEL-OUTSIDE-SECRET
                }
                """);
        Files.writeString(root.resolve("Keep.java"), """
                public class Keep {
                }
                """);
        Files.createSymbolicLink(root.resolve("Leak.java"), outside.resolve("Secret.java"));

        CodeGraph graph = ingester.ingest(root);
        String visible = graph.nodes().stream()
                .map(node -> String.join("\n",
                        text(node.name()),
                        text(node.qualifiedName()),
                        text(node.filePath()),
                        text(node.signature()),
                        text(node.snippet())))
                .reduce("", (left, right) -> left + "\n" + right);

        assertFalse(visible.contains("SentinelOutsideSecret"));
        assertFalse(visible.contains("SENTINEL-OUTSIDE-SECRET"));
        assertFalse(visible.contains("Leak.java"));
        assertTrue(graph.nodes().stream().anyMatch(node -> "Keep.java".equals(node.name())));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
