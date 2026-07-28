package com.jmjava.teamjeopardy.graph;

public record CodeEdge(
        String fromId,
        String toId,
        Relation relation
) {
    public enum Relation {
        CONTAINS, DEFINES, CALLS, IMPORTS, EXTENDS, IMPLEMENTS, BELONGS_TO
    }
}
