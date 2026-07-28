package com.jmjava.teamjeopardy.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmjava.teamjeopardy.ingest.enrich.GraphEnricher;
import com.jmjava.teamjeopardy.ingest.enrich.JavaClassHierarchyEnricher;
import com.jmjava.teamjeopardy.ingest.enrich.VueComponentHierarchyEnricher;
import com.jmjava.teamjeopardy.ingest.gradle.GradleProjectIngester;
import com.jmjava.teamjeopardy.ingest.maven.MavenReactorIngester;
import com.jmjava.teamjeopardy.ingest.osgi.OsgiManifestParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Walks a source tree and builds a code graph using language-aware heuristics.
 * Maven reactor / OSGi pieces are extracted from jmjava/skgraph; Gradle and Vue
 * extend the same in-repo model so no private dependency is required.
 */
@Service
public class CodeGraphIngester {

    private static final Logger log = LoggerFactory.getLogger(CodeGraphIngester.class);

    private static final List<String> SKIP_DIRS = List.of(
            ".git", "node_modules", "target", "build", "dist", ".idea", ".vscode",
            "__pycache__", ".nuxt", ".output", ".gradle"
    );

    private static final Pattern JAVA_PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern JAVA_IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern JAVA_TYPE = Pattern.compile(
            "(?:public\\s+|protected\\s+|private\\s+)?(?:abstract\\s+|final\\s+)?(class|interface|enum|record)\\s+(\\w+)",
            Pattern.MULTILINE);
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "(?:public|protected|private)\\s+(?:static\\s+)?(?:final\\s+)?[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\(([^)]*)\\)",
            Pattern.MULTILINE);
    private static final Pattern JS_FUNCTION = Pattern.compile(
            "(?:(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)|(?:(?:export\\s+)?(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|\\w+)\\s*=>))",
            Pattern.MULTILINE);
    private static final Pattern JS_IMPORT = Pattern.compile(
            "import\\s+(?:.+?\\s+from\\s+)?['\"]([^'\"]+)['\"]",
            Pattern.MULTILINE);
    private static final Pattern JS_CLASS = Pattern.compile("(?:export\\s+)?class\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern JS_EXPORT_DEFAULT = Pattern.compile(
            "export\\s+default\\s+(?:defineComponent\\s*\\(|\\{)",
            Pattern.MULTILINE);
    private static final Pattern VUE_SCRIPT = Pattern.compile(
            "<script\\b([^>]*)>([\\s\\S]*?)</script>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VUE_TEMPLATE = Pattern.compile(
            "<template\\b([^>]*)>([\\s\\S]*?)</template>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VUE_STYLE = Pattern.compile(
            "<style\\b([^>]*)>([\\s\\S]*?)</style>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VUE_COMPONENT_TAG = Pattern.compile(
            "<([A-Z][\\w-]*)\\b",
            Pattern.MULTILINE);
    private static final Pattern VUE_PROPS = Pattern.compile(
            "(?:defineProps\\s*\\(|props\\s*:)\\s*(\\{[\\s\\S]*?\\}|\\[[\\s\\S]*?\\])",
            Pattern.MULTILINE);
    private static final Pattern VUE_EMITS = Pattern.compile(
            "(?:defineEmits\\s*\\(|emits\\s*:)\\s*(\\[[\\s\\S]*?\\]|\\{[\\s\\S]*?\\})",
            Pattern.MULTILINE);
    private static final Pattern ROUTE_PATH = Pattern.compile(
            "path\\s*:\\s*['\"]([^'\"]+)['\"]",
            Pattern.MULTILINE);
    private static final Pattern PY_DEF = Pattern.compile("^\\s*(?:async\\s+)?def\\s+(\\w+)\\s*\\(([^)]*)\\)", Pattern.MULTILINE);
    private static final Pattern PY_CLASS = Pattern.compile("^\\s*class\\s+(\\w+)\\s*(?:\\(([^)]*)\\))?:", Pattern.MULTILINE);
    private static final Pattern PY_IMPORT = Pattern.compile("^(?:from\\s+([\\w.]+)\\s+import\\s+.+|import\\s+([\\w.,\\s]+))", Pattern.MULTILINE);

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectDetector projectDetector;
    private final MavenReactorIngester mavenReactorIngester;
    private final GradleProjectIngester gradleProjectIngester;
    private final OsgiManifestParser osgiManifestParser;
    private final List<GraphEnricher> enrichers;

    public CodeGraphIngester(
            ProjectDetector projectDetector,
            MavenReactorIngester mavenReactorIngester,
            GradleProjectIngester gradleProjectIngester,
            OsgiManifestParser osgiManifestParser,
            JavaClassHierarchyEnricher javaClassHierarchyEnricher,
            VueComponentHierarchyEnricher vueComponentHierarchyEnricher
    ) {
        this.projectDetector = projectDetector;
        this.mavenReactorIngester = mavenReactorIngester;
        this.gradleProjectIngester = gradleProjectIngester;
        this.osgiManifestParser = osgiManifestParser;
        this.enrichers = List.of(javaClassHierarchyEnricher, vueComponentHierarchyEnricher);
    }

    public CodeGraph ingest(Path root) throws IOException {
        return ingest(root, null);
    }

    public CodeGraph ingest(Path root, ProjectKind forcedKind) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("Path is not a directory: " + absolute);
        }

        ProjectKind kind = forcedKind != null ? forcedKind : projectDetector.detect(absolute);
        CodeGraph graph = new CodeGraph(absolute.toString());
        graph.setProjectKind(kind);

        try {
            // Maven / Gradle are optional adapters — skipped when those markers are absent.
            if (kind == ProjectKind.MAVEN || Files.exists(absolute.resolve("pom.xml"))) {
                mavenReactorIngester.ingestInto(graph, absolute);
            }
            if (kind == ProjectKind.GRADLE || projectDetector.hasGradleBuild(absolute)) {
                gradleProjectIngester.ingestInto(graph, absolute);
            }
            // OSGi is best-effort and never required. Only scan when bundle markers exist.
            if (osgiManifestParser.hasOsgiMarkers(absolute)) {
                osgiManifestParser.ingestInto(graph, absolute);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Project structure ingest failed: " + e.getMessage(), e);
        }

        Path packageJson = absolute.resolve("package.json");
        if (Files.exists(packageJson)) {
            parsePackageJson(graph, absolute.relativize(packageJson).toString().replace('\\', '/'),
                    Files.readString(packageJson, StandardCharsets.UTF_8));
        }

        List<Path> files = listSourceFiles(absolute);
        log.info("Ingesting {} source files from {} as {}", files.size(), absolute, kind);

        for (Path file : files) {
            String relative = absolute.relativize(file).toString().replace('\\', '/');
            String language = languageFor(file);
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String fileId = "file:" + relative;
            graph.addNode(new CodeNode(
                    fileId,
                    CodeNode.NodeKind.FILE,
                    file.getFileName().toString(),
                    relative,
                    language,
                    relative,
                    null,
                    snippet(content, 0, 4)
            ));

            switch (language) {
                case "java" -> parseJava(graph, fileId, relative, content);
                case "javascript", "typescript" -> {
                    parseJsTs(graph, fileId, relative, language, content);
                    if (relative.contains("router") || relative.endsWith("routes.js")
                            || relative.endsWith("routes.ts")) {
                        parseRoutes(graph, fileId, relative, language, content);
                    }
                }
                case "vue" -> parseVue(graph, fileId, relative, content);
                case "python" -> parsePython(graph, fileId, relative, content);
                case "json" -> {
                    if (relative.endsWith("package.json")) {
                        // already handled at root; nested workspaces still parse
                        parsePackageJson(graph, relative, content);
                    }
                }
                default -> {
                }
            }
        }

        // Structural enrichers: Java type hierarchy + Vue component composition tree
        for (GraphEnricher enricher : enrichers) {
            int edges = enricher.enrich(graph, absolute);
            log.info("Enricher {} added {} edges", enricher.name(), edges);
        }

        return graph;
    }

    private List<Path> listSourceFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(this::isSourceFile)
                    .filter(p -> {
                        for (Path part : root.relativize(p)) {
                            if (SKIP_DIRS.contains(part.toString())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .sorted()
                    .toList();
        }
    }

    private boolean isSourceFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".java")
                || name.endsWith(".js")
                || name.endsWith(".jsx")
                || name.endsWith(".mjs")
                || name.endsWith(".cjs")
                || name.endsWith(".ts")
                || name.endsWith(".tsx")
                || name.endsWith(".vue")
                || name.endsWith(".py")
                || name.equals("package.json");
    }

    private String languageFor(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            return "java";
        }
        if (name.endsWith(".py")) {
            return "python";
        }
        if (name.endsWith(".vue")) {
            return "vue";
        }
        if (name.equals("package.json")) {
            return "json";
        }
        if (name.endsWith(".ts") || name.endsWith(".tsx")) {
            return "typescript";
        }
        return "javascript";
    }

    private void parsePackageJson(CodeGraph graph, String relative, String content) throws IOException {
        JsonNode root = mapper.readTree(content);
        String name = root.path("name").asText(Path.of(graph.getRootPath()).getFileName().toString());
        if (graph.getProjectName() == null || relative.equals("package.json")) {
            graph.setProjectName(name);
        }

        String fileId = "file:" + relative;
        graph.addNode(new CodeNode(
                fileId,
                CodeNode.NodeKind.FILE,
                "package.json",
                relative,
                "json",
                relative,
                "package " + name,
                snippet(content, 0, 8)
        ));

        addDependencyNodes(graph, fileId, relative, root.path("dependencies"), "runtime");
        addDependencyNodes(graph, fileId, relative, root.path("devDependencies"), "dev");
        addDependencyNodes(graph, fileId, relative, root.path("peerDependencies"), "peer");

        JsonNode scripts = root.path("scripts");
        if (scripts.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = scripts.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String scriptId = "script:" + relative + "#" + entry.getKey();
                graph.addNode(new CodeNode(
                        scriptId,
                        CodeNode.NodeKind.SCRIPT,
                        entry.getKey(),
                        entry.getKey(),
                        "npm",
                        relative,
                        "npm run " + entry.getKey(),
                        entry.getValue().asText("")
                ));
                graph.addEdge(new CodeEdge(fileId, scriptId, CodeEdge.Relation.DEFINES));
            }
        }
    }

    private void addDependencyNodes(
            CodeGraph graph,
            String fileId,
            String relative,
            JsonNode deps,
            String scope
    ) {
        if (deps == null || !deps.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = deps.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String depName = entry.getKey();
            String version = entry.getValue().asText("");
            String depId = "dep:" + scope + ":" + depName;
            graph.addNode(new CodeNode(
                    depId,
                    CodeNode.NodeKind.DEPENDENCY,
                    depName,
                    depName + "@" + version,
                    "npm",
                    relative,
                    scope + " " + depName + "@" + version,
                    version
            ));
            graph.addEdge(new CodeEdge(fileId, depId, CodeEdge.Relation.IMPORTS));
        }
    }

    private void parseVue(CodeGraph graph, String fileId, String relative, String content) {
        String componentName = componentNameFromPath(relative);
        String componentId = "component:" + relative;
        boolean scriptSetup = false;
        String scriptLang = "javascript";
        StringBuilder scriptBody = new StringBuilder();

        Matcher scripts = VUE_SCRIPT.matcher(content);
        while (scripts.find()) {
            String attrs = scripts.group(1) == null ? "" : scripts.group(1).toLowerCase(Locale.ROOT);
            scriptSetup = scriptSetup || attrs.contains("setup");
            if (attrs.contains("lang=\"ts\"") || attrs.contains("lang='ts'")) {
                scriptLang = "typescript";
            }
            scriptBody.append(scripts.group(2)).append('\n');
        }

        boolean hasTemplate = VUE_TEMPLATE.matcher(content).find();
        boolean hasStyle = VUE_STYLE.matcher(content).find();
        String signature = "SFC " + componentName
                + (scriptSetup ? " <script setup>" : "")
                + (hasTemplate ? " +template" : "")
                + (hasStyle ? " +style" : "");

        graph.addNode(new CodeNode(
                componentId,
                CodeNode.NodeKind.COMPONENT,
                componentName,
                relative,
                "vue",
                relative,
                signature,
                snippet(content, 0, 10)
        ));
        graph.addEdge(new CodeEdge(fileId, componentId, CodeEdge.Relation.DEFINES));

        String script = scriptBody.toString();
        if (!script.isBlank()) {
            parseJsTs(graph, fileId, relative, scriptLang, script);
        }

        Matcher props = VUE_PROPS.matcher(script.isBlank() ? content : script);
        if (props.find()) {
            String propsId = "props:" + relative;
            graph.addNode(new CodeNode(
                    propsId,
                    CodeNode.NodeKind.FUNCTION,
                    componentName + ".props",
                    relative + "#props",
                    "vue",
                    relative,
                    "props",
                    truncate(props.group(1), 180)
            ));
            graph.addEdge(new CodeEdge(componentId, propsId, CodeEdge.Relation.DEFINES));
        }

        Matcher emits = VUE_EMITS.matcher(script.isBlank() ? content : script);
        if (emits.find()) {
            String emitsId = "emits:" + relative;
            graph.addNode(new CodeNode(
                    emitsId,
                    CodeNode.NodeKind.FUNCTION,
                    componentName + ".emits",
                    relative + "#emits",
                    "vue",
                    relative,
                    "emits",
                    truncate(emits.group(1), 180)
            ));
            graph.addEdge(new CodeEdge(componentId, emitsId, CodeEdge.Relation.DEFINES));
        }

        Matcher template = VUE_TEMPLATE.matcher(content);
        if (template.find()) {
            String templateBody = template.group(2);
            Matcher child = VUE_COMPONENT_TAG.matcher(templateBody);
            while (child.find()) {
                String childName = child.group(1);
                if (List.of("Template", "Script", "Style", "Component", "Transition", "KeepAlive", "Suspense")
                        .contains(childName)) {
                    continue;
                }
                String childId = "component-ref:" + relative + "#" + childName;
                graph.addNode(new CodeNode(
                        childId,
                        CodeNode.NodeKind.COMPONENT,
                        childName,
                        childName,
                        "vue",
                        relative,
                        "<" + childName + ">",
                        null
                ));
                graph.addEdge(new CodeEdge(componentId, childId, CodeEdge.Relation.CALLS));
            }
        }
    }

    private void parseRoutes(CodeGraph graph, String fileId, String relative, String language, String content) {
        Matcher routes = ROUTE_PATH.matcher(content);
        while (routes.find()) {
            String path = routes.group(1);
            String routeId = "route:" + relative + "#" + path;
            graph.addNode(new CodeNode(
                    routeId,
                    CodeNode.NodeKind.ROUTE,
                    path,
                    path,
                    language,
                    relative,
                    "path: " + path,
                    snippet(content, lineOf(content, routes.start()), 4)
            ));
            graph.addEdge(new CodeEdge(fileId, routeId, CodeEdge.Relation.DEFINES));
        }
    }

    private String componentNameFromPath(String relative) {
        String fileName = Path.of(relative).getFileName().toString();
        if (fileName.endsWith(".vue")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }

    private void parseJava(CodeGraph graph, String fileId, String relative, String content) {
        Matcher pkg = JAVA_PACKAGE.matcher(content);
        String packageName = pkg.find() ? pkg.group(1) : "(default)";
        String packageId = "package:" + packageName;
        graph.addNode(new CodeNode(
                packageId, CodeNode.NodeKind.PACKAGE, packageName, packageName,
                "java", relative, null, null
        ));
        graph.addEdge(new CodeEdge(fileId, packageId, CodeEdge.Relation.BELONGS_TO));

        Matcher imports = JAVA_IMPORT.matcher(content);
        while (imports.find()) {
            String imported = imports.group(1);
            String importId = "import:" + relative + ":" + imported;
            graph.addNode(new CodeNode(
                    importId, CodeNode.NodeKind.IMPORT, simpleName(imported), imported,
                    "java", relative, null, null
            ));
            graph.addEdge(new CodeEdge(fileId, importId, CodeEdge.Relation.IMPORTS));
        }

        Matcher types = JAVA_TYPE.matcher(content);
        while (types.find()) {
            String kindWord = types.group(1);
            String typeName = types.group(2);
            String qname = packageName + "." + typeName;
            String typeId = "type:" + qname;
            CodeNode.NodeKind kind = "interface".equals(kindWord)
                    ? CodeNode.NodeKind.INTERFACE
                    : CodeNode.NodeKind.CLASS;
            int line = lineOf(content, types.start());
            graph.addNode(new CodeNode(
                    typeId, kind, typeName, qname, "java", relative,
                    kindWord + " " + typeName, snippet(content, line, 8)
            ));
            graph.addEdge(new CodeEdge(fileId, typeId, CodeEdge.Relation.DEFINES));
            graph.addEdge(new CodeEdge(packageId, typeId, CodeEdge.Relation.CONTAINS));
        }

        Matcher methods = JAVA_METHOD.matcher(content);
        while (methods.find()) {
            String methodName = methods.group(1);
            if (isJavaKeywordNoise(methodName)) {
                continue;
            }
            String params = methods.group(2).trim();
            String methodId = "method:" + relative + "#" + methodName + "(" + params + ")";
            int line = lineOf(content, methods.start());
            graph.addNode(new CodeNode(
                    methodId, CodeNode.NodeKind.METHOD, methodName,
                    relative + "#" + methodName, "java", relative,
                    methodName + "(" + params + ")", snippet(content, line, 6)
            ));
            graph.addEdge(new CodeEdge(fileId, methodId, CodeEdge.Relation.DEFINES));
        }
    }

    private void parseJsTs(CodeGraph graph, String fileId, String relative, String language, String content) {
        Matcher imports = JS_IMPORT.matcher(content);
        while (imports.find()) {
            String imported = imports.group(1);
            String importId = "import:" + relative + ":" + imported;
            graph.addNode(new CodeNode(
                    importId, CodeNode.NodeKind.IMPORT, simpleName(imported), imported,
                    language, relative, null, null
            ));
            graph.addEdge(new CodeEdge(fileId, importId, CodeEdge.Relation.IMPORTS));
        }

        Matcher classes = JS_CLASS.matcher(content);
        while (classes.find()) {
            String className = classes.group(1);
            String classId = "type:" + relative + "#" + className;
            int line = lineOf(content, classes.start());
            graph.addNode(new CodeNode(
                    classId, CodeNode.NodeKind.CLASS, className, className,
                    language, relative, "class " + className, snippet(content, line, 8)
            ));
            graph.addEdge(new CodeEdge(fileId, classId, CodeEdge.Relation.DEFINES));
        }

        Matcher functions = JS_FUNCTION.matcher(content);
        while (functions.find()) {
            String name = functions.group(1) != null ? functions.group(1) : functions.group(2);
            if (name == null) {
                continue;
            }
            String fnId = "fn:" + relative + "#" + name;
            int line = lineOf(content, functions.start());
            graph.addNode(new CodeNode(
                    fnId, CodeNode.NodeKind.FUNCTION, name, relative + "#" + name,
                    language, relative, name + "()", snippet(content, line, 6)
            ));
            graph.addEdge(new CodeEdge(fileId, fnId, CodeEdge.Relation.DEFINES));
        }
    }

    private void parsePython(CodeGraph graph, String fileId, String relative, String content) {
        Matcher imports = PY_IMPORT.matcher(content);
        while (imports.find()) {
            String imported = imports.group(1) != null ? imports.group(1) : imports.group(2);
            if (imported == null) {
                continue;
            }
            for (String part : imported.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String importId = "import:" + relative + ":" + trimmed;
                graph.addNode(new CodeNode(
                        importId, CodeNode.NodeKind.IMPORT, simpleName(trimmed), trimmed,
                        "python", relative, null, null
                ));
                graph.addEdge(new CodeEdge(fileId, importId, CodeEdge.Relation.IMPORTS));
            }
        }

        Matcher classes = PY_CLASS.matcher(content);
        while (classes.find()) {
            String className = classes.group(1);
            String bases = classes.group(2);
            String classId = "type:" + relative + "#" + className;
            int line = lineOf(content, classes.start());
            graph.addNode(new CodeNode(
                    classId, CodeNode.NodeKind.CLASS, className, className,
                    "python", relative, "class " + className, snippet(content, line, 8)
            ));
            graph.addEdge(new CodeEdge(fileId, classId, CodeEdge.Relation.DEFINES));
            if (bases != null && !bases.isBlank()) {
                for (String base : bases.split(",")) {
                    String b = base.trim();
                    if (!b.isEmpty()) {
                        String baseId = "type-ref:" + b;
                        graph.addNode(new CodeNode(
                                baseId, CodeNode.NodeKind.CLASS, simpleName(b), b,
                                "python", relative, null, null
                        ));
                        graph.addEdge(new CodeEdge(classId, baseId, CodeEdge.Relation.EXTENDS));
                    }
                }
            }
        }

        Matcher defs = PY_DEF.matcher(content);
        while (defs.find()) {
            String name = defs.group(1);
            String params = defs.group(2).trim();
            String fnId = "fn:" + relative + "#" + name;
            int line = lineOf(content, defs.start());
            graph.addNode(new CodeNode(
                    fnId, CodeNode.NodeKind.FUNCTION, name, relative + "#" + name,
                    "python", relative, name + "(" + params + ")", snippet(content, line, 6)
            ));
            graph.addEdge(new CodeEdge(fileId, fnId, CodeEdge.Relation.DEFINES));
        }
    }

    private boolean isJavaKeywordNoise(String name) {
        return List.of("if", "for", "while", "switch", "catch", "return", "new", "class").contains(name);
    }

    private String simpleName(String qualified) {
        if (qualified == null) {
            return "";
        }
        String cleaned = qualified.replace(".*", "");
        int slash = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('.'));
        return slash >= 0 ? cleaned.substring(slash + 1) : cleaned;
    }

    private int lineOf(String content, int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String snippet(String content, int startLine, int maxLines) {
        String[] lines = content.split("\\R", -1);
        List<String> selected = new ArrayList<>();
        for (int i = startLine; i < lines.length && selected.size() < maxLines; i++) {
            selected.add(lines[i]);
        }
        return String.join("\n", selected).trim();
    }
}
