package com.jmjava.teamjeopardy.graph;

import com.jmjava.teamjeopardy.github.PullRequestFact;
import com.jmjava.teamjeopardy.pattern.PatternFact;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Lightweight in-memory code knowledge graph produced by repository ingestion.
 * Inspired by code-graph / skgraph-style pipelines: parse sources into entities
 * and relations, then generate quiz content from structural facts.
 */
public class CodeGraph {

    private final String rootPath;
    private ProjectKind projectKind = ProjectKind.GENERIC;
    private String projectName;
    private final Map<String, CodeNode> nodes = new LinkedHashMap<>();
    private final List<CodeEdge> edges = new ArrayList<>();
    private List<PatternFact> patternFacts = List.of();
    private List<PullRequestFact> pullRequests = List.of();
    /** Moderator guidance for question emphasis on this board. */
    private String questionHints = "";

    public CodeGraph(String rootPath) {
        this.rootPath = rootPath;
    }

    public String getRootPath() {
        return rootPath;
    }

    public ProjectKind getProjectKind() {
        return projectKind;
    }

    public void setProjectKind(ProjectKind projectKind) {
        this.projectKind = projectKind == null ? ProjectKind.GENERIC : projectKind;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public void addNode(CodeNode node) {
        nodes.putIfAbsent(node.id(), node);
    }

    public void addEdge(CodeEdge edge) {
        edges.add(edge);
    }

    public List<PatternFact> getPatternFacts() {
        return patternFacts;
    }

    public void setPatternFacts(List<PatternFact> patternFacts) {
        this.patternFacts = patternFacts == null ? List.of() : List.copyOf(patternFacts);
    }

    public List<PullRequestFact> getPullRequests() {
        return pullRequests;
    }

    public void setPullRequests(List<PullRequestFact> pullRequests) {
        this.pullRequests = pullRequests == null ? List.of() : List.copyOf(pullRequests);
    }

    public String getQuestionHints() {
        return questionHints;
    }

    public void setQuestionHints(String questionHints) {
        this.questionHints = questionHints == null ? "" : questionHints.trim();
    }

    public Collection<CodeNode> nodes() {
        return nodes.values();
    }

    public List<CodeEdge> edges() {
        return List.copyOf(edges);
    }

    public Optional<CodeNode> findById(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public List<CodeNode> nodesOfKind(CodeNode.NodeKind kind) {
        return nodes.values().stream()
                .filter(n -> n.kind() == kind)
                .collect(Collectors.toList());
    }

    public List<CodeEdge> edgesFrom(String nodeId) {
        return edges.stream().filter(e -> e.fromId().equals(nodeId)).toList();
    }

    public List<CodeEdge> edgesOf(CodeEdge.Relation relation) {
        return edges.stream().filter(e -> e.relation() == relation).toList();
    }

    public GraphStats stats() {
        Map<CodeNode.NodeKind, Long> byKind = nodes.values().stream()
                .collect(Collectors.groupingBy(CodeNode::kind, Collectors.counting()));
        Map<CodeEdge.Relation, Long> byRelation = edges.stream()
                .collect(Collectors.groupingBy(CodeEdge::relation, Collectors.counting()));
        return new GraphStats(nodes.size(), edges.size(), byKind, byRelation);
    }

    public record GraphStats(
            int nodeCount,
            int edgeCount,
            Map<CodeNode.NodeKind, Long> nodesByKind,
            Map<CodeEdge.Relation, Long> edgesByRelation
    ) {
    }
}
