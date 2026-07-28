package com.jmjava.teamjeopardy.api;

import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeGraphIngester;
import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.QuestionGenerator;
import com.jmjava.teamjeopardy.skgraph.SkgraphIngestService;
import com.jmjava.teamjeopardy.skgraph.SkgraphQuestionGenerator;
import com.skgraph.model.IngestResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prefers skgraph Maven-reactor ingest; falls back to the lightweight
 * multi-language walker when no pom.xml is present.
 */
@Service
public class BoardFactory {

    private final SkgraphIngestService skgraphIngestService;
    private final SkgraphQuestionGenerator skgraphQuestionGenerator;
    private final CodeGraphIngester codeGraphIngester;
    private final QuestionGenerator questionGenerator;

    public BoardFactory(
            SkgraphIngestService skgraphIngestService,
            SkgraphQuestionGenerator skgraphQuestionGenerator,
            CodeGraphIngester codeGraphIngester,
            QuestionGenerator questionGenerator
    ) {
        this.skgraphIngestService = skgraphIngestService;
        this.skgraphQuestionGenerator = skgraphQuestionGenerator;
        this.codeGraphIngester = codeGraphIngester;
        this.questionGenerator = questionGenerator;
    }

    public record BuiltBoard(Board board, Map<String, Object> summary) {
    }

    public BuiltBoard fromSample(String boardTitle) {
        IngestResult result = skgraphIngestService.ingestSample();
        Board board = skgraphQuestionGenerator.generate(result, boardTitle);
        return new BuiltBoard(board, skgraphSummary(result, board));
    }

    public BuiltBoard fromPath(
            Path path,
            String repo,
            String branch,
            String commitSha,
            String boardTitle
    ) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute.resolve("pom.xml"))) {
            IngestResult result = skgraphIngestService.ingestPath(absolute, repo, branch, commitSha);
            Board board = skgraphQuestionGenerator.generate(result, boardTitle);
            return new BuiltBoard(board, skgraphSummary(result, board));
        }

        CodeGraph graph = codeGraphIngester.ingest(absolute);
        Board board = questionGenerator.generate(graph, boardTitle);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("engine", "lightweight-code-graph");
        summary.put("rootPath", graph.getRootPath());
        summary.put("nodes", graph.stats().nodeCount());
        summary.put("edges", graph.stats().edgeCount());
        summary.put("categories", board.categories().size());
        return new BuiltBoard(board, summary);
    }

    private Map<String, Object> skgraphSummary(IngestResult result, Board board) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("engine", "skgraph-core");
        summary.put("repo", result.getContext().getRepo());
        summary.put("branch", result.getContext().getBranch());
        summary.put("rootPath", result.getContext().getRootPath());
        summary.put("modules", result.getReactor().getModules().size());
        summary.put("edges", result.getEdges().size());
        summary.put("propositions", result.getPropositions().size());
        summary.put("javaFiles", result.getJava() == null ? 0 : result.getJava().getFiles().size());
        summary.put("osgiBundles", result.getOsgi() == null ? 0 : result.getOsgi().getBundles().size());
        summary.put("categories", board.categories().size());
        return summary;
    }
}
