package com.jmjava.teamjeopardy.ingest.gradle;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lightweight Gradle multi-project ingest (settings + build scripts).
 * Mirrors skgraph's Maven-reactor style facts for Jeopardy boards without invoking Gradle.
 */
@Component
public class GradleProjectIngester {

    private static final Logger log = LoggerFactory.getLogger(GradleProjectIngester.class);

    private static final Pattern INCLUDE = Pattern.compile(
            "include\\s*\\(?\\s*['\"]([^'\"]+)['\"]\\s*\\)?",
            Pattern.MULTILINE);
    private static final Pattern ROOT_NAME = Pattern.compile(
            "rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern PLUGIN_ID = Pattern.compile(
            "id\\s*\\(?\\s*['\"]([^'\"]+)['\"]\\s*\\)?");
    private static final Pattern PLUGIN_GROOVY = Pattern.compile(
            "apply\\s+plugin:\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern DEPENDENCY = Pattern.compile(
            "(implementation|api|compileOnly|runtimeOnly|testImplementation|classpath)\\s*\\(?\\s*['\"]([^'\"]+)['\"]\\s*\\)?");
    private static final Pattern PROJECT_DEP = Pattern.compile(
            "(implementation|api|compileOnly|runtimeOnly|testImplementation)\\s*\\(?\\s*project\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)",
            Pattern.MULTILINE);

    public List<String> ingestInto(CodeGraph graph, Path root) throws IOException {
        Path settings = firstExisting(root, "settings.gradle.kts", "settings.gradle");
        Path rootBuild = firstExisting(root, "build.gradle.kts", "build.gradle");

        Set<String> modulePaths = new LinkedHashSet<>();
        modulePaths.add(":");

        if (settings != null) {
            String settingsText = Files.readString(settings, StandardCharsets.UTF_8);
            String relative = root.relativize(settings).toString().replace('\\', '/');
            addFile(graph, relative, "gradle", settingsText);
            Matcher rootName = ROOT_NAME.matcher(settingsText);
            if (rootName.find()) {
                graph.setProjectName(rootName.group(1));
            }
            Matcher include = INCLUDE.matcher(settingsText);
            while (include.find()) {
                String raw = include.group(1).trim();
                if (!raw.startsWith(":")) {
                    raw = ":" + raw.replace('/', ':');
                }
                modulePaths.add(raw);
            }
        }

        if (rootBuild != null) {
            parseBuildScript(graph, root, rootBuild, ":");
        }

        for (String modulePath : modulePaths) {
            if (":".equals(modulePath)) {
                projectModule(graph, ":", graph.getProjectName() != null ? graph.getProjectName() : root.getFileName().toString(),
                        settings != null ? root.relativize(settings).toString().replace('\\', '/') : "settings.gradle");
                continue;
            }
            String dir = modulePath.substring(1).replace(':', '/');
            Path moduleDir = root.resolve(dir);
            Path build = firstExisting(moduleDir, "build.gradle.kts", "build.gradle");
            projectModule(graph, modulePath, dir, build == null ? dir : root.relativize(build).toString().replace('\\', '/'));
            if (build != null) {
                parseBuildScript(graph, root, build, modulePath);
            }
        }

        // Catch nested build scripts not listed in settings (broken/partial trees)
        try (Stream<Path> walk = Files.walk(root, 6)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return "build.gradle".equals(name) || "build.gradle.kts".equals(name);
                    })
                    .forEach(build -> {
                        try {
                            String relative = root.relativize(build).toString().replace('\\', '/');
                            String modulePath = relative.contains("/")
                                    ? ":" + relative.substring(0, relative.lastIndexOf('/')).replace('/', ':')
                                    : ":";
                            if (!modulePaths.contains(modulePath) || !":".equals(modulePath)) {
                                parseBuildScript(graph, root, build, modulePath);
                                modulePaths.add(modulePath);
                            }
                        } catch (IOException e) {
                            log.warn("Failed Gradle parse {}: {}", build, e.getMessage());
                        }
                    });
        }

        if (graph.getProjectName() == null) {
            graph.setProjectName(root.getFileName().toString());
        }
        log.info("Gradle ingest: {} modules from {}", modulePaths.size(), root);
        return new ArrayList<>(modulePaths);
    }

    private void parseBuildScript(CodeGraph graph, Path root, Path build, String modulePath) throws IOException {
        String relative = root.relativize(build).toString().replace('\\', '/');
        String content = Files.readString(build, StandardCharsets.UTF_8);
        String fileId = addFile(graph, relative, "gradle", content);
        String moduleId = moduleId(modulePath);
        graph.addEdge(new CodeEdge(fileId, moduleId, CodeEdge.Relation.DEFINES));

        Matcher plugins = PLUGIN_ID.matcher(content);
        while (plugins.find()) {
            addPlugin(graph, moduleId, relative, plugins.group(1));
        }
        Matcher groovyPlugins = PLUGIN_GROOVY.matcher(content);
        while (groovyPlugins.find()) {
            addPlugin(graph, moduleId, relative, groovyPlugins.group(1));
        }

        Matcher deps = DEPENDENCY.matcher(content);
        while (deps.find()) {
            String config = deps.group(1);
            String notation = deps.group(2);
            String depId = "dep:gradle:" + modulePath + "->" + notation;
            graph.addNode(new CodeNode(
                    depId,
                    CodeNode.NodeKind.DEPENDENCY,
                    artifactFromNotation(notation),
                    notation,
                    "gradle",
                    relative,
                    config,
                    notation
            ));
            graph.addEdge(new CodeEdge(moduleId, depId, CodeEdge.Relation.IMPORTS));
        }

        Matcher projectDeps = PROJECT_DEP.matcher(content);
        while (projectDeps.find()) {
            String target = projectDeps.group(2);
            if (!target.startsWith(":")) {
                target = ":" + target;
            }
            graph.addEdge(new CodeEdge(moduleId, moduleId(target), CodeEdge.Relation.IMPORTS));
        }
    }

    private void projectModule(CodeGraph graph, String modulePath, String name, String sourcePath) {
        graph.addNode(new CodeNode(
                moduleId(modulePath),
                CodeNode.NodeKind.MODULE,
                name,
                modulePath,
                "gradle",
                sourcePath,
                "gradle module " + modulePath,
                null
        ));
    }

    private void addPlugin(CodeGraph graph, String moduleId, String relative, String pluginId) {
        String id = "plugin:gradle:" + moduleId + ":" + pluginId;
        graph.addNode(new CodeNode(
                id,
                CodeNode.NodeKind.PLUGIN,
                pluginId,
                pluginId,
                "gradle",
                relative,
                "id '" + pluginId + "'",
                null
        ));
        graph.addEdge(new CodeEdge(moduleId, id, CodeEdge.Relation.DEFINES));
    }

    private String addFile(CodeGraph graph, String relative, String language, String content) {
        String fileId = "file:" + relative;
        graph.addNode(new CodeNode(
                fileId,
                CodeNode.NodeKind.FILE,
                Path.of(relative).getFileName().toString(),
                relative,
                language,
                relative,
                null,
                content.lines().limit(6).reduce((a, b) -> a + "\n" + b).orElse("")
        ));
        return fileId;
    }

    private static String moduleId(String modulePath) {
        return "module:gradle:" + modulePath;
    }

    private static String artifactFromNotation(String notation) {
        String[] parts = notation.split(":");
        return parts.length >= 2 ? parts[1] : notation;
    }

    private static Path firstExisting(Path dir, String... names) {
        for (String name : names) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
