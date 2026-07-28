package com.jmjava.teamjeopardy.quiz.strategy;

import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.QuestionPersona;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds Jeopardy categories from an ingested code graph for a given audience.
 * Heuristic first (deterministic, offline); {@link com.jmjava.teamjeopardy.quiz.enrich.QuestionEnricher}
 * may polish wording afterward when an OpenAI key is configured.
 */
public interface QuestionStrategy {

    String id();

    QuestionPersona persona();

    /**
     * Priority within a persona (higher first). Used when trimming the board to 6 categories.
     */
    default int priority() {
        return 0;
    }

    List<Category> build(CodeGraph graph, AtomicInteger clueSeq);
}
