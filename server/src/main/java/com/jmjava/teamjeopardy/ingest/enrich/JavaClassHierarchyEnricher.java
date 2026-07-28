package com.jmjava.teamjeopardy.ingest.enrich;

import com.jmjava.teamjeopardy.graph.CodeEdge;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds Java class/interface hierarchies: {@code EXTENDS} / {@code IMPLEMENTS}
 * edges resolved against types already in the graph (inspired by skgraph Java AST
 * relationship projection).
 */
@Component
public class JavaClassHierarchyEnricher implements GraphEnricher {

    private static final Logger log = LoggerFactory.getLogger(JavaClassHierarchyEnricher.class);

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern TYPE_DECL = Pattern.compile(
            "(?:public\\s+|protected\\s+|private\\s+)?(?:abstract\\s+|final\\s+|sealed\\s+)?"
                    + "(class|interface|enum|record)\\s+(\\w+)"
                    // do not let 'extends' consume the 'implements' keyword
                    + "(?:\\s+extends\\s+([\\w.]+(?:\\s*,\\s*[\\w.]+)*))?"
                    + "(?:\\s+implements\\s+([\\w.]+(?:\\s*,\\s*[\\w.]+)*))?",
            Pattern.MULTILINE);

    @Override
    public String name() {
        return "java-class-hierarchy";
    }

    @Override
    public int enrich(CodeGraph graph, Path root) {
        Map<String, String> byQualified = new LinkedHashMap<>();
        Map<String, List<String>> bySimple = new HashMap<>();
        for (CodeNode node : graph.nodes()) {
            if (node.kind() != CodeNode.NodeKind.CLASS && node.kind() != CodeNode.NodeKind.INTERFACE) {
                continue;
            }
            if (!"java".equals(node.language())) {
                continue;
            }
            byQualified.put(node.qualifiedName(), node.id());
            bySimple.computeIfAbsent(node.name(), k -> new ArrayList<>()).add(node.id());
        }
        if (byQualified.isEmpty()) {
            return 0;
        }

        int added = 0;
        Set<String> seenEdges = new HashSet<>();
        for (CodeEdge existing : graph.edges()) {
            if (existing.relation() == CodeEdge.Relation.EXTENDS
                    || existing.relation() == CodeEdge.Relation.IMPLEMENTS) {
                seenEdges.add(edgeKey(existing.fromId(), existing.toId(), existing.relation()));
            }
        }

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> javaFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !isSkipped(root, p))
                    .toList();

            for (Path file : javaFiles) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String packageName = findPackage(content);
                List<String> imports = findImports(content);
                Matcher decl = TYPE_DECL.matcher(content);
                while (decl.find()) {
                    String kind = decl.group(1);
                    String typeName = decl.group(2);
                    String qname = packageName == null || packageName.isBlank()
                            ? typeName
                            : packageName + "." + typeName;
                    String typeId = byQualified.getOrDefault(qname, "type:" + qname);
                    ensureTypeNode(graph, typeId, typeName, qname, kind, root.relativize(file).toString());

                    String extendsClause = decl.group(3);
                    if (extendsClause != null) {
                        for (String parent : splitTypes(extendsClause)) {
                            Optional<String> parentId = resolve(parent, packageName, imports, byQualified, bySimple);
                            if (parentId.isPresent()) {
                                CodeEdge.Relation relation = "interface".equals(kind)
                                        ? CodeEdge.Relation.EXTENDS
                                        : CodeEdge.Relation.EXTENDS;
                                // class extends class; interface extends interface(s)
                                if ("class".equals(kind) || "interface".equals(kind) || "enum".equals(kind)) {
                                    added += addEdge(graph, seenEdges, typeId, parentId.get(), CodeEdge.Relation.EXTENDS);
                                }
                            } else {
                                String stubId = stubExternal(graph, parent, byQualified, bySimple);
                                added += addEdge(graph, seenEdges, typeId, stubId, CodeEdge.Relation.EXTENDS);
                            }
                        }
                    }

                    String implementsClause = decl.group(4);
                    if (implementsClause != null && !"interface".equals(kind)) {
                        for (String iface : splitTypes(implementsClause)) {
                            Optional<String> ifaceId = resolve(iface, packageName, imports, byQualified, bySimple);
                            if (ifaceId.isPresent()) {
                                added += addEdge(graph, seenEdges, typeId, ifaceId.get(), CodeEdge.Relation.IMPLEMENTS);
                            } else {
                                String stubId = stubExternal(graph, iface, byQualified, bySimple);
                                added += addEdge(graph, seenEdges, typeId, stubId, CodeEdge.Relation.IMPLEMENTS);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Java hierarchy enrich failed: {}", e.getMessage());
        }

        // Package nesting: com.example.api BELONGS_TO com.example
        added += enrichPackageTree(graph, seenEdges);

        log.info("Java hierarchy enricher added {} edges ({} types indexed)", added, byQualified.size());
        return added;
    }

    private int enrichPackageTree(CodeGraph graph, Set<String> seenEdges) {
        int added = 0;
        List<CodeNode> packages = graph.nodesOfKind(CodeNode.NodeKind.PACKAGE);
        Map<String, String> byName = new HashMap<>();
        for (CodeNode pkg : packages) {
            byName.put(pkg.name(), pkg.id());
        }
        for (CodeNode pkg : packages) {
            String name = pkg.name();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String parentName = name.substring(0, dot);
            String parentId = byName.computeIfAbsent(parentName, pn -> {
                String id = "package:" + pn;
                graph.addNode(new CodeNode(
                        id, CodeNode.NodeKind.PACKAGE, pn, pn, "java", null, null, null
                ));
                return id;
            });
            added += addEdge(graph, seenEdges, pkg.id(), parentId, CodeEdge.Relation.BELONGS_TO);
            added += addEdge(graph, seenEdges, parentId, pkg.id(), CodeEdge.Relation.CONTAINS);
        }
        return added;
    }

    private void ensureTypeNode(
            CodeGraph graph,
            String typeId,
            String typeName,
            String qname,
            String kind,
            String relative
    ) {
        if (graph.findById(typeId).isPresent()) {
            return;
        }
        CodeNode.NodeKind nodeKind = "interface".equals(kind)
                ? CodeNode.NodeKind.INTERFACE
                : CodeNode.NodeKind.CLASS;
        graph.addNode(new CodeNode(
                typeId, nodeKind, typeName, qname, "java",
                relative.replace('\\', '/'), kind + " " + typeName, null
        ));
    }

    private String stubExternal(
            CodeGraph graph,
            String typeName,
            Map<String, String> byQualified,
            Map<String, List<String>> bySimple
    ) {
        String simple = simpleName(typeName);
        String id = "type-ext:" + typeName;
        if (byQualified.containsKey(typeName)) {
            return byQualified.get(typeName);
        }
        graph.addNode(new CodeNode(
                id, CodeNode.NodeKind.CLASS, simple, typeName, "java", null,
                "external " + typeName, null
        ));
        byQualified.put(typeName, id);
        bySimple.computeIfAbsent(simple, k -> new ArrayList<>()).add(id);
        return id;
    }

    private Optional<String> resolve(
            String rawName,
            String packageName,
            List<String> imports,
            Map<String, String> byQualified,
            Map<String, List<String>> bySimple
    ) {
        String name = rawName.trim();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        // strip generics
        int generic = name.indexOf('<');
        if (generic >= 0) {
            name = name.substring(0, generic).trim();
        }

        if (byQualified.containsKey(name)) {
            return Optional.of(byQualified.get(name));
        }
        if (packageName != null && !packageName.isBlank()) {
            String samePkg = packageName + "." + name;
            if (byQualified.containsKey(samePkg)) {
                return Optional.of(byQualified.get(samePkg));
            }
        }
        for (String imported : imports) {
            if (imported.endsWith("." + name) || imported.equals(name)) {
                if (byQualified.containsKey(imported)) {
                    return Optional.of(byQualified.get(imported));
                }
            }
            if (imported.endsWith(".*")) {
                String prefix = imported.substring(0, imported.length() - 1);
                String candidate = prefix + name;
                if (byQualified.containsKey(candidate)) {
                    return Optional.of(byQualified.get(candidate));
                }
            }
        }
        List<String> simpleHits = bySimple.getOrDefault(simpleName(name), List.of());
        if (simpleHits.size() == 1) {
            return Optional.of(simpleHits.getFirst());
        }
        return Optional.empty();
    }

    private int addEdge(
            CodeGraph graph,
            Set<String> seen,
            String from,
            String to,
            CodeEdge.Relation relation
    ) {
        String key = edgeKey(from, to, relation);
        if (!seen.add(key) || from.equals(to)) {
            return 0;
        }
        graph.addEdge(new CodeEdge(from, to, relation));
        return 1;
    }

    private static String edgeKey(String from, String to, CodeEdge.Relation relation) {
        return relation + "|" + from + "->" + to;
    }

    private static List<String> splitTypes(String clause) {
        List<String> out = new ArrayList<>();
        for (String part : clause.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String findPackage(String content) {
        Matcher matcher = PACKAGE.matcher(content);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<String> findImports(String content) {
        List<String> imports = new ArrayList<>();
        Matcher matcher = IMPORT.matcher(content);
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
        return imports;
    }

    private static String simpleName(String qualified) {
        int idx = qualified.lastIndexOf('.');
        return idx >= 0 ? qualified.substring(idx + 1) : qualified;
    }

    private static boolean isSkipped(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (List.of(".git", "node_modules", "target", "build", ".gradle").contains(name)) {
                return true;
            }
        }
        return false;
    }
}
