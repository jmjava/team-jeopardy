package com.jmjava.teamjeopardy.graph;

/**
 * Detected root project shape used to choose ingest + question strategies.
 */
public enum ProjectKind {
    MAVEN,
    GRADLE,
    VUE,
    NPM,
    PYTHON,
    GENERIC;

    public static ProjectKind fromSampleType(String sampleType) {
        if (sampleType == null || sampleType.isBlank()) {
            return MAVEN;
        }
        return switch (sampleType.trim().toLowerCase()) {
            case "gradle", "gradle-java", "gradle-maven" -> GRADLE;
            case "vue", "frontend", "npm-vue" -> VUE;
            case "npm", "node" -> NPM;
            case "python", "py" -> PYTHON;
            case "maven", "skgraph", "java-maven" -> MAVEN;
            default -> MAVEN;
        };
    }
}
