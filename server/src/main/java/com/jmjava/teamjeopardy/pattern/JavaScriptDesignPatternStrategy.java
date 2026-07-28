package com.jmjava.teamjeopardy.pattern;

import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Idiomatic JavaScript / TypeScript module patterns (not Vue-specific).
 */
@Component
public class JavaScriptDesignPatternStrategy implements PatternStrategy {

    private static final Pattern EVENT_EMITTER = Pattern.compile(
            "\\b(EventEmitter|mitt\\b|\\.on\\s*\\(|\\.off\\s*\\(|addEventListener\\s*\\()");
    private static final Pattern OBSERVERISH = Pattern.compile(
            "\\b(subscribe\\s*\\(|unsubscribe\\s*\\(|publish\\s*\\(|notify\\s*\\()");
    private static final Pattern FACTORY_FN = Pattern.compile(
            "(?m)^\\s*export\\s+(?:default\\s+)?(?:function|const)\\s+(create\\w+|make\\w+)\\b");
    private static final Pattern SINGLETON_EXPORT = Pattern.compile(
            "(?m)^\\s*export\\s+default\\s+new\\s+\\w+|export\\s+const\\s+\\w+\\s*=\\s*new\\s+\\w+");
    private static final Pattern MIDDLEWARE = Pattern.compile(
            "\\b(app\\.use\\s*\\(|router\\.use\\s*\\(|\\(\\s*req\\s*,\\s*res\\s*,\\s*next\\s*\\))");
    private static final Pattern MODULE_EXPORTS = Pattern.compile(
            "(?m)^\\s*(export\\s+(?:default\\b|const\\b|function\\b|class\\b)|module\\.exports\\s*=)");
    private static final Pattern VUE_MARKERS = Pattern.compile(
            "<template[\\s>]|defineStore\\s*\\(|defineProps\\s*\\(|<script\\s+[^>]*\\bsetup\\b",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String id() {
        return "javascript-design-patterns";
    }

    @Override
    public String displayName() {
        return "JavaScript design patterns";
    }

    @Override
    public Set<String> languages() {
        return Set.of("javascript", "typescript");
    }

    @Override
    public List<PatternFact> find(CodeGraph graph) {
        List<PatternFact> facts = new ArrayList<>();
        Path root = Path.of(graph.getRootPath());
        if (!Files.isDirectory(root)) {
            return facts;
        }
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (isSkipped(root, file)) {
                    continue;
                }
                String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(fileName.endsWith(".js") || fileName.endsWith(".ts")
                        || fileName.endsWith(".mjs") || fileName.endsWith(".cjs")
                        || fileName.endsWith(".jsx") || fileName.endsWith(".tsx"))) {
                    continue;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                String lower = relative.toLowerCase(Locale.ROOT);
                // Leave Vue composables/stores/SFCs to VueDesignPatternStrategy.
                if (lower.contains("/composables/") || lower.contains("/stores/")
                        || lower.endsWith(".vue")) {
                    continue;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (content.isBlank() || VUE_MARKERS.matcher(content).find()) {
                    continue;
                }
                String lang = fileName.endsWith(".ts") || fileName.endsWith(".tsx")
                        ? "typescript" : "javascript";
                String subject = file.getFileName().toString().replaceAll("\\.[^.]+$", "");
                String subjectId = graph.nodes().stream()
                        .filter(n -> relative.equals(n.filePath())
                                && (n.kind() == CodeNode.NodeKind.FILE
                                || n.kind() == CodeNode.NodeKind.FUNCTION
                                || n.kind() == CodeNode.NodeKind.CLASS))
                        .map(CodeNode::id)
                        .findFirst()
                        .orElse("file:" + relative);

                if (EVENT_EMITTER.matcher(content).find() || OBSERVERISH.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), lang, "Observer",
                            "Pub/sub or EventEmitter-style observer cues in `" + subject + "`.",
                            "MODULE", subjectId,
                            "MATCHES_OBSERVER_PATTERN",
                            "Pattern", "pattern:Observer",
                            0.72,
                            relative,
                            List.of(lang, "observer"),
                            Map.of()
                    ));
                }
                if (FACTORY_FN.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), lang, "Factory Method",
                            "Factory function (create*/make*) export in `" + subject + "`.",
                            "FUNCTION", subjectId,
                            "MATCHES_FACTORY_METHOD_PATTERN",
                            "Pattern", "pattern:FactoryMethod",
                            0.75,
                            relative,
                            List.of(lang, "factory"),
                            Map.of()
                    ));
                }
                if (SINGLETON_EXPORT.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), lang, "Singleton",
                            "Module singleton (exported shared instance) in `" + subject + "`.",
                            "MODULE", subjectId,
                            "MATCHES_SINGLETON_PATTERN",
                            "Pattern", "pattern:Singleton",
                            0.8,
                            relative,
                            List.of(lang, "singleton"),
                            Map.of()
                    ));
                }
                if (MIDDLEWARE.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), lang, "Middleware",
                            "Middleware / chain-of-responsibility style handler in `" + subject + "`.",
                            "FUNCTION", subjectId,
                            "MATCHES_MIDDLEWARE_PATTERN",
                            "Pattern", "pattern:Middleware",
                            0.8,
                            relative,
                            List.of(lang, "middleware"),
                            Map.of()
                    ));
                }
                if (MODULE_EXPORTS.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), lang, "Module",
                            "ESM/CJS module encapsulation in `" + subject + "`.",
                            "MODULE", subjectId,
                            "MATCHES_MODULE_PATTERN",
                            "Pattern", "pattern:Module",
                            0.6,
                            relative,
                            List.of(lang, "module"),
                            Map.of()
                    ));
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return facts;
    }

    private static boolean isSkipped(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            String n = part.toString();
            if (List.of(".git", "target", "build", ".gradle", "node_modules", "dist", "coverage")
                    .contains(n)) {
                return true;
            }
        }
        return false;
    }
}
