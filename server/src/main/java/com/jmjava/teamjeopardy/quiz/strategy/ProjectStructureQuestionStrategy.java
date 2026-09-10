package com.jmjava.teamjeopardy.quiz.strategy;

import com.jmjava.teamjeopardy.graph.CodeEdge;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeNode;
import com.jmjava.teamjeopardy.graph.ProjectKind;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import com.jmjava.teamjeopardy.quiz.JeopardyStyle;
import com.jmjava.teamjeopardy.quiz.QuestionPersona;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Project-kind structure clues (modules, components, plugins). Shared context for both personas;
 * tagged CODER but kept at lower priority than DEV/QA specialty categories.
 */
@Component
public class ProjectStructureQuestionStrategy implements QuestionStrategy {

    @Override
    public String id() {
        return "project-structure";
    }

    @Override
    public QuestionPersona persona() {
        return QuestionPersona.CODER;
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public List<Category> build(CodeGraph graph, AtomicInteger clueSeq) {
        ProjectKind kind = graph.getProjectKind() == null ? ProjectKind.GENERIC : graph.getProjectKind();
        List<Category> categories = new ArrayList<>();
        if (kind == ProjectKind.MAVEN || kind == ProjectKind.GRADLE) {
            categories.add(modules(graph, clueSeq, kind));
            categories.add(buildDeps(graph, clueSeq, kind));
            categories.add(hierarchy(graph, clueSeq, "CLASS HIERARCHY"));
        }
        if (kind == ProjectKind.VUE || kind == ProjectKind.NPM) {
            categories.add(components(graph, clueSeq));
            categories.add(vueHierarchy(graph, clueSeq));
            categories.add(npmDeps(graph, clueSeq));
        }
        if (kind == ProjectKind.PYTHON || kind == ProjectKind.GENERIC) {
            categories.add(hierarchy(graph, clueSeq, "TYPE HIERARCHY"));
        }
        return categories.stream()
                .filter(c -> c.clues() != null && !c.clues().isEmpty())
                .toList();
    }

    private Category modules(CodeGraph graph, AtomicInteger seq, ProjectKind kind) {
        List<CodeNode> modules = graph.nodesOfKind(CodeNode.NodeKind.MODULE);
        List<Clue> clues = new ArrayList<>();
        String tool = kind == ProjectKind.GRADLE ? "Gradle" : "Maven";
        clues.add(clue(seq,
                "Ingest recorded this many " + tool + " modules in the graph.",
                JeopardyStyle.whatIs(String.valueOf(modules.size())),
                "MODULE node count.", null));
        modules.stream().limit(6).forEach(module -> clues.add(clue(seq,
                "This " + tool + " module was parsed from `"
                        + JeopardyStyle.redact(String.valueOf(module.filePath()), module.name()) + "`.",
                JeopardyStyle.whatIs(module.name()),
                module.signature(),
                module.filePath())));
        return new Category("cat-modules", "MODULE MADNESS", clues);
    }

    private Category buildDeps(CodeGraph graph, AtomicInteger seq, ProjectKind kind) {
        List<CodeNode> deps = graph.nodesOfKind(CodeNode.NodeKind.DEPENDENCY).stream()
                .filter(d -> kind.name().toLowerCase().equals(d.language())
                        || "maven".equals(d.language())
                        || "gradle".equals(d.language()))
                .toList();
        List<Clue> clues = new ArrayList<>();
        clues.add(clue(seq,
                "The graph contains this many declared " + kind.name().toLowerCase() + " dependency nodes.",
                JeopardyStyle.whatIs(String.valueOf(deps.size())),
                "DEPENDENCY nodes for build tool coordinates.", null));
        deps.stream().limit(6).forEach(dep -> clues.add(clue(seq,
                "A build file pulls in this coordinate from `"
                        + JeopardyStyle.basename(dep.filePath()) + "`.",
                JeopardyStyle.whatIs(dep.name()),
                dep.signature() == null ? dep.qualifiedName() : dep.signature(),
                dep.filePath())));
        return new Category("cat-build-deps", "BUILD DEPENDENCIES", clues);
    }

    private Category components(CodeGraph graph, AtomicInteger seq) {
        List<CodeNode> components = graph.nodesOfKind(CodeNode.NodeKind.COMPONENT).stream()
                .filter(c -> c.filePath() != null && c.filePath().endsWith(".vue"))
                .toList();
        List<Clue> clues = new ArrayList<>();
        clues.add(clue(seq,
                "Ingest indexed this many Vue single-file components.",
                JeopardyStyle.whatIs(String.valueOf(components.size())),
                "COMPONENT nodes backed by .vue files.", null));
        components.stream().limit(6).forEach(c -> clues.add(clue(seq,
                "This Vue SFC lives under `" + JeopardyStyle.pathHint(c.filePath(), c.name()) + "`.",
                JeopardyStyle.whatIs(c.name()),
                c.signature(),
                c.filePath())));
        return new Category("cat-components", "COMPONENT CATALOG", clues);
    }

    private Category npmDeps(CodeGraph graph, AtomicInteger seq) {
        List<CodeNode> deps = graph.nodesOfKind(CodeNode.NodeKind.DEPENDENCY).stream()
                .filter(d -> "npm".equals(d.language()))
                .toList();
        List<Clue> clues = new ArrayList<>();
        clues.add(clue(seq,
                "package.json contributed this many npm dependency nodes.",
                JeopardyStyle.whatIs(String.valueOf(deps.size())),
                "DEPENDENCY nodes with language=npm.", null));
        deps.stream().limit(6).forEach(d -> clues.add(clue(seq,
                "package.json pins this package at version `" + d.snippet() + "`.",
                JeopardyStyle.whatIs(d.name()),
                d.signature(),
                d.filePath())));
        return new Category("cat-npm", "NPM DEPENDENCIES", clues);
    }

    private Category hierarchy(CodeGraph graph, AtomicInteger seq, String title) {
        List<CodeEdge> extendsEdges = graph.edgesOf(CodeEdge.Relation.EXTENDS);
        List<CodeEdge> implementsEdges = graph.edgesOf(CodeEdge.Relation.IMPLEMENTS);
        List<Clue> clues = new ArrayList<>();
        if (extendsEdges.isEmpty() && implementsEdges.isEmpty()) {
            return new Category("cat-hierarchy", title, clues);
        }
        clues.add(clue(seq,
                "Hierarchy enrichment recorded this many EXTENDS relationships.",
                JeopardyStyle.whatIs(String.valueOf(extendsEdges.size())),
                "EXTENDS edge count.", null));
        clues.add(clue(seq,
                "Hierarchy enrichment recorded this many IMPLEMENTS relationships.",
                JeopardyStyle.whatIs(String.valueOf(implementsEdges.size())),
                "IMPLEMENTS edge count.", null));
        extendsEdges.stream().limit(4).forEach(edge -> {
            String child = graph.findById(edge.fromId()).map(CodeNode::name).orElse("?");
            String parent = graph.findById(edge.toId()).map(CodeNode::name).orElse("?");
            clues.add(clue(seq,
                    "This type extends `" + parent + "`.",
                    JeopardyStyle.whatIs(child),
                    "EXTENDS edge.",
                    graph.findById(edge.fromId()).map(CodeNode::filePath).orElse(null)));
        });
        return new Category("cat-hierarchy", title, clues);
    }

    private Category vueHierarchy(CodeGraph graph, AtomicInteger seq) {
        List<CodeEdge> uses = graph.edgesOf(CodeEdge.Relation.USES);
        List<Clue> clues = new ArrayList<>();
        if (uses.isEmpty()) {
            return new Category("cat-vue-hierarchy", "COMPONENT TREE", clues);
        }
        clues.add(clue(seq,
                "Component-tree enrichment recorded this many parent→child Vue USES edges.",
                JeopardyStyle.whatIs(String.valueOf(uses.size())),
                "USES edge count.", null));
        uses.stream().limit(5).forEach(edge -> {
            String parent = graph.findById(edge.fromId()).map(CodeNode::name).orElse("?");
            String child = graph.findById(edge.toId()).map(CodeNode::name).orElse("?");
            clues.add(clue(seq,
                    "`" + parent + "`'s template renders this child component.",
                    JeopardyStyle.whatIs(child),
                    "Vue USES edge.",
                    graph.findById(edge.fromId()).map(CodeNode::filePath).orElse(null)));
        });
        return new Category("cat-vue-hierarchy", "COMPONENT TREE", clues);
    }

    private static Clue clue(AtomicInteger seq, String prompt, String response, String explanation, String source) {
        return new Clue("c" + seq.getAndIncrement(), 0, prompt, response, explanation, source, false);
    }
}
