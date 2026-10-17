package com.jmjava.teamjeopardy.quiz.strategy;

import com.jmjava.teamjeopardy.graph.CodeEdge;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeNode;
import com.jmjava.teamjeopardy.pattern.PatternFact;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import com.jmjava.teamjeopardy.quiz.JeopardyStyle;
import com.jmjava.teamjeopardy.quiz.QuestionPersona;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Engineer-facing clues: patterns, types, APIs, ownership, call/import structure.
 */
@Component
public class CoderQuestionStrategy implements QuestionStrategy {

    @Override
    public String id() {
        return "coder-core";
    }

    @Override
    public QuestionPersona persona() {
        return QuestionPersona.CODER;
    }

    @Override
    public int priority() {
        return 80;
    }

    @Override
    public List<Category> build(CodeGraph graph, AtomicInteger clueSeq) {
        List<Category> categories = new ArrayList<>();
        categories.add(buildPatterns(graph, clueSeq));
        categories.add(buildNameThatType(graph, clueSeq));
        categories.add(buildApis(graph, clueSeq));
        categories.add(buildOwnership(graph, clueSeq));
        return categories.stream()
                .filter(c -> c.clues() != null && !c.clues().isEmpty())
                .toList();
    }

    private Category buildPatterns(CodeGraph graph, AtomicInteger seq) {
        List<PatternFact> facts = graph.getPatternFacts();
        List<Clue> clues = new ArrayList<>();
        if (facts == null || facts.isEmpty()) {
            return new Category("cat-dev-patterns", "DEV: DESIGN PATTERNS", clues);
        }
        Map<String, Long> byPattern = facts.stream()
                .collect(Collectors.groupingBy(
                        f -> String.valueOf(f.attributes().getOrDefault("pattern", f.predicate())),
                        Collectors.counting()));
        byPattern.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> clues.add(clue(seq,
                        "Ingest matched this software design pattern " + e.getValue()
                                + " time(s) across the codebase.",
                        JeopardyStyle.whatIs(e.getKey()),
                        "Language-scoped PatternStrategy attribute `pattern`.",
                        null)));
        for (PatternFact fact : facts.stream().limit(5).toList()) {
            String pattern = String.valueOf(fact.attributes().getOrDefault("pattern", "pattern"));
            String langBit = fact.language() == null || fact.language().isBlank()
                    ? ""
                    : fact.language() + " ";
            String evidence = JeopardyStyle.redact(fact.text(), pattern);
            clues.add(clue(seq,
                    "A scanner flagged this " + langBit + "pattern from `"
                            + JeopardyStyle.redact(JeopardyStyle.basename(fact.evidenceFile()), pattern) + "`:\n" + evidence,
                    JeopardyStyle.whatIs(pattern),
                    "predicate=" + fact.predicate() + " confidence=" + fact.confidence(),
                    fact.evidenceFile()));
        }
        return new Category("cat-dev-patterns", "DEV: DESIGN PATTERNS", clues);
    }

    private Category buildNameThatType(CodeGraph graph, AtomicInteger seq) {
        List<CodeNode> types = new ArrayList<>();
        types.addAll(graph.nodesOfKind(CodeNode.NodeKind.CLASS));
        types.addAll(graph.nodesOfKind(CodeNode.NodeKind.INTERFACE));
        types.addAll(graph.nodesOfKind(CodeNode.NodeKind.COMPONENT));
        types = types.stream()
                .filter(n -> n.snippet() != null && !n.snippet().isBlank())
                .sorted(Comparator.comparing(CodeNode::qualifiedName, Comparator.nullsLast(String::compareTo)))
                .limit(8)
                .toList();
        List<Clue> clues = new ArrayList<>();
        for (CodeNode type : types) {
            String kind = switch (type.kind()) {
                case INTERFACE -> "interface";
                case COMPONENT -> "component";
                default -> "class";
            };
            clues.add(clue(seq,
                    "This " + kind + " lives under `" + JeopardyStyle.pathHint(type.filePath(), type.name()) + "`:\n```\n"
                            + JeopardyStyle.redact(truncate(type.snippet(), 200), type.name()) + "\n```",
                    JeopardyStyle.whatIs(type.name()),
                    "Qualified: " + type.qualifiedName(),
                    type.filePath()));
        }
        return new Category("cat-dev-types", "DEV: NAME THAT TYPE", clues);
    }

    private Category buildApis(CodeGraph graph, AtomicInteger seq) {
        List<CodeNode> methods = new ArrayList<>();
        methods.addAll(graph.nodesOfKind(CodeNode.NodeKind.METHOD));
        methods.addAll(graph.nodesOfKind(CodeNode.NodeKind.FUNCTION));
        methods = methods.stream()
                .filter(n -> n.signature() != null && !n.signature().isBlank())
                .sorted(Comparator.comparingInt((CodeNode n) -> n.signature().length()).reversed())
                .limit(8)
                .toList();
        List<Clue> clues = new ArrayList<>();
        for (CodeNode method : methods) {
            String signature = JeopardyStyle.redact(method.signature(), method.name());
            clues.add(clue(seq,
                    "In `" + JeopardyStyle.pathHint(method.filePath(), method.name()) + "`, this API is declared as `"
                            + signature + "`.",
                    JeopardyStyle.whatIs(method.name()),
                    method.snippet() != null ? truncate(method.snippet(), 160) : method.qualifiedName(),
                    method.filePath()));
        }
        return new Category("cat-dev-apis", "DEV: API SURFACE", clues);
    }

    private Category buildOwnership(CodeGraph graph, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        Map<String, Long> importsByFile = graph.edgesOf(CodeEdge.Relation.IMPORTS).stream()
                .collect(Collectors.groupingBy(CodeEdge::fromId, Collectors.counting()));
        importsByFile.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> graph.findById(e.getKey()).ifPresent(file ->
                        clues.add(clue(seq,
                                "This source file owns " + e.getValue()
                                        + " import edges — a likely integration seam.",
                                JeopardyStyle.whatIs(file.qualifiedName()),
                                "IMPORTS fan-out from FILE node.",
                                file.filePath()))));

        Map<String, Long> importCounts = graph.nodesOfKind(CodeNode.NodeKind.IMPORT).stream()
                .collect(Collectors.groupingBy(CodeNode::qualifiedName, Collectors.counting()));
        importCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(4)
                .forEach(e -> clues.add(clue(seq,
                        "This dependency appears in " + e.getValue() + " import(s).",
                        JeopardyStyle.whatIs(e.getKey()),
                        "IMPORT node grouping.",
                        null)));
        return new Category("cat-dev-ownership", "DEV: OWNERSHIP & DEPS", clues);
    }

    private static Clue clue(AtomicInteger seq, String prompt, String response, String explanation, String source) {
        return new Clue("c" + seq.getAndIncrement(), 0, prompt, response, explanation, source, false);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\t', ' ');
        return normalized.length() <= max ? normalized : normalized.substring(0, max).trim() + "…";
    }
}
