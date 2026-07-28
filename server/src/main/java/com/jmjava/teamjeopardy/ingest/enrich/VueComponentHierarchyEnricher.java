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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Resolves Vue SFC composition hierarchies: parent components {@code USES} child
 * components found in {@code <template>}, matching by PascalCase name / file name
 * / import path — the Vue analogue of Java class hierarchy enrichment.
 */
@Component
public class VueComponentHierarchyEnricher implements GraphEnricher {

    private static final Logger log = LoggerFactory.getLogger(VueComponentHierarchyEnricher.class);

    private static final Pattern VUE_TEMPLATE = Pattern.compile(
            "<template\\b[^>]*>([\\s\\S]*?)</template>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VUE_COMPONENT_TAG = Pattern.compile("<([A-Z][\\w-]*)\\b");
    private static final Pattern VUE_IMPORT = Pattern.compile(
            "import\\s+([A-Za-z_][\\w]*)\\s+from\\s+['\"]([^'\"]+\\.vue)['\"]");
    private static final Set<String> BUILTINS = Set.of(
            "Template", "Script", "Style", "Component", "Transition", "TransitionGroup",
            "KeepAlive", "Suspense", "Teleport", "RouterView", "RouterLink"
    );

    @Override
    public String name() {
        return "vue-component-hierarchy";
    }

    @Override
    public int enrich(CodeGraph graph, Path root) {
        // Index real SFCs: name -> component id, also kebab-case and path
        Map<String, String> byName = new LinkedHashMap<>();
        Map<String, String> byPath = new HashMap<>();
        for (CodeNode node : graph.nodesOfKind(CodeNode.NodeKind.COMPONENT)) {
            if (!"vue".equals(node.language())) {
                continue;
            }
            // Skip unresolved stub refs from the first pass
            if (node.id().startsWith("component-ref:")) {
                continue;
            }
            byName.put(node.name(), node.id());
            byName.put(toKebab(node.name()), node.id());
            if (node.filePath() != null) {
                byPath.put(normalizePath(node.filePath()), node.id());
                byPath.put(Path.of(node.filePath()).getFileName().toString(), node.id());
            }
        }
        if (byName.isEmpty()) {
            return 0;
        }

        int added = 0;
        Set<String> seen = new HashSet<>();
        for (CodeEdge edge : graph.edges()) {
            if (edge.relation() == CodeEdge.Relation.USES || edge.relation() == CodeEdge.Relation.CALLS) {
                seen.add(edgeKey(edge.fromId(), edge.toId(), edge.relation()));
            }
        }

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> vueFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".vue"))
                    .filter(p -> !isSkipped(root, p))
                    .toList();

            for (Path file : vueFiles) {
                String relative = normalizePath(root.relativize(file).toString());
                String parentId = byPath.getOrDefault(relative, "component:" + relative);
                if (graph.findById(parentId).isEmpty()) {
                    continue;
                }

                String content = Files.readString(file, StandardCharsets.UTF_8);
                Map<String, String> localImports = resolveLocalImports(relative, content, byPath, byName);

                Matcher template = VUE_TEMPLATE.matcher(content);
                if (!template.find()) {
                    continue;
                }
                String body = template.group(1);
                Matcher tags = VUE_COMPONENT_TAG.matcher(body);
                Set<String> used = new HashSet<>();
                while (tags.find()) {
                    String tag = tags.group(1);
                    if (BUILTINS.contains(tag) || !used.add(tag)) {
                        continue;
                    }
                    String childId = localImports.get(tag);
                    if (childId == null) {
                        childId = byName.get(tag);
                    }
                    if (childId == null) {
                        childId = byName.get(toKebab(tag));
                    }
                    if (childId == null) {
                        // keep a stub so hierarchy questions can still mention the tag
                        childId = "component-ref:" + relative + "#" + tag;
                        if (graph.findById(childId).isEmpty()) {
                            graph.addNode(new CodeNode(
                                    childId, CodeNode.NodeKind.COMPONENT, tag, tag, "vue",
                                    relative, "<" + tag + "> (unresolved)", null
                            ));
                        }
                    }
                    added += addEdge(graph, seen, parentId, childId, CodeEdge.Relation.USES);
                    // Also keep CALLS for compatibility with earlier board categories
                    added += addEdge(graph, seen, parentId, childId, CodeEdge.Relation.CALLS);
                }
            }
        } catch (IOException e) {
            log.warn("Vue hierarchy enrich failed: {}", e.getMessage());
        }

        // Directory nesting as soft CONTAINS (components/ folder under src/)
        added += enrichDirectoryNesting(graph, byPath, seen);

        log.info("Vue hierarchy enricher added {} edges ({} components indexed)", added, byName.size() / 2);
        return added;
    }

    private Map<String, String> resolveLocalImports(
            String relative,
            String content,
            Map<String, String> byPath,
            Map<String, String> byName
    ) {
        Map<String, String> local = new HashMap<>();
        Matcher imports = VUE_IMPORT.matcher(content);
        Path parentDir = Path.of(relative).getParent();
        while (imports.find()) {
            String localName = imports.group(1);
            String importPath = imports.group(2);
            Path resolved = parentDir == null
                    ? Path.of(importPath)
                    : parentDir.resolve(importPath).normalize();
            String normalized = normalizePath(resolved.toString());
            String target = byPath.get(normalized);
            if (target == null) {
                target = byPath.get(Path.of(normalized).getFileName().toString());
            }
            if (target == null) {
                target = byName.get(localName);
            }
            if (target != null) {
                local.put(localName, target);
            }
        }
        return local;
    }

    private int enrichDirectoryNesting(CodeGraph graph, Map<String, String> byPath, Set<String> seen) {
        int added = 0;
        for (Map.Entry<String, String> entry : byPath.entrySet()) {
            String path = entry.getKey();
            if (!path.contains("/")) {
                continue;
            }
            Path parentPath = Path.of(path).getParent();
            if (parentPath == null) {
                continue;
            }
            // If a sibling/parent index.vue or folder-named component exists, link CONTAINS
            String folder = parentPath.getFileName().toString();
            String indexCandidate = normalizePath(parentPath.resolve("index.vue").toString());
            String folderCandidate = normalizePath(parentPath.resolve(folder + ".vue").toString());
            for (String candidate : List.of(indexCandidate, folderCandidate)) {
                String parentId = byPath.get(candidate);
                if (parentId != null && !parentId.equals(entry.getValue())) {
                    added += addEdge(graph, seen, parentId, entry.getValue(), CodeEdge.Relation.CONTAINS);
                }
            }
        }
        return added;
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

    private static String toKebab(String pascal) {
        return pascal
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    private static boolean isSkipped(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (List.of(".git", "node_modules", "dist", ".nuxt", ".output").contains(name)) {
                return true;
            }
        }
        return false;
    }
}
