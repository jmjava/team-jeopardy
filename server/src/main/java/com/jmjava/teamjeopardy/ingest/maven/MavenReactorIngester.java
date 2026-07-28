package com.jmjava.teamjeopardy.ingest.maven;

import com.jmjava.teamjeopardy.graph.CodeEdge;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeNode;
import com.jmjava.teamjeopardy.ingest.maven.MavenPomParser.Dependency;
import com.jmjava.teamjeopardy.ingest.maven.MavenPomParser.ParsedModule;
import com.jmjava.teamjeopardy.ingest.maven.MavenPomParser.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Maven reactor walk + dependency projection, extracted from skgraph's
 * MavenReactorBuilder / RelaxedDependencyGraphBuilder ideas.
 */
@Component
public class MavenReactorIngester {

    private static final Logger log = LoggerFactory.getLogger(MavenReactorIngester.class);

    private final MavenPomParser parser = new MavenPomParser();

    public List<ParsedModule> ingestInto(CodeGraph graph, Path reactorRoot) throws Exception {
        Path rootPom = reactorRoot.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            throw new IllegalArgumentException("No pom.xml at " + reactorRoot);
        }

        Map<String, ParsedModule> discovered = new LinkedHashMap<>();
        ArrayDeque<Path> queue = new ArrayDeque<>();
        queue.add(rootPom);

        while (!queue.isEmpty()) {
            Path pom = queue.removeFirst().toAbsolutePath().normalize();
            if (discovered.containsKey(pom.toString())) {
                continue;
            }
            ParsedModule module = parser.parseFile(pom, reactorRoot);
            discovered.put(pom.toString(), module);
            for (String child : module.modules()) {
                Path childPom = pom.getParent().resolve(child).resolve("pom.xml");
                if (Files.isRegularFile(childPom)) {
                    queue.add(childPom);
                }
            }
        }

        try (Stream<Path> walk = Files.walk(reactorRoot)) {
            walk.filter(p -> Files.isRegularFile(p) && "pom.xml".equals(p.getFileName().toString()))
                    .forEach(pom -> {
                        String abs = pom.toAbsolutePath().normalize().toString();
                        if (!discovered.containsKey(abs)) {
                            try {
                                discovered.put(abs, parser.parseFile(pom, reactorRoot));
                            } catch (Exception e) {
                                log.warn("Failed to parse orphan POM {}: {}", pom, e.getMessage());
                            }
                        }
                    });
        }

        List<ParsedModule> modules = new ArrayList<>(discovered.values());
        Map<String, ParsedModule> byGa = new LinkedHashMap<>();
        for (ParsedModule module : modules) {
            byGa.put(module.coordinate().ga(), module);
            projectModule(graph, module);
        }

        for (ParsedModule module : modules) {
            projectDependencies(graph, module, byGa);
        }

        if (graph.getProjectName() == null) {
            graph.setProjectName(modules.getFirst().coordinate().artifactId());
        }
        log.info("Maven reactor ingest: {} modules from {}", modules.size(), reactorRoot);
        return modules;
    }

    private void projectModule(CodeGraph graph, ParsedModule module) {
        String moduleId = module.moduleId();
        graph.addNode(new CodeNode(
                moduleId,
                CodeNode.NodeKind.MODULE,
                module.coordinate().artifactId(),
                module.coordinate().ga(),
                "maven",
                module.relativePath(),
                module.packaging() + " " + module.coordinate().ga(),
                "version=" + module.coordinate().version()
        ));

        String pomFileId = "file:" + module.relativePath();
        graph.addNode(new CodeNode(
                pomFileId,
                CodeNode.NodeKind.FILE,
                "pom.xml",
                module.relativePath(),
                "xml",
                module.relativePath(),
                module.coordinate().ga(),
                null
        ));
        graph.addEdge(new CodeEdge(pomFileId, moduleId, CodeEdge.Relation.DEFINES));

        for (Plugin plugin : module.plugins()) {
            String pluginId = "plugin:" + module.relativePath() + ":" + plugin.ga();
            graph.addNode(new CodeNode(
                    pluginId,
                    CodeNode.NodeKind.PLUGIN,
                    plugin.artifactId(),
                    plugin.ga(),
                    "maven",
                    module.relativePath(),
                    plugin.ga() + (plugin.version() == null ? "" : ":" + plugin.version()),
                    null
            ));
            graph.addEdge(new CodeEdge(moduleId, pluginId, CodeEdge.Relation.DEFINES));
        }
    }

    private void projectDependencies(CodeGraph graph, ParsedModule module, Map<String, ParsedModule> byGa) {
        for (Dependency dep : module.dependencies()) {
            String depId = "dep:maven:" + module.coordinate().ga() + "->" + dep.coordinate().ga();
            String version = dep.coordinate().version() == null ? "?" : dep.coordinate().version();
            graph.addNode(new CodeNode(
                    depId,
                    CodeNode.NodeKind.DEPENDENCY,
                    dep.coordinate().artifactId(),
                    dep.coordinate().ga() + "@" + version,
                    "maven",
                    module.relativePath(),
                    dep.coordinate().scope() == null ? "compile" : dep.coordinate().scope(),
                    version
            ));
            graph.addEdge(new CodeEdge(module.moduleId(), depId, CodeEdge.Relation.IMPORTS));

            ParsedModule provider = byGa.get(dep.coordinate().ga());
            if (provider != null) {
                graph.addEdge(new CodeEdge(module.moduleId(), provider.moduleId(), CodeEdge.Relation.IMPORTS));
            }
        }
    }
}
