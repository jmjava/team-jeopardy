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
 * Idiomatic Python patterns: ABC/Protocol strategy families, dataclasses,
 * decorators, context managers, repositories, factories.
 */
@Component
public class PythonDesignPatternStrategy implements PatternStrategy {

    private static final Pattern CLASS = Pattern.compile(
            "(?m)^\\s*class\\s+(\\w+)\\s*(?:\\(([^)]*)\\))?:");
    private static final Pattern ABC = Pattern.compile("\\b(ABC|abstractmethod|Protocol)\\b");
    private static final Pattern ABSTRACTMETHOD = Pattern.compile("@abstractmethod\\b");
    private static final Pattern DATACLASS = Pattern.compile("@dataclass\\b");
    private static final Pattern SINGLETON = Pattern.compile(
            "(?m)^\\s*def\\s+__new__\\s*\\(|_instance\\s*=\\s*None|__instance\\s*=");
    private static final Pattern CONTEXT_MANAGER = Pattern.compile(
            "(?m)^\\s*def\\s+__enter__\\s*\\(|@contextmanager\\b");
    private static final Pattern DECORATOR_DEF = Pattern.compile(
            "(?m)^\\s*def\\s+(\\w+)\\s*\\([^)]*\\):[\\s\\S]{0,240}?def\\s+(?:async\\s+)?wrapper\\s*\\(");
    private static final Pattern FACTORY = Pattern.compile(
            "(?m)^\\s*def\\s+(create_\\w+|make_\\w+|from_\\w+)\\s*\\(");
    private static final Pattern OBSERVER = Pattern.compile(
            "\\b(subscribe|unsubscribe|notify|add_listener|remove_listener)\\s*\\(");
    private static final Pattern STRATEGY_NAME = Pattern.compile("(?i)strategy");

    @Override
    public String id() {
        return "python-design-patterns";
    }

    @Override
    public String displayName() {
        return "Python design patterns";
    }

    @Override
    public Set<String> languages() {
        return Set.of("python");
    }

    @Override
    public List<PatternFact> find(CodeGraph graph) {
        List<PatternFact> facts = new ArrayList<>();
        facts.addAll(detectStrategyFamilies(graph));
        facts.addAll(detectFromSources(graph));
        return facts;
    }

    private List<PatternFact> detectStrategyFamilies(CodeGraph graph) {
        List<PatternFact> facts = new ArrayList<>();
        Map<String, List<String>> children = new HashMap<>();
        for (CodeEdge edge : graph.edgesOf(CodeEdge.Relation.EXTENDS)) {
            CodeNode child = graph.findById(edge.fromId()).orElse(null);
            CodeNode parent = graph.findById(edge.toId()).orElse(null);
            if (child == null || parent == null) {
                continue;
            }
            if (!"python".equals(child.language())) {
                continue;
            }
            children.computeIfAbsent(parent.name(), k -> new ArrayList<>()).add(child.name());
        }
        for (Map.Entry<String, List<String>> entry : children.entrySet()) {
            List<String> impls = entry.getValue().stream().distinct().toList();
            if (impls.size() < 2 && !STRATEGY_NAME.matcher(entry.getKey()).find()) {
                continue;
            }
            if (impls.size() >= 2 || STRATEGY_NAME.matcher(entry.getKey()).find()) {
                if (impls.size() >= 2) {
                    String implNames = impls.stream().collect(Collectors.joining(", "));
                    facts.add(PatternFacts.of(
                            id(), "python", "Strategy",
                            "Strategy-like ABC/family `" + entry.getKey() + "` with "
                                    + impls.size() + " variants (" + implNames + ").",
                            "CLASS", "type-ref:" + entry.getKey(),
                            "HAS_STRATEGY_VARIANTS",
                            "TypeFamily", "family:" + entry.getKey(),
                            0.78,
                            null,
                            List.of("python", "gof", "strategy"),
                            Map.of("variantCount", impls.size(), "variants", implNames)
                    ));
                }
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
            for (Path file : walk.filter(p -> p.toString().endsWith(".py")).filter(Files::isRegularFile).toList()) {
                if (isSkipped(root, file)) {
                    continue;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String module = file.getFileName().toString().replace(".py", "");
                String fileId = "file:" + relative;

                Matcher classes = CLASS.matcher(content);
                while (classes.find()) {
                    String className = classes.group(1);
                    String bases = classes.group(2) == null ? "" : classes.group(2);
                    String typeId = graph.nodes().stream()
                            .filter(n -> className.equals(n.name()) && "python".equals(n.language()))
                            .map(CodeNode::id)
                            .findFirst()
                            .orElse("type:" + relative + "#" + className);
                    if (STRATEGY_NAME.matcher(className).find()
                            && (ABC.matcher(bases).find() || ABC.matcher(content).find()
                            || ABSTRACTMETHOD.matcher(content).find())) {
                        facts.add(PatternFacts.of(
                                id(), "python", "Strategy",
                                "Strategy ABC/protocol `" + className + "` defining interchangeable algorithms.",
                                "CLASS", typeId,
                                "MATCHES_STRATEGY_PATTERN",
                                "Pattern", "pattern:Strategy",
                                0.85,
                                relative,
                                List.of("python", "gof", "strategy"),
                                Map.of()
                        ));
                    }
                }

                if (DATACLASS.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), "python", "Dataclass",
                            "Python @dataclass value/object pattern in `" + module + "`.",
                            "CLASS", fileId,
                            "MATCHES_DATACLASS_PATTERN",
                            "Pattern", "pattern:Dataclass",
                            0.9,
                            relative,
                            List.of("python", "dataclass"),
                            Map.of()
                    ));
                }
                if (SINGLETON.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), "python", "Singleton",
                            "Singleton via __new__ or shared _instance in `" + module + "`.",
                            "CLASS", fileId,
                            "MATCHES_SINGLETON_PATTERN",
                            "Pattern", "pattern:Singleton",
                            0.8,
                            relative,
                            List.of("python", "gof", "singleton"),
                            Map.of()
                    ));
                }
                if (CONTEXT_MANAGER.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), "python", "Context Manager",
                            "Context manager (__enter__/__exit__ or @contextmanager) in `" + module + "`.",
                            "CLASS", fileId,
                            "MATCHES_CONTEXT_MANAGER",
                            "Pattern", "pattern:ContextManager",
                            0.85,
                            relative,
                            List.of("python", "context-manager"),
                            Map.of()
                    ));
                }
                if (DECORATOR_DEF.matcher(content).find() || countDecorators(content) >= 3) {
                    facts.add(PatternFacts.of(
                            id(), "python", "Decorator",
                            "Decorator pattern / Python function decorators in `" + module + "`.",
                            "FUNCTION", fileId,
                            "MATCHES_DECORATOR_PATTERN",
                            "Pattern", "pattern:Decorator",
                            0.75,
                            relative,
                            List.of("python", "decorator"),
                            Map.of()
                    ));
                }
                if (FACTORY.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), "python", "Factory Method",
                            "Factory function (create_*/make_*/from_*) in `" + module + "`.",
                            "FUNCTION", fileId,
                            "MATCHES_FACTORY_METHOD_PATTERN",
                            "Pattern", "pattern:FactoryMethod",
                            0.75,
                            relative,
                            List.of("python", "factory"),
                            Map.of()
                    ));
                }
                if (OBSERVER.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), "python", "Observer",
                            "Observer / pub-sub hooks in `" + module + "`.",
                            "CLASS", fileId,
                            "MATCHES_OBSERVER_PATTERN",
                            "Pattern", "pattern:Observer",
                            0.7,
                            relative,
                            List.of("python", "observer"),
                            Map.of()
                    ));
                }
                if (module.toLowerCase(Locale.ROOT).contains("repository")
                        || module.toLowerCase(Locale.ROOT).endsWith("_repo")
                        || relative.toLowerCase(Locale.ROOT).contains("repository")) {
                    facts.add(PatternFacts.of(
                            id(), "python", "Repository",
                            "Repository-style data access module `" + module + "`.",
                            "MODULE", fileId,
                            "MATCHES_REPOSITORY_PATTERN",
                            "Pattern", "pattern:Repository",
                            0.85,
                            relative,
                            List.of("python", "repository"),
                            Map.of()
                    ));
                }
                if (ABC.matcher(content).find() && ABSTRACTMETHOD.matcher(content).find()) {
                    facts.add(PatternFacts.of(
                            id(), "python", "Template Method",
                            "Template Method style ABC with abstract steps in `" + module + "`.",
                            "CLASS", fileId,
                            "SUGGESTS_TEMPLATE_METHOD",
                            "Pattern", "pattern:TemplateMethod",
                            0.65,
                            relative,
                            List.of("python", "gof", "template-method"),
                            Map.of()
                    ));
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return facts;
    }

    private static int countDecorators(String src) {
        Matcher m = Pattern.compile("(?m)^\\s*@\\w+").matcher(src);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static boolean isSkipped(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            String n = part.toString();
            if (List.of(".git", "__pycache__", ".venv", "venv", "build", "dist", ".tox").contains(n)) {
                return true;
            }
        }
        return false;
    }
}
