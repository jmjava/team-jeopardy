package com.jmjava.teamjeopardy.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Walks a source tree and builds a code graph using language-aware heuristics.
 * This is intentionally dependency-light so demos run without external graph DBs.
 */
@Service
public class CodeGraphIngester {

    private static final Logger log = LoggerFactory.getLogger(CodeGraphIngester.class);

    private static final List<String> SKIP_DIRS = List.of(
            ".git", "node_modules", "target", "build", "dist", ".idea", ".vscode", "__pycache__"
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
    private static final Pattern PY_DEF = Pattern.compile("^\\s*(?:async\\s+)?def\\s+(\\w+)\\s*\\(([^)]*)\\)", Pattern.MULTILINE);
    private static final Pattern PY_CLASS = Pattern.compile("^\\s*class\\s+(\\w+)\\s*(?:\\(([^)]*)\\))?:", Pattern.MULTILINE);
    private static final Pattern PY_IMPORT = Pattern.compile("^(?:from\\s+([\\w.]+)\\s+import\\s+.+|import\\s+([\\w.,\\s]+))", Pattern.MULTILINE);

    public CodeGraph ingest(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("Path is not a directory: " + absolute);
        }

        CodeGraph graph = new CodeGraph(absolute.toString());
        List<Path> files = listSourceFiles(absolute);
        log.info("Ingesting {} source files from {}", files.size(), absolute);

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
                case "javascript", "typescript" -> parseJsTs(graph, fileId, relative, language, content);
                case "python" -> parsePython(graph, fileId, relative, content);
                default -> {
                }
            }
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
                || name.endsWith(".ts")
                || name.endsWith(".tsx")
                || name.endsWith(".py");
    }

    private String languageFor(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            return "java";
        }
        if (name.endsWith(".py")) {
            return "python";
        }
        if (name.endsWith(".ts") || name.endsWith(".tsx")) {
            return "typescript";
        }
        return "javascript";
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
