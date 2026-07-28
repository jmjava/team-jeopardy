package com.jmjava.teamjeopardy.graph;

import java.util.Objects;

public record CodeNode(
        String id,
        NodeKind kind,
        String name,
        String qualifiedName,
        String language,
        String filePath,
        String signature,
        String snippet
) {
    public enum NodeKind {
        FILE,
        PACKAGE,
        CLASS,
        INTERFACE,
        METHOD,
        FUNCTION,
        IMPORT,
        COMPONENT,
        DEPENDENCY,
        SCRIPT,
        ROUTE,
        MODULE,
        PLUGIN
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CodeNode that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
