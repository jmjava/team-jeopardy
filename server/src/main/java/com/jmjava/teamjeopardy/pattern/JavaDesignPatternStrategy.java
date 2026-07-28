package com.jmjava.teamjeopardy.pattern;

import com.jmjava.teamjeopardy.graph.CodeEdge;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Classic / enterprise Java patterns (GoF + Spring).
 * Piggybacks on hierarchy edges from {@code JavaClassHierarchyEnricher} plus source cues.
 */
@Component
public class JavaDesignPatternStrategy implements PatternStrategy {

    private static final Pattern PRIVATE_CTOR = Pattern.compile(
            "private\\s+(\\w+)\\s*\\(\\s*\\)");
    private static final Pattern STATIC_INSTANCE = Pattern.compile(
            "private\\s+static\\s+(?:final\\s+)?(\\w+)\\s+INSTANCE\\b|getInstance\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BUILDER = Pattern.compile(
            "static\\s+final\\s+class\\s+Builder\\b|class\\s+(\\w+)Builder\\b|\\.builder\\s*\\(");
    private static final Pattern FACTORY_METHOD = Pattern.compile(
            "(?:public\\s+)?static\\s+(\\w+)\\s+(create|of|from|valueOf|getInstance|newInstance)\\s*\\(");
    private static final Pattern OBSERVER = Pattern.compile(
            "(?:List|Set|Collection)\\s*<\\s*\\w*(?:Listener|Observer|Handler|Subscriber)\\w*\\s*>"
                    + "|add(?:Listener|Observer|Handler)\\s*\\(|remove(?:Listener|Observer)\\s*\\(");
    private static final Pattern SPRING_STEREOTYPE = Pattern.compile(
            "@(Service|Repository|Controller|RestController|Component|Bean)\\b");
    private static final Pattern INJECT = Pattern.compile(
            "@(Autowired|Inject|Resource)\\b|private\\s+final\\s+\\w+\\s+\\w+\\s*;");

    @Override
    public String id() {
        return "java-design-patterns";
    }

    @Override
    public String displayName() {
        return "Java design patterns";
    }

    @Override
    public Set<String> languages() {
        return Set.of("java");
    }

    @Override
    public boolean supports(CodeGraph graph) {
        return graph.nodes().stream().anyMatch(n -> "java".equals(n.language())
                && (n.kind() == CodeNode.NodeKind.CLASS || n.kind() == CodeNode.NodeKind.INTERFACE));
    }

    @Override
    public List<PatternFact> find(CodeGraph graph) {
        List<PatternFact> facts = new ArrayList<>();
        facts.addAll(detectStrategyPattern(graph));
        facts.addAll(detectTemplateMethod(graph));
        facts.addAll(detectRepositoryNaming(graph));
        facts.addAll(detectFromSources(graph));
        return facts;
    }

    /**
     * Strategy: an interface/abstract type with 2+ implementing/extending concrete types.
     */
    private List<PatternFact> detectStrategyPattern(CodeGraph graph) {
        List<PatternFact> facts = new ArrayList<>();
        Map<String, List<String>> implementors = new HashMap<>();
        for (CodeEdge edge : graph.edgesOf(CodeEdge.Relation.IMPLEMENTS)) {
            implementors.computeIfAbsent(edge.toId(), k -> new ArrayList<>()).add(edge.fromId());
        }
        for (CodeEdge edge : graph.edgesOf(CodeEdge.Relation.EXTENDS)) {
            graph.findById(edge.toId()).ifPresent(parent -> {
                if (parent.kind() == CodeNode.NodeKind.INTERFACE
                        || (parent.signature() != null && parent.signature().contains("abstract"))) {
                    implementors.computeIfAbsent(edge.toId(), k -> new ArrayList<>()).add(edge.fromId());
                }
            });
        }

        for (Map.Entry<String, List<String>> entry : implementors.entrySet()) {
            List<String> impls = entry.getValue().stream().distinct().toList();
            if (impls.size() < 2) {
                continue;
            }
            CodeNode strategy = graph.findById(entry.getKey()).orElse(null);
            if (strategy == null) {
                continue;
            }
            String implNames = impls.stream()
                    .map(id -> graph.findById(id).map(CodeNode::name).orElse(id))
                    .collect(Collectors.joining(", "));
            facts.add(PatternFacts.of(
                    id(), "java", "Strategy",
                    "Strategy-like family: `" + strategy.name() + "` has "
                            + impls.size() + " variants (" + implNames + ").",
                    strategy.kind().name(), strategy.id(),
                    "HAS_STRATEGY_VARIANTS",
                    "TypeFamily", "family:" + strategy.id(),
                    0.75,
                    strategy.filePath(),
                    List.of("java", "gof", "strategy"),
                    Map.of("variantCount", impls.size(), "variants", implNames)
            ));
        }
        return facts;
    }

    private List<PatternFact> detectTemplateMethod(CodeGraph graph) {
        List<PatternFact> facts = new ArrayList<>();
        for (CodeNode type : graph.nodesOfKind(CodeNode.NodeKind.CLASS)) {
            if (!"java".equals(type.language())) {
                continue;
            }
            boolean abstractCue = type.signature() != null && type.signature().contains("abstract");
            long subclasses = graph.edgesOf(CodeEdge.Relation.EXTENDS).stream()
                    .filter(e -> e.toId().equals(type.id()))
                    .count();
            if (abstractCue && subclasses >= 1) {
                facts.add(PatternFacts.of(
                        id(), "java", "Template Method",
                        "Template Method cue: abstract type `" + type.name()
                                + "` with " + subclasses + " subclass(es).",
                        "CLASS", type.id(),
                        "SUGGESTS_TEMPLATE_METHOD",
                        "Pattern", "pattern:TemplateMethod",
                        0.7,
                        type.filePath(),
                        List.of("java", "gof", "template-method"),
                        Map.of("subclassCount", subclasses)
                ));
            }
        }
        return facts;
    }

    private List<PatternFact> detectRepositoryNaming(CodeGraph graph) {
        List<PatternFact> facts = new ArrayList<>();
        for (CodeNode type : graph.nodes()) {
            if (!"java".equals(type.language())) {
                continue;
            }
            if (type.kind() != CodeNode.NodeKind.CLASS && type.kind() != CodeNode.NodeKind.INTERFACE) {
                continue;
            }
            String name = type.name();
            if (name.endsWith("Repository") || name.endsWith("Dao") || name.endsWith("DAO")) {
                facts.add(PatternFacts.of(
                        id(), "java", "Repository",
                        "Repository/DAO naming pattern on `" + name + "`.",
                        type.kind().name(), type.id(),
                        "MATCHES_REPOSITORY_PATTERN",
                        "Pattern", "pattern:Repository",
                        0.85,
                        type.filePath(),
                        List.of("java", "enterprise", "repository"),
                        Map.of("suffix", name.endsWith("DAO") || name.endsWith("Dao") ? "DAO" : "Repository")
                ));
            }
            if (name.endsWith("Factory") || name.endsWith("FactoryBean")) {
                facts.add(PatternFacts.of(
                        id(), "java", "Factory",
                        "Factory naming pattern on `" + name + "`.",
                        type.kind().name(), type.id(),
                        "MATCHES_FACTORY_PATTERN",
                        "Pattern", "pattern:Factory",
                        0.8,
                        type.filePath(),
                        List.of("java", "gof", "factory"),
                        Map.of()
                ));
            }
            if (name.endsWith("Builder")) {
                facts.add(PatternFacts.of(
                        id(), "java", "Builder",
                        "Builder naming pattern on `" + name + "`.",
                        type.kind().name(), type.id(),
                        "MATCHES_BUILDER_PATTERN",
                        "Pattern", "pattern:Builder",
                        0.8,
                        type.filePath(),
                        List.of("java", "gof", "builder"),
                        Map.of()
                ));
            }
            if (name.endsWith("Observer") || name.endsWith("Listener") || name.endsWith("Subscriber")) {
                facts.add(PatternFacts.of(
                        id(), "java", "Observer",
                        "Observer/Listener role naming on `" + name + "`.",
                        type.kind().name(), type.id(),
                        "MATCHES_OBSERVER_ROLE",
                        "Pattern", "pattern:Observer",
                        0.75,
                        type.filePath(),
                        List.of("java", "gof", "observer"),
                        Map.of()
                ));
            }
        }
        return facts;
    }

    private List<PatternFact> detectFromSources(CodeGraph graph) {
        List<PatternFact> facts = new ArrayList<>();
        Path root = Path.of(graph.getRootPath());
        if (!Files.isDirectory(root)) {
            return facts;
        }
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).filter(Files::isRegularFile).toList()) {
                if (isSkipped(root, file)) {
                    continue;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String typeName = file.getFileName().toString().replace(".java", "");
                String typeId = graph.nodes().stream()
                        .filter(n -> typeName.equals(n.name()) && "java".equals(n.language()))
                        .map(CodeNode::id)
                        .findFirst()
                        .orElse("type:" + relative + "#" + typeName);

                if (PRIVATE_CTOR.matcher(content).find() && STATIC_INSTANCE.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), "java", "Singleton",
                            "Singleton cues in `" + typeName + "` (private ctor + static instance/getInstance).",
                            "CLASS", typeId,
                            "MATCHES_SINGLETON_PATTERN",
                            "Pattern", "pattern:Singleton",
                            0.8,
                            relative,
                            List.of("java", "gof", "singleton"),
                            Map.of()
                    ));
                }
                if (BUILDER.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), "java", "Builder",
                            "Builder structural cues in `" + typeName + "`.",
                            "CLASS", typeId,
                            "MATCHES_BUILDER_PATTERN",
                            "Pattern", "pattern:Builder",
                            0.78,
                            relative,
                            List.of("java", "gof", "builder"),
                            Map.of()
                    ));
                }
                if (FACTORY_METHOD.matcher(content).find() && !typeName.contains("Test")) {
                    facts.add(PatternFacts.of(
                            id(), "java", "Factory Method",
                            "Factory Method cues (static create/of/getInstance) in `" + typeName + "`.",
                            "CLASS", typeId,
                            "MATCHES_FACTORY_METHOD_PATTERN",
                            "Pattern", "pattern:FactoryMethod",
                            0.7,
                            relative,
                            List.of("java", "gof", "factory-method"),
                            Map.of()
                    ));
                }
                if (OBSERVER.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), "java", "Observer",
                            "Observer/Listener collection or registration APIs in `" + typeName + "`.",
                            "CLASS", typeId,
                            "MATCHES_OBSERVER_PATTERN",
                            "Pattern", "pattern:Observer",
                            0.72,
                            relative,
                            List.of("java", "gof", "observer"),
                            Map.of()
                    ));
                }
                Matcher spring = SPRING_STEREOTYPE.matcher(content);
                if (spring.find()) {
                    String stereo = spring.group(1);
                    String pattern = switch (stereo) {
                        case "Repository" -> "Repository";
                        case "Controller", "RestController" -> "MVC Controller";
                        case "Service" -> "Service Layer";
                        default -> "Dependency Injection";
                    };
                    facts.add(PatternFacts.of(
                            id(), "java", pattern,
                            "Spring @" + stereo + " on `" + typeName + "` suggests "
                                    + pattern.toLowerCase(Locale.ROOT) + ".",
                            "CLASS", typeId,
                            "MATCHES_SPRING_" + stereo.toUpperCase(Locale.ROOT),
                            "Pattern", "pattern:" + pattern.replace(" ", ""),
                            0.9,
                            relative,
                            List.of("java", "spring", "architecture"),
                            Map.of("stereotype", stereo)
                    ));
                }
                if (INJECT.matcher(content).find()
                        && (content.contains("final") || content.contains("@Autowired") || content.contains("@Inject"))) {
                    facts.add(PatternFacts.of(
                            id(), "java", "Dependency Injection",
                            "DI cues (injected/final collaborators) in `" + typeName + "`.",
                            "CLASS", typeId,
                            "MATCHES_DEPENDENCY_INJECTION",
                            "Pattern", "pattern:DependencyInjection",
                            0.7,
                            relative,
                            List.of("java", "architecture", "di"),
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
            if (List.of(".git", "target", "build", ".gradle", "node_modules").contains(n)) {
                return true;
            }
        }
        return false;
    }
}
