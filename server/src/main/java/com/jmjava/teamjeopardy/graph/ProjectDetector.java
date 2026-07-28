package com.jmjava.teamjeopardy.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

@Component
public class ProjectDetector {

    private final ObjectMapper mapper = new ObjectMapper();

    public ProjectKind detect(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("Not a directory: " + absolute);
        }

        boolean hasPom = Files.exists(absolute.resolve("pom.xml"));
        boolean hasGradle = hasGradleBuild(absolute);
        boolean hasPackageJson = Files.exists(absolute.resolve("package.json"));
        boolean hasPyProject = Files.exists(absolute.resolve("pyproject.toml"))
                || Files.exists(absolute.resolve("requirements.txt"))
                || Files.exists(absolute.resolve("setup.py"));

        // Root Vue/npm apps win when package.json clearly identifies them.
        if (hasPackageJson && looksLikeVue(absolute)) {
            return ProjectKind.VUE;
        }

        // Gradle multi-module (Maven-reactor analogue) before plain Maven when both exist.
        // Common for Gradle migrations that still keep a pom, or Gradle + Maven Wrapper hybrids.
        if (hasGradle) {
            return ProjectKind.GRADLE;
        }
        if (hasPom) {
            return ProjectKind.MAVEN;
        }
        if (hasPackageJson) {
            return ProjectKind.NPM;
        }
        if (hasPyProject) {
            return ProjectKind.PYTHON;
        }
        if (hasVueFiles(absolute)) {
            return ProjectKind.VUE;
        }
        return ProjectKind.GENERIC;
    }

    public boolean hasGradleBuild(Path root) {
        return Files.exists(root.resolve("settings.gradle"))
                || Files.exists(root.resolve("settings.gradle.kts"))
                || Files.exists(root.resolve("build.gradle"))
                || Files.exists(root.resolve("build.gradle.kts"));
    }

    private boolean looksLikeVue(Path root) throws IOException {
        Path packageJson = root.resolve("package.json");
        if (Files.exists(packageJson)) {
            JsonNode json = mapper.readTree(Files.readString(packageJson));
            if (dependsOn(json, "vue") || dependsOn(json, "@vitejs/plugin-vue") || dependsOn(json, "nuxt")) {
                return true;
            }
            String name = text(json, "name").toLowerCase(Locale.ROOT);
            if (name.contains("vue")) {
                return true;
            }
        }
        return Files.exists(root.resolve("vite.config.js"))
                || Files.exists(root.resolve("vite.config.ts"))
                || Files.exists(root.resolve("vue.config.js"))
                || hasVueFiles(root);
    }

    private boolean dependsOn(JsonNode root, String packageName) {
        return hasDep(root.path("dependencies"), packageName)
                || hasDep(root.path("devDependencies"), packageName)
                || hasDep(root.path("peerDependencies"), packageName);
    }

    private boolean hasDep(JsonNode deps, String packageName) {
        return deps != null && deps.isObject() && deps.has(packageName);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private boolean hasVueFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root, 6)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".vue"))
                    .filter(p -> {
                        for (Path part : root.relativize(p)) {
                            String name = part.toString();
                            if ("node_modules".equals(name) || "dist".equals(name) || ".git".equals(name)) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .findFirst()
                    .isPresent();
        }
    }
}
