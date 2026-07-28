package com.jmjava.teamjeopardy.quiz.strategy;

import com.jmjava.teamjeopardy.graph.CodeEdge;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeNode;
import com.jmjava.teamjeopardy.pattern.PatternFact;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import com.jmjava.teamjeopardy.quiz.QuestionPersona;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * QA-facing clues: test matrices, contracts, regression blast radius, risk hotspots.
 */
@Component
public class QaQuestionStrategy implements QuestionStrategy {

    @Override
    public String id() {
        return "qa-core";
    }

    @Override
    public QuestionPersona persona() {
        return QuestionPersona.QA;
    }

    @Override
    public int priority() {
        return 85;
    }

    @Override
    public List<Category> build(CodeGraph graph, AtomicInteger clueSeq) {
        List<Category> categories = new ArrayList<>();
        categories.add(buildTestMatrix(graph, clueSeq));
        categories.add(buildContracts(graph, clueSeq));
        categories.add(buildBlastRadius(graph, clueSeq));
        categories.add(buildRiskPatterns(graph, clueSeq));
        return categories.stream()
                .filter(c -> c.clues() != null && !c.clues().isEmpty())
                .toList();
    }

    /**
     * Interfaces / ABCs with multiple implementors — classic combinatorial test targets.
     */
    private Category buildTestMatrix(CodeGraph graph, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        Map<String, List<String>> implementors = new HashMap<>();
        for (CodeEdge edge : graph.edgesOf(CodeEdge.Relation.IMPLEMENTS)) {
            implementors.computeIfAbsent(edge.toId(), k -> new ArrayList<>()).add(edge.fromId());
        }
        for (CodeEdge edge : graph.edgesOf(CodeEdge.Relation.EXTENDS)) {
            graph.findById(edge.toId()).ifPresent(parent -> {
                if (parent.kind() == CodeNode.NodeKind.INTERFACE
                        || (parent.signature() != null && parent.signature().toLowerCase(Locale.ROOT).contains("abstract"))
                        || (parent.name() != null && parent.name().toLowerCase(Locale.ROOT).contains("strategy"))) {
                    implementors.computeIfAbsent(edge.toId(), k -> new ArrayList<>()).add(edge.fromId());
                }
            });
        }
        implementors.entrySet().stream()
                .filter(e -> e.getValue().stream().distinct().count() >= 2)
                .sorted((a, b) -> Long.compare(
                        b.getValue().stream().distinct().count(),
                        a.getValue().stream().distinct().count()))
                .limit(5)
                .forEach(e -> {
                    CodeNode parent = graph.findById(e.getKey()).orElse(null);
                    if (parent == null) {
                        return;
                    }
                    List<String> names = e.getValue().stream().distinct()
                            .map(id -> graph.findById(id).map(CodeNode::name).orElse(id))
                            .toList();
                    clues.add(clue(seq,
                            "QA test matrix: `" + parent.name() + "` has " + names.size()
                                    + " variants — name the abstraction under test.",
                            "What is " + parent.name() + "?",
                            "Variants: " + String.join(", ", names),
                            parent.filePath()));
                    if (names.size() >= 2) {
                        clues.add(clue(seq,
                                "A regression suite for `" + parent.name()
                                        + "` should cover this concrete variant among: "
                                        + String.join(", ", names) + ".",
                                "What is " + names.get(0) + "?",
                                "One of " + names.size() + " implementors/subclasses.",
                                parent.filePath()));
                    }
                });
        return new Category("cat-qa-matrix", "QA: TEST MATRIX", clues);
    }

    /**
     * Props / signatures / routes as contracts callers and tests must honor.
     */
    private Category buildContracts(CodeGraph graph, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        graph.nodesOfKind(CodeNode.NodeKind.FUNCTION).stream()
                .filter(n -> n.name() != null && (n.name().endsWith(".props") || "props".equals(n.signature())))
                .limit(4)
                .forEach(props -> clues.add(clue(seq,
                        "Contract check: this Vue props declaration must stay stable for UI tests — `"
                                + truncate(props.snippet() == null ? props.qualifiedName() : props.snippet(), 120) + "`.",
                        "What is " + props.name().replace(".props", "") + "?",
                        "COMPONENT props contract.",
                        props.filePath())));

        graph.nodesOfKind(CodeNode.NodeKind.ROUTE).stream().limit(4).forEach(route ->
                clues.add(clue(seq,
                        "Boundary contract: an HTTP/router path registered as `" + route.name()
                                + "` — where would an E2E smoke hit first?",
                        "What is " + route.name() + "?",
                        route.signature(),
                        route.filePath())));

        graph.nodesOfKind(CodeNode.NodeKind.METHOD).stream()
                .filter(m -> m.signature() != null && m.signature().contains("("))
                .sorted((a, b) -> Integer.compare(
                        b.signature() == null ? 0 : b.signature().length(),
                        a.signature() == null ? 0 : a.signature().length()))
                .limit(4)
                .forEach(m -> clues.add(clue(seq,
                        "API contract under test: `" + m.signature()
                                + "` in `" + m.filePath() + "`. Name the callable.",
                        "What is " + m.name() + "?",
                        "Signature-level contract for unit/integration tests.",
                        m.filePath())));

        return new Category("cat-qa-contracts", "QA: CONTRACTS", clues);
    }

    /**
     * Fan-in / composition edges → regression blast radius.
     */
    private Category buildBlastRadius(CodeGraph graph, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        Map<String, Long> fanIn = new HashMap<>();
        for (CodeEdge.Relation rel : List.of(
                CodeEdge.Relation.USES, CodeEdge.Relation.CALLS, CodeEdge.Relation.IMPORTS,
                CodeEdge.Relation.EXTENDS, CodeEdge.Relation.IMPLEMENTS)) {
            for (CodeEdge edge : graph.edgesOf(rel)) {
                fanIn.merge(edge.toId(), 1L, Long::sum);
            }
        }
        fanIn.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> graph.findById(e.getKey()).ifPresent(node -> {
                    if (node.kind() == CodeNode.NodeKind.IMPORT || node.kind() == CodeNode.NodeKind.PACKAGE) {
                        return;
                    }
                    clues.add(clue(seq,
                            "Blast radius: " + e.getValue()
                                    + " inbound edges target this symbol — a high-value regression candidate.",
                            "What is " + node.name() + "?",
                            "Fan-in across USES/CALLS/IMPORTS/EXTENDS/IMPLEMENTS.",
                            node.filePath()));
                }));

        graph.edgesOf(CodeEdge.Relation.USES).stream().limit(4).forEach(edge -> {
            String parent = graph.findById(edge.fromId()).map(CodeNode::name).orElse("?");
            String child = graph.findById(edge.toId()).map(CodeNode::name).orElse("?");
            clues.add(clue(seq,
                    "If `" + child + "` breaks rendering, this parent component's UI test likely fails first.",
                    "What is " + parent + "?",
                    "Vue USES composition edge.",
                    graph.findById(edge.fromId()).map(CodeNode::filePath).orElse(null)));
        });
        return new Category("cat-qa-blast", "QA: BLAST RADIUS", clues);
    }

    /**
     * Pattern facts that imply test risk (Singleton state, Observer leaks, Strategy variants).
     */
    private Category buildRiskPatterns(CodeGraph graph, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        List<PatternFact> facts = graph.getPatternFacts();
        if (facts == null) {
            return new Category("cat-qa-risk", "QA: PATTERN RISK", clues);
        }
        for (PatternFact fact : facts) {
            String pattern = String.valueOf(fact.attributes().getOrDefault("pattern", ""));
            String risk = riskCue(pattern);
            if (risk == null) {
                continue;
            }
            clues.add(clue(seq,
                    risk + " Evidence: " + fact.text(),
                    "What is " + pattern + "?",
                    "QA risk lens on PatternFact (" + fact.language() + ").",
                    fact.evidenceFile()));
        }
        // Prefer a few strong ones
        clues = clues.stream().limit(8).collect(Collectors.toCollection(ArrayList::new));
        return new Category("cat-qa-risk", "QA: PATTERN RISK", clues);
    }

    private static String riskCue(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        String p = pattern.toLowerCase(Locale.ROOT);
        if (p.contains("singleton")) {
            return "Shared mutable state risk — which pattern needs isolation between tests?";
        }
        if (p.contains("observer") || p.contains("listener")) {
            return "Subscription leak risk — which pattern needs register/unregister coverage?";
        }
        if (p.contains("strategy")) {
            return "Combinatorial behavior risk — which pattern needs a variant matrix?";
        }
        if (p.contains("repository") || p.contains("dao")) {
            return "Persistence boundary risk — which pattern needs fake/in-memory doubles?";
        }
        if (p.contains("store") || p.contains("pinia") || p.contains("vuex")) {
            return "Client state risk — which pattern needs store reset between UI tests?";
        }
        if (p.contains("middleware")) {
            return "Pipeline ordering risk — which pattern needs negative-path filter tests?";
        }
        if (p.contains("factory")) {
            return "Construction edge-case risk — which pattern needs null/invalid input tests?";
        }
        return null;
    }

    private static Clue clue(AtomicInteger seq, String prompt, String response, String explanation, String source) {
        return new Clue("c" + seq.getAndIncrement(), 0, prompt, response, explanation, source, false);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\t', ' ').replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }
}
