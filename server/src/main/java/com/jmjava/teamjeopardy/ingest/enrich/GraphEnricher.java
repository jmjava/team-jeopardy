package com.jmjava.teamjeopardy.ingest.enrich;

import com.jmjava.teamjeopardy.graph.CodeGraph;

import java.nio.file.Path;

/**
 * Post-pass over an ingested {@link CodeGraph} to add structural relationships
 * (Java type hierarchies, Vue component trees, etc.).
 */
public interface GraphEnricher {

    String name();

    /**
     * @return number of relationship edges added
     */
    int enrich(CodeGraph graph, Path root);
}
