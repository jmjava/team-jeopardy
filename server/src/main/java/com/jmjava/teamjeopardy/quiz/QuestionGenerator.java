package com.jmjava.teamjeopardy.quiz;

import com.jmjava.teamjeopardy.graph.CodeEdge;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeNode;
import com.jmjava.teamjeopardy.graph.ProjectKind;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Turns an ingested code graph into a Jeopardy-style board.
 * Categories adapt to Maven (skgraph-derived), Gradle, Vue/npm, or generic trees.
 */
@Service
public class QuestionGenerator {

    private static final int[] VALUES = {200, 400, 600, 800, 1000};

    public Board generate(CodeGraph graph, String boardTitle) {
        List<Category> categories = new ArrayList<>();
        AtomicInteger clueSeq = new AtomicInteger(1);
        ProjectKind kind = graph.getProjectKind() == null ? ProjectKind.GENERIC : graph.getProjectKind();

        if (kind == ProjectKind.MAVEN || kind == ProjectKind.GRADLE) {
            categories.add(buildModuleMadness(graph, clueSeq, kind));
            categories.add(buildBuildDependencies(graph, clueSeq, kind));
            categories.add(buildPluginsAndTooling(graph, clueSeq, kind));
            categories.add(buildJavaHierarchy(graph, clueSeq));
        }
        if (kind == ProjectKind.VUE || kind == ProjectKind.NPM) {
            categories.add(buildComponentCatalog(graph, clueSeq));
            categories.add(buildVueHierarchy(graph, clueSeq));
            categories.add(buildNpmDependencies(graph, clueSeq));
            categories.add(buildScriptsAndRoutes(graph, clueSeq));
        }
        // Generic / mixed trees still get hierarchy categories when edges exist
        if (kind == ProjectKind.GENERIC || kind == ProjectKind.PYTHON) {
            categories.add(buildJavaHierarchy(graph, clueSeq));
            categories.add(buildVueHierarchy(graph, clueSeq));
        }

        categories.add(buildNameThatType(graph, clueSeq));
        categories.add(buildMethodSignatures(graph, clueSeq));
        categories.add(buildImportDependencies(graph, clueSeq));
        categories.add(buildFileFacts(graph, clueSeq));
        categories.add(buildLanguageMix(graph, clueSeq));
        categories.add(buildWhoCallsWhom(graph, clueSeq));

        categories = categories.stream()
                .filter(c -> c.clues() != null && !c.clues().isEmpty())
                .limit(6)
                .toList();

        // Ensure each category has up to 5 clues with ascending values
        categories = categories.stream()
                .map(this::normalizeCategory)
                .toList();

        var stats = graph.stats();
        long files = stats.nodesByKind().getOrDefault(CodeNode.NodeKind.FILE, 0L);
        long types = stats.nodesByKind().getOrDefault(CodeNode.NodeKind.CLASS, 0L)
                + stats.nodesByKind().getOrDefault(CodeNode.NodeKind.INTERFACE, 0L);
        long functions = stats.nodesByKind().getOrDefault(CodeNode.NodeKind.METHOD, 0L)
                + stats.nodesByKind().getOrDefault(CodeNode.NodeKind.FUNCTION, 0L);

        String title = boardTitle != null && !boardTitle.isBlank()
                ? boardTitle
                : kind.name() + " Jeopardy: " + (
                graph.getProjectName() != null
                        ? graph.getProjectName()
                        : Path.of(graph.getRootPath()).getFileName()
        );

        return new Board(
                title,
                graph.getRootPath(),
                categories,
                new Board.GraphDigest(stats.nodeCount(), stats.edgeCount(), (int) files, (int) types, (int) functions)
        );
    }

    private Category buildModuleMadness(CodeGraph graph, AtomicInteger seq, ProjectKind kind) {
        List<CodeNode> modules = graph.nodesOfKind(CodeNode.NodeKind.MODULE);
        List<Clue> clues = new ArrayList<>();
        String tool = kind == ProjectKind.GRADLE ? "Gradle" : "Maven";
        clues.add(clue(seq,
                "How many " + tool + " modules did ingest discover?",
                "What is " + modules.size() + "?",
                "MODULE node count.", null));
        for (CodeNode module : modules.stream().limit(6).toList()) {
            clues.add(clue(seq,
                    "This " + tool + " module is identified as `" + module.qualifiedName() + "`.",
                    "What is " + module.name() + "?",
                    module.signature(),
                    module.filePath()));
        }
        return new Category("cat-modules", "MODULE MADNESS", clues);
    }

    private Category buildBuildDependencies(CodeGraph graph, AtomicInteger seq, ProjectKind kind) {
        List<CodeNode> deps = graph.nodesOfKind(CodeNode.NodeKind.DEPENDENCY).stream()
                .filter(d -> kind.name().toLowerCase().equals(d.language())
                        || "maven".equals(d.language())
                        || "gradle".equals(d.language()))
                .toList();
        List<Clue> clues = new ArrayList<>();
        clues.add(clue(seq,
                "Declared " + kind.name().toLowerCase() + " dependency nodes in the graph.",
                "What is " + deps.size() + "?",
                "DEPENDENCY nodes for build tool coordinates.", null));
        deps.stream().limit(7).forEach(dep -> clues.add(clue(seq,
                "A dependency with coordinate/notation `" + dep.qualifiedName() + "`.",
                "What is " + dep.name() + "?",
                dep.signature(),
                dep.filePath())));
        return new Category("cat-build-deps", "DEPENDENCY DRAMA", clues);
    }

    private Category buildPluginsAndTooling(CodeGraph graph, AtomicInteger seq, ProjectKind kind) {
        List<CodeNode> plugins = graph.nodesOfKind(CodeNode.NodeKind.PLUGIN);
        List<Clue> clues = new ArrayList<>();
        clues.add(clue(seq,
                "How many " + kind.name().toLowerCase() + " plugins were indexed?",
                "What is " + plugins.size() + "?",
                "PLUGIN node count.", null));
        plugins.stream().limit(6).forEach(plugin -> clues.add(clue(seq,
                "This plugin id/artifact appears in the build: `" + plugin.qualifiedName() + "`.",
                "What is " + plugin.name() + "?",
                plugin.signature(),
                plugin.filePath())));
        return new Category("cat-plugins", "PLUGIN PLAY", clues);
    }

    private Category buildJavaHierarchy(CodeGraph graph, AtomicInteger seq) {
        List<CodeEdge> extendsEdges = graph.edgesOf(CodeEdge.Relation.EXTENDS);
        List<CodeEdge> implementsEdges = graph.edgesOf(CodeEdge.Relation.IMPLEMENTS);
        List<Clue> clues = new ArrayList<>();
        if (extendsEdges.isEmpty() && implementsEdges.isEmpty()) {
            return new Category("cat-java-hierarchy", "CLASS HIERARCHY", clues);
        }

        clues.add(clue(seq,
                "How many Java EXTENDS relationships did the hierarchy enricher record?",
                "What is " + extendsEdges.size() + "?",
                "EXTENDS edge count.", null));
        clues.add(clue(seq,
                "How many Java IMPLEMENTS relationships were resolved?",
                "What is " + implementsEdges.size() + "?",
                "IMPLEMENTS edge count.", null));

        for (CodeEdge edge : extendsEdges.stream().limit(4).toList()) {
            String child = graph.findById(edge.fromId()).map(CodeNode::name).orElse("?");
            String parent = graph.findById(edge.toId()).map(CodeNode::name).orElse("?");
            clues.add(clue(seq,
                    "This type extends `" + parent + "`.",
                    "What is " + child + "?",
                    graph.findById(edge.fromId()).map(CodeNode::qualifiedName).orElse(child),
                    graph.findById(edge.fromId()).map(CodeNode::filePath).orElse(null)));
        }
        for (CodeEdge edge : implementsEdges.stream().limit(4).toList()) {
            String child = graph.findById(edge.fromId()).map(CodeNode::name).orElse("?");
            String iface = graph.findById(edge.toId()).map(CodeNode::name).orElse("?");
            clues.add(clue(seq,
                    "This type implements `" + iface + "`.",
                    "What is " + child + "?",
                    graph.findById(edge.fromId()).map(CodeNode::qualifiedName).orElse(child),
                    graph.findById(edge.fromId()).map(CodeNode::filePath).orElse(null)));
        }
        return new Category("cat-java-hierarchy", "CLASS HIERARCHY", clues);
    }

    private Category buildVueHierarchy(CodeGraph graph, AtomicInteger seq) {
        List<CodeEdge> uses = graph.edgesOf(CodeEdge.Relation.USES);
        List<Clue> clues = new ArrayList<>();
        if (uses.isEmpty()) {
            return new Category("cat-vue-hierarchy", "COMPONENT TREE", clues);
        }

        clues.add(clue(seq,
                "How many Vue parent→child USES edges are in the composition tree?",
                "What is " + uses.size() + "?",
                "USES edge count from VueComponentHierarchyEnricher.", null));

        Map<String, Long> childrenByParent = uses.stream()
                .collect(Collectors.groupingBy(CodeEdge::fromId, Collectors.counting()));
        childrenByParent.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .flatMap(e -> graph.findById(e.getKey()))
                .ifPresent(parent -> clues.add(clue(seq,
                        "This component renders the most child components in its template.",
                        "What is " + parent.name() + "?",
                        parent.qualifiedName(),
                        parent.filePath())));

        for (CodeEdge edge : uses.stream().limit(5).toList()) {
            String parent = graph.findById(edge.fromId()).map(CodeNode::name).orElse("?");
            String child = graph.findById(edge.toId()).map(CodeNode::name).orElse("?");
            clues.add(clue(seq,
                    "`" + parent + "`'s template uses this child component.",
                    "What is " + child + "?",
                    "USES edge in the Vue component tree.",
                    graph.findById(edge.fromId()).map(CodeNode::filePath).orElse(null)));
        }
        return new Category("cat-vue-hierarchy", "COMPONENT TREE", clues);
    }

    private Category buildComponentCatalog(CodeGraph graph, AtomicInteger seq) {
        List<CodeNode> components = graph.nodesOfKind(CodeNode.NodeKind.COMPONENT).stream()
                .filter(c -> "vue".equals(c.language()))
                .toList();
        List<Clue> clues = new ArrayList<>();
        clues.add(clue(seq,
                "How many Vue SFC component nodes were extracted?",
                "What is " + components.size() + "?",
                "COMPONENT nodes with language=vue.", null));
        for (CodeNode component : components.stream().limit(7).toList()) {
            clues.add(clue(seq,
                    "This Vue component file is `" + component.qualifiedName() + "`"
                            + (component.signature() == null ? "" : (" (" + component.signature() + ")")) + ".",
                    "What is " + component.name() + "?",
                    component.snippet() == null ? component.signature() : truncate(component.snippet(), 160),
                    component.filePath()));
        }
        return new Category("cat-components", "COMPONENT CATALOG", clues);
    }

    private Category buildNpmDependencies(CodeGraph graph, AtomicInteger seq) {
        List<CodeNode> deps = graph.nodesOfKind(CodeNode.NodeKind.DEPENDENCY).stream()
                .filter(d -> "npm".equals(d.language()))
                .toList();
        List<Clue> clues = new ArrayList<>();
        clues.add(clue(seq,
                "npm dependency entries discovered in package.json files.",
                "What is " + deps.size() + "?",
                "DEPENDENCY nodes with language=npm.", null));
        deps.stream()
                .filter(d -> "vue".equals(d.name()) || d.name().contains("vite") || d.signature() != null)
                .limit(7)
                .forEach(dep -> clues.add(clue(seq,
                        "package.json lists `" + dep.qualifiedName() + "` (" + dep.signature() + ").",
                        "What is " + dep.name() + "?",
                        dep.snippet(),
                        dep.filePath())));
        return new Category("cat-npm", "PACKAGE.JSON POINTS", clues);
    }

    private Category buildScriptsAndRoutes(CodeGraph graph, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        List<CodeNode> scripts = graph.nodesOfKind(CodeNode.NodeKind.SCRIPT);
        List<CodeNode> routes = graph.nodesOfKind(CodeNode.NodeKind.ROUTE);
        clues.add(clue(seq,
                "How many npm scripts were indexed?",
                "What is " + scripts.size() + "?",
                "SCRIPT node count.", null));
        scripts.stream().limit(4).forEach(script -> clues.add(clue(seq,
                "Running `npm run " + script.name() + "` executes this command.",
                "What is " + script.snippet() + "?",
                script.signature(),
                script.filePath())));
        clues.add(clue(seq,
                "Router path entries discovered in the graph.",
                "What is " + routes.size() + "?",
                "ROUTE node count.", null));
        routes.stream().limit(3).forEach(route -> clues.add(clue(seq,
                "A route definition uses this path.",
                "What is " + route.name() + "?",
                route.signature(),
                route.filePath())));
        return new Category("cat-scripts", "SCRIPTS & ROUTES", clues);
    }

    private Clue clue(AtomicInteger seq, String prompt, String response, String explanation, String source) {
        return new Clue("c" + seq.getAndIncrement(), 0, prompt, response, explanation, source, false);
    }

    private Category normalizeCategory(Category category) {
        List<Clue> clues = new ArrayList<>();
        List<Clue> source = category.clues();
        for (int i = 0; i < Math.min(VALUES.length, source.size()); i++) {
            Clue c = source.get(i);
            clues.add(new Clue(
                    c.id(),
                    VALUES[i],
                    c.prompt(),
                    c.response(),
                    c.explanation(),
                    c.sourcePath(),
                    i == 3 && source.size() >= 4 // one daily double mid/high
            ));
        }
        return new Category(category.id(), category.title(), clues);
    }

    private Category buildNameThatType(CodeGraph graph, AtomicInteger seq) {
        List<CodeNode> types = new ArrayList<>();
        types.addAll(graph.nodesOfKind(CodeNode.NodeKind.CLASS));
        types.addAll(graph.nodesOfKind(CodeNode.NodeKind.INTERFACE));
        types = types.stream()
                .filter(n -> n.snippet() != null && !n.snippet().isBlank())
                .sorted(Comparator.comparing(CodeNode::qualifiedName))
                .limit(8)
                .toList();

        List<Clue> clues = new ArrayList<>();
        for (CodeNode type : types) {
            String kind = type.kind() == CodeNode.NodeKind.INTERFACE ? "interface" : "class";
            clues.add(new Clue(
                    "c" + seq.getAndIncrement(),
                    0,
                    "This " + kind + " lives in `" + type.filePath() + "` and begins like:\n\n```\n"
                            + truncate(type.snippet(), 220) + "\n```",
                    "What is " + type.name() + "?",
                    "Qualified name: " + type.qualifiedName(),
                    type.filePath(),
                    false
            ));
        }
        return new Category("cat-types", "NAME THAT TYPE", clues);
    }

    private Category buildMethodSignatures(CodeGraph graph, AtomicInteger seq) {
        List<CodeNode> methods = new ArrayList<>();
        methods.addAll(graph.nodesOfKind(CodeNode.NodeKind.METHOD));
        methods.addAll(graph.nodesOfKind(CodeNode.NodeKind.FUNCTION));
        methods = methods.stream()
                .filter(n -> n.signature() != null)
                .sorted(Comparator.comparingInt((CodeNode n) -> n.signature().length()).reversed())
                .limit(8)
                .toList();

        List<Clue> clues = new ArrayList<>();
        for (CodeNode method : methods) {
            clues.add(new Clue(
                    "c" + seq.getAndIncrement(),
                    0,
                    "In `" + method.filePath() + "`, this callable is declared as:\n`"
                            + method.signature() + "`",
                    "What is " + method.name() + "?",
                    method.snippet() != null ? truncate(method.snippet(), 180) : method.qualifiedName(),
                    method.filePath(),
                    false
            ));
        }
        return new Category("cat-methods", "CALLABLE OR NOT", clues);
    }

    private Category buildImportDependencies(CodeGraph graph, AtomicInteger seq) {
        Map<String, Long> importCounts = graph.nodesOfKind(CodeNode.NodeKind.IMPORT).stream()
                .collect(Collectors.groupingBy(CodeNode::qualifiedName, LinkedHashMap::new, Collectors.counting()));

        List<Map.Entry<String, Long>> ranked = importCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(8)
                .toList();

        List<Clue> clues = new ArrayList<>();
        for (Map.Entry<String, Long> entry : ranked) {
            long count = entry.getValue();
            clues.add(new Clue(
                    "c" + seq.getAndIncrement(),
                    0,
                    "This dependency appears in " + count + " import"
                            + (count == 1 ? "" : "s") + " across the ingested tree.",
                    "What is " + entry.getKey() + "?",
                    "Counted from IMPORT nodes in the code graph.",
                    null,
                    false
            ));
        }

        // Also ask which file imports the most
        Map<String, Long> importsByFile = graph.edgesOf(CodeEdge.Relation.IMPORTS).stream()
                .collect(Collectors.groupingBy(CodeEdge::fromId, Collectors.counting()));
        importsByFile.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .flatMap(e -> graph.findById(e.getKey()))
                .ifPresent(fileNode -> clues.add(0, new Clue(
                        "c" + seq.getAndIncrement(),
                        0,
                        "This source file declares the most import edges in the graph.",
                        "What is " + fileNode.qualifiedName() + "?",
                        "Import edge count winner among FILE nodes.",
                        fileNode.filePath(),
                        false
                )));

        return new Category("cat-imports", "DEPENDENCY DRAMA", clues);
    }

    private Category buildFileFacts(CodeGraph graph, AtomicInteger seq) {
        List<CodeNode> files = graph.nodesOfKind(CodeNode.NodeKind.FILE);
        List<Clue> clues = new ArrayList<>();

        if (!files.isEmpty()) {
            clues.add(new Clue(
                    "c" + seq.getAndIncrement(),
                    0,
                    "The ingested repository contains this many source files.",
                    "What is " + files.size() + "?",
                    "FILE nodes in the code graph.",
                    null,
                    false
            ));
        }

        files.stream()
                .collect(Collectors.groupingBy(CodeNode::language, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(e -> clues.add(new Clue(
                        "c" + seq.getAndIncrement(),
                        0,
                        "Most source files in this board are written in this language.",
                        "What is " + e.getKey() + "?",
                        e.getValue() + " files.",
                        null,
                        false
                )));

        for (CodeNode file : files.stream().limit(4).toList()) {
            long defined = graph.edgesFrom(file.id()).stream()
                    .filter(e -> e.relation() == CodeEdge.Relation.DEFINES)
                    .count();
            clues.add(new Clue(
                    "c" + seq.getAndIncrement(),
                    0,
                    "This file defines " + defined + " top-level type/callable symbols.",
                    "What is " + file.qualifiedName() + "?",
                    "Counted DEFINES edges from the FILE node.",
                    file.filePath(),
                    false
            ));
        }

        return new Category("cat-files", "FILE CABINET", clues);
    }

    private Category buildLanguageMix(CodeGraph graph, AtomicInteger seq) {
        Map<String, Long> byLang = graph.nodesOfKind(CodeNode.NodeKind.FILE).stream()
                .collect(Collectors.groupingBy(CodeNode::language, Collectors.counting()));
        List<Clue> clues = new ArrayList<>();

        byLang.forEach((lang, count) -> clues.add(new Clue(
                "c" + seq.getAndIncrement(),
                0,
                "How many `" + lang + "` source files were indexed?",
                "What is " + count + "?",
                "Language tag on FILE nodes.",
                null,
                false
        )));

        long packages = graph.nodesOfKind(CodeNode.NodeKind.PACKAGE).size();
        if (packages > 0) {
            clues.add(new Clue(
                    "c" + seq.getAndIncrement(),
                    0,
                    "Java package nodes discovered in the graph.",
                    "What is " + packages + "?",
                    "PACKAGE node count.",
                    null,
                    false
            ));
        }

        long interfaces = graph.nodesOfKind(CodeNode.NodeKind.INTERFACE).size();
        if (interfaces > 0) {
            clues.add(new Clue(
                    "c" + seq.getAndIncrement(),
                    0,
                    "Number of interface types found during ingestion.",
                    "What is " + interfaces + "?",
                    "INTERFACE node count.",
                    null,
                    false
            ));
        }

        return new Category("cat-lang", "POLYGLOT POINTS", clues);
    }

    private Category buildWhoCallsWhom(CodeGraph graph, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        var stats = graph.stats();

        clues.add(new Clue(
                "c" + seq.getAndIncrement(),
                0,
                "Total nodes currently stored in the in-memory code graph.",
                "What is " + stats.nodeCount() + "?",
                "Sum of all entity kinds.",
                null,
                false
        ));
        clues.add(new Clue(
                "c" + seq.getAndIncrement(),
                0,
                "Total relationship edges extracted during ingestion.",
                "What is " + stats.edgeCount() + "?",
                "Includes CONTAINS, DEFINES, IMPORTS, and related edges.",
                null,
                false
        ));

        graph.edgesOf(CodeEdge.Relation.EXTENDS).stream().findFirst().ifPresent(edge -> {
            String child = graph.findById(edge.fromId()).map(CodeNode::name).orElse("?");
            String parent = graph.findById(edge.toId()).map(CodeNode::name).orElse("?");
            clues.add(new Clue(
                    "c" + seq.getAndIncrement(),
                    0,
                    "This type extends or subclasses `" + parent + "`.",
                    "What is " + child + "?",
                    "From an EXTENDS edge in the code graph.",
                    graph.findById(edge.fromId()).map(CodeNode::filePath).orElse(null),
                    false
            ));
        });

        // Package containment questions
        Map<String, Long> typesByPackage = graph.edgesOf(CodeEdge.Relation.CONTAINS).stream()
                .collect(Collectors.groupingBy(CodeEdge::fromId, Collectors.counting()));
        typesByPackage.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .flatMap(e -> graph.findById(e.getKey()))
                .ifPresent(pkg -> clues.add(new Clue(
                        "c" + seq.getAndIncrement(),
                        0,
                        "This package contains the most type definitions.",
                        "What is " + pkg.name() + "?",
                        "CONTAINS edge winner among PACKAGE nodes.",
                        null,
                        false
                )));

        // Fallback structural clue from any method
        graph.nodesOfKind(CodeNode.NodeKind.METHOD).stream().findFirst().ifPresent(m ->
                clues.add(new Clue(
                        "c" + seq.getAndIncrement(),
                        0,
                        "A method discovered under `" + m.filePath() + "` goes by this name.",
                        "What is " + m.name() + "?",
                        m.signature(),
                        m.filePath(),
                        false
                )));

        return new Category("cat-graph", "GRAPH THEORY", clues);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\t', ' ');
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max).trim() + "…";
    }
}
