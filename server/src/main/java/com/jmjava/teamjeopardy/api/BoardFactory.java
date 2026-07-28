package com.jmjava.teamjeopardy.api;

import com.jmjava.teamjeopardy.graph.CodeEdge;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeGraphIngester;
import com.jmjava.teamjeopardy.graph.CodeNode;
import com.jmjava.teamjeopardy.graph.ProjectKind;
import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.QuestionGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds Jeopardy boards from in-repo ingest (skgraph-derived Maven/OSGi + Gradle + Vue).
 */
@Service
public class BoardFactory {

    private final CodeGraphIngester codeGraphIngester;
    private final QuestionGenerator questionGenerator;

    @Value("${team-jeopardy.sample-code-path}")
    private String sampleMavenPath;

    @Value("${team-jeopardy.sample-gradle-path:../samples/sample-gradle}")
    private String sampleGradlePath;

    @Value("${team-jeopardy.sample-vue-path:../samples/sample-vue}")
    private String sampleVuePath;

    public BoardFactory(CodeGraphIngester codeGraphIngester, QuestionGenerator questionGenerator) {
        this.codeGraphIngester = codeGraphIngester;
        this.questionGenerator = questionGenerator;
    }

    public record BuiltBoard(Board board, Map<String, Object> summary) {
    }

    public BuiltBoard fromSample(String sampleType, String boardTitle) throws IOException {
        ProjectKind kind = ProjectKind.fromSampleType(sampleType);
        Path path = switch (kind) {
            case GRADLE -> Path.of(sampleGradlePath);
            case VUE, NPM -> Path.of(sampleVuePath);
            default -> Path.of(sampleMavenPath);
        };
        return fromPath(path, boardTitle, kind);
    }

    public BuiltBoard fromPath(Path path, String boardTitle) throws IOException {
        return fromPath(path, boardTitle, null);
    }

    public BuiltBoard fromPath(Path path, String boardTitle, ProjectKind forcedKind) throws IOException {
        CodeGraph graph = codeGraphIngester.ingest(path, forcedKind);
        Board board = questionGenerator.generate(graph, boardTitle);
        return new BuiltBoard(board, summary(graph, board));
    }

    public Map<String, String> samplePaths() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("maven", Path.of(sampleMavenPath).toAbsolutePath().normalize().toString());
        paths.put("gradle", Path.of(sampleGradlePath).toAbsolutePath().normalize().toString());
        paths.put("vue", Path.of(sampleVuePath).toAbsolutePath().normalize().toString());
        return paths;
    }

    private Map<String, Object> summary(CodeGraph graph, Board board) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("engine", "team-jeopardy-ingest");
        summary.put("derivedFrom", "jmjava/skgraph (Maven/OSGi extract) + Gradle/Vue extensions");
        summary.put("projectKind", graph.getProjectKind().name());
        summary.put("projectName", graph.getProjectName());
        summary.put("rootPath", graph.getRootPath());
        summary.put("nodes", graph.stats().nodeCount());
        summary.put("edges", graph.stats().edgeCount());
        summary.put("modules", graph.nodesOfKind(CodeNode.NodeKind.MODULE).size());
        summary.put("dependencies", graph.nodesOfKind(CodeNode.NodeKind.DEPENDENCY).size());
        summary.put("components", graph.nodesOfKind(CodeNode.NodeKind.COMPONENT).size());
        summary.put("extendsEdges", graph.edgesOf(CodeEdge.Relation.EXTENDS).size());
        summary.put("implementsEdges", graph.edgesOf(CodeEdge.Relation.IMPLEMENTS).size());
        summary.put("vueUsesEdges", graph.edgesOf(CodeEdge.Relation.USES).size());
        summary.put("categories", board.categories().size());
        return summary;
    }
}
