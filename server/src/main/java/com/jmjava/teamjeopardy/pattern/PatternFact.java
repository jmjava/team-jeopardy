package com.jmjava.teamjeopardy.pattern;

import java.util.List;
import java.util.Map;

/**
 * DICE-style structural fact (piggybacks on skgraph Proposition shape):
 * subject — predicate — object, with confidence, tags, language, and file evidence.
 */
public record PatternFact(
        String id,
        String strategy,
        String language,
        String text,
        String subjectType,
        String subjectId,
        String predicate,
        String objectType,
        String objectId,
        double confidence,
        String evidenceFile,
        List<String> tags,
        Map<String, Object> attributes
) {
}
