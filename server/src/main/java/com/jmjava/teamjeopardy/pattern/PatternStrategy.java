package com.jmjava.teamjeopardy.pattern;

import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Language-scoped pattern finder. Each implementation owns idioms for one (or a small set of)
 * languages — Java GoF/Spring, Vue composition, Python ABCs/decorators, JS modules, etc.
 * Output shape piggybacks on skgraph DICE propositions via {@link PatternFact}.
 */
public interface PatternStrategy {

    String id();

    /**
     * Human label for boards / UI.
     */
    String displayName();

    /**
     * Languages this strategy understands (e.g. {@code java}, {@code vue}, {@code python}).
     */
    Set<String> languages();

    /**
     * Default: run when the graph contains at least one source file in {@link #languages()}.
     */
    default boolean supports(CodeGraph graph) {
        Set<String> langs = languages();
        return graph.nodes().stream()
                .filter(n -> n.kind() == CodeNode.NodeKind.FILE
                        || n.kind() == CodeNode.NodeKind.CLASS
                        || n.kind() == CodeNode.NodeKind.INTERFACE
                        || n.kind() == CodeNode.NodeKind.COMPONENT
                        || n.kind() == CodeNode.NodeKind.FUNCTION)
                .map(CodeNode::language)
                .filter(l -> l != null && !l.isBlank())
                .map(l -> l.toLowerCase(Locale.ROOT))
                .anyMatch(langs::contains);
    }

    List<PatternFact> find(CodeGraph graph);
}
