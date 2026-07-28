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
 * Vue framework idioms — distinct from plain JavaScript module patterns.
 */
@Component
public class VueDesignPatternStrategy implements PatternStrategy {

    private static final Pattern SCRIPT_SETUP = Pattern.compile(
            "<script\\s+[^>]*\\bsetup\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFINE_PROPS = Pattern.compile("\\bdefineProps\\s*\\(");
    private static final Pattern PROVIDE = Pattern.compile("\\bprovide\\s*\\(");
    private static final Pattern INJECT = Pattern.compile("\\binject\\s*\\(");
    private static final Pattern PINIA = Pattern.compile("\\bdefineStore\\s*\\(");
    private static final Pattern VUEX = Pattern.compile("\\bcreateStore\\s*\\(|\\bnew\\s+Vuex\\.Store\\b");
    private static final Pattern COMPOSABLE = Pattern.compile(
            "(?m)^\\s*export\\s+(?:default\\s+)?(?:function|const)\\s+(use[A-Z]\\w*)");
    private static final Pattern FETCHISH = Pattern.compile(
            "\\b(fetch\\s*\\(|axios\\b|useRouter\\b|onMounted\\s*\\(|defineStore\\s*\\()");

    @Override
    public String id() {
        return "vue-design-patterns";
    }

    @Override
    public String displayName() {
        return "Vue design patterns";
    }

    @Override
    public Set<String> languages() {
        return Set.of("vue");
    }

    @Override
    public boolean supports(CodeGraph graph) {
        return graph.nodes().stream().anyMatch(n ->
                "vue".equalsIgnoreCase(n.language())
                        || n.kind() == CodeNode.NodeKind.COMPONENT
                        || isVueModulePath(n.filePath()));
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
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean vueSfc = name.endsWith(".vue");
                boolean jsModule = name.endsWith(".js") || name.endsWith(".ts")
                        || name.endsWith(".mjs") || name.endsWith(".cjs");
                if (!vueSfc && !jsModule) {
                    continue;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!vueSfc && !isVueModulePath(relative)) {
                    continue;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String subject = file.getFileName().toString().replaceAll("\\.(vue|js|ts|mjs|cjs)$", "");
                String subjectId = graph.nodes().stream()
                        .filter(n -> relative.equals(n.filePath())
                                && (n.kind() == CodeNode.NodeKind.COMPONENT
                                || n.kind() == CodeNode.NodeKind.FUNCTION
                                || n.kind() == CodeNode.NodeKind.FILE))
                        .map(CodeNode::id)
                        .findFirst()
                        .orElse("file:" + relative);

                if (vueSfc && SCRIPT_SETUP.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), "vue", "Composition API SFC",
                            "`" + subject + "` uses `<script setup>` composition API style.",
                            "COMPONENT", subjectId,
                            "MATCHES_SCRIPT_SETUP",
                            "Pattern", "pattern:ScriptSetup",
                            0.9,
                            relative,
                            List.of("vue", "composition-api"),
                            Map.of()
                    ));
                }
                if (COMPOSABLE.matcher(content).find()
                        || (relative.contains("/composables/") && subject.startsWith("use"))) {
                    facts.add(PatternFacts.of(
                            id(), "vue", "Composable",
                            "Vue composable `" + subject + "` exports a reusable use* function.",
                            "FUNCTION", subjectId,
                            "MATCHES_COMPOSABLE",
                            "Pattern", "pattern:Composable",
                            0.88,
                            relative,
                            List.of("vue", "composable"),
                            Map.of()
                    ));
                }
                if (PINIA.matcher(content).find() || VUEX.matcher(content).find()
                        || relative.contains("/stores/")) {
                    String storeKind = PINIA.matcher(content).find() ? "Store (Pinia)"
                            : VUEX.matcher(content).find() ? "Store (Vuex)" : "Store";
                    facts.add(PatternFacts.of(
                            id(), "vue", storeKind,
                            "Centralized state store in `" + subject + "`.",
                            "MODULE", subjectId,
                            "MATCHES_VUE_STORE",
                            "Pattern", "pattern:Store",
                            0.9,
                            relative,
                            List.of("vue", "store", "state"),
                            Map.of()
                    ));
                }
                if (PROVIDE.matcher(content).find() || INJECT.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), "vue", "Provide/Inject",
                            "Vue provide/inject dependency sharing in `" + subject + "`.",
                            "COMPONENT", subjectId,
                            "MATCHES_PROVIDE_INJECT",
                            "Pattern", "pattern:ProvideInject",
                            0.8,
                            relative,
                            List.of("vue", "di"),
                            Map.of()
                    ));
                }
                if (vueSfc) {
                    boolean props = DEFINE_PROPS.matcher(content).find();
                    boolean sideEffects = FETCHISH.matcher(content).find();
                    boolean importsChild = content.contains(".vue");
                    if (props && !sideEffects && !importsChild) {
                        facts.add(PatternFacts.of(
                                id(), "vue", "Presentational Component",
                                "Presentational Vue component `" + subject + "` (props in, little orchestration).",
                                "COMPONENT", subjectId,
                                "MATCHES_PRESENTATIONAL",
                                "Pattern", "pattern:Presentational",
                                0.7,
                                relative,
                                List.of("vue", "presentational"),
                                Map.of()
                        ));
                    }
                    if (importsChild || sideEffects) {
                        facts.add(PatternFacts.of(
                                id(), "vue", "Container Component",
                                "Container-style Vue component `" + subject
                                        + "` orchestrating children or side effects.",
                                "COMPONENT", subjectId,
                                "MATCHES_CONTAINER",
                                "Pattern", "pattern:Container",
                                0.68,
                                relative,
                                List.of("vue", "container"),
                                Map.of()
                        ));
                    }
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return facts;
    }

    private static boolean isVueModulePath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return p.contains("/composables/") || p.contains("/stores/") || p.endsWith(".vue");
    }

    private static boolean isSkipped(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            String n = part.toString();
            if (List.of(".git", "target", "build", ".gradle", "node_modules", "dist").contains(n)) {
                return true;
            }
        }
        return false;
    }
}
