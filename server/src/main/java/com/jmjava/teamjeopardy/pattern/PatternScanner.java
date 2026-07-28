package com.jmjava.teamjeopardy.pattern;

import com.jmjava.teamjeopardy.graph.CodeGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs language-scoped {@link PatternStrategy} beans (Java, Vue, Python, JS, …).
 * Same stacking role as skgraph's PropositionEmitter family, but per language.
 */
@Service
public class PatternScanner {

    private static final Logger log = LoggerFactory.getLogger(PatternScanner.class);

    private final List<PatternStrategy> strategies;

    public PatternScanner(List<PatternStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    public List<PatternFact> scan(CodeGraph graph) {
        List<PatternFact> facts = new ArrayList<>();
        for (PatternStrategy strategy : strategies) {
            if (!strategy.supports(graph)) {
                log.debug("Skipping pattern strategy {} (languages={} not present)",
                        strategy.id(), strategy.languages());
                continue;
            }
            List<PatternFact> found = strategy.find(graph);
            facts.addAll(found);
            log.info("Pattern strategy {} [{}] emitted {} facts",
                    strategy.id(),
                    String.join(",", strategy.languages()),
                    found.size());
        }
        graph.setPatternFacts(facts);
        return facts;
    }

    public Map<String, Long> countByLanguage(List<PatternFact> facts) {
        return facts.stream().collect(Collectors.groupingBy(
                PatternFact::language,
                LinkedHashMap::new,
                Collectors.counting()
        ));
    }

    public List<PatternStrategy> strategies() {
        return strategies;
    }
}
