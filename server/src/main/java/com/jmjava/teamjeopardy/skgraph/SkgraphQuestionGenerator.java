package com.jmjava.teamjeopardy.skgraph;

import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import com.skgraph.model.IngestResult;
import com.skgraph.model.JavaTypeDecl;
import com.skgraph.model.MavenModule;
import com.skgraph.model.RelaxedDependencyEdge;
import com.skgraph.osgi.OsgiBundleMeta;
import com.skgraph.proposition.Proposition;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Builds a Jeopardy board from an skgraph {@link IngestResult}.
 * Categories mirror the dual-nature knowledge in skgraph: reactor structure,
 * dependency edges, Java AST types, DICE propositions, and OSGi wiring.
 */
@Service
public class SkgraphQuestionGenerator {

    private static final int[] VALUES = {200, 400, 600, 800, 1000};

    public Board generate(IngestResult result, String boardTitle) {
        AtomicInteger seq = new AtomicInteger(1);
        List<Category> categories = new ArrayList<>();
        categories.add(normalize(moduleMadness(result, seq)));
        categories.add(normalize(dependencyDrama(result, seq)));
        categories.add(normalize(nameThatType(result, seq)));
        categories.add(normalize(propositionPoints(result, seq)));
        categories.add(normalize(osgiOrBust(result, seq)));
        categories.add(normalize(reactorFacts(result, seq)));

        categories = categories.stream()
                .filter(c -> c.clues() != null && !c.clues().isEmpty())
                .limit(6)
                .toList();

        int files = result.getJava() != null ? result.getJava().getFiles().size() : 0;
        int types = result.getJava() != null
                ? result.getJava().getFiles().stream().mapToInt(f -> f.getTypes().size()).sum()
                : 0;
        int functions = result.getJava() != null
                ? result.getJava().getFiles().stream()
                .flatMap(f -> f.getTypes().stream())
                .mapToInt(t -> t.getMethods().size())
                .sum()
                : 0;

        String title = boardTitle != null && !boardTitle.isBlank()
                ? boardTitle
                : "skgraph Jeopardy: " + result.getContext().getRepo();

        return new Board(
                title,
                result.getContext().getRootPath(),
                categories,
                new Board.GraphDigest(
                        result.getReactor().getModules().size() + result.getPropositions().size(),
                        result.getEdges().size(),
                        files,
                        types,
                        functions
                )
        );
    }

    private Category normalize(Category category) {
        List<Clue> clues = new ArrayList<>();
        List<Clue> source = category.clues();
        for (int i = 0; i < Math.min(VALUES.length, source.size()); i++) {
            Clue c = source.get(i);
            clues.add(new Clue(
                    c.id(),
                    VALUES[i],
                    c.prompt(),
                    c.response(),
                    c.explanation(),
                    c.sourcePath(),
                    i == 3 && source.size() >= 4
            ));
        }
        return new Category(category.id(), category.title(), clues);
    }

    private Category moduleMadness(IngestResult result, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        List<MavenModule> modules = result.getReactor().getModules();

        clues.add(clue(seq, "How many Maven modules did skgraph ingest in this reactor?",
                "What is " + modules.size() + "?",
                "Count of MavenModule nodes from IngestRunner.", null));

        modules.stream()
                .max(Comparator.comparingInt(m -> m.getDependencies().size()))
                .ifPresent(m -> clues.add(clue(seq,
                        "This module declares the most direct dependencies ("
                                + m.getDependencies().size() + ").",
                        "What is " + m.getCoordinate().getArtifactId() + "?",
                        m.getCoordinate().ga() + " packaging=" + m.getPackaging(),
                        m.getRelativePath())));

        modules.stream()
                .filter(m -> "pom".equalsIgnoreCase(m.getPackaging()))
                .findFirst()
                .ifPresent(m -> clues.add(clue(seq,
                        "This reactor member uses packaging `pom`.",
                        "What is " + m.getCoordinate().getArtifactId() + "?",
                        m.getCoordinate().ga(),
                        m.getRelativePath())));

        for (MavenModule module : modules.stream().limit(4).toList()) {
            clues.add(clue(seq,
                    "Artifact `" + module.getCoordinate().getArtifactId()
                            + "` belongs to this Maven groupId.",
                    "What is " + module.getCoordinate().getGroupId() + "?",
                    module.getCoordinate().ga(),
                    module.getRelativePath()));
        }

        return new Category("cat-modules", "MODULE MADNESS", clues);
    }

    private Category dependencyDrama(IngestResult result, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        List<RelaxedDependencyEdge> edges = result.getEdges();

        clues.add(clue(seq,
                "skgraph projected this many relaxed dependency edges.",
                "What is " + edges.size() + "?",
                "RelaxedDependencyEdge count from the reactor graph.", null));

        edges.stream()
                .filter(e -> e.getReactorProvider() != null)
                .findFirst()
                .ifPresent(e -> clues.add(clue(seq,
                        "`" + e.getFromModule().getCoordinate().getArtifactId()
                                + "` depends on a reactor-provided artifact with this artifactId.",
                        "What is " + e.getDependency().getCoordinate().getArtifactId() + "?",
                        "status=" + e.getStatus() + " provider="
                                + e.getReactorProvider().getCoordinate().ga(),
                        e.getFromModule().getRelativePath())));

        Map<String, Long> depCounts = edges.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getDependency().getCoordinate().ga(),
                        LinkedHashMap::new,
                        Collectors.counting()));
        depCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(4)
                .forEach(entry -> clues.add(clue(seq,
                        "This GA coordinate appears on " + entry.getValue()
                                + " relaxed dependency edge(s).",
                        "What is " + entry.getKey() + "?",
                        "Aggregated from RelaxedDependencyEdge.dependency.coordinate",
                        null)));

        long mismatches = edges.stream()
                .filter(e -> String.valueOf(e.getStatus()).contains("MISMATCH"))
                .count();
        clues.add(clue(seq,
                "How many dependency edges are marked VERSION_MISMATCH?",
                "What is " + mismatches + "?",
                "EdgeStatus.VERSION_MISMATCH count.", null));

        return new Category("cat-deps", "DEPENDENCY DRAMA", clues);
    }

    private Category nameThatType(IngestResult result, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        if (result.getJava() == null) {
            return new Category("cat-types", "NAME THAT TYPE", clues);
        }

        List<JavaTypeDecl> types = result.getJava().getFiles().stream()
                .flatMap(f -> f.getTypes().stream())
                .sorted(Comparator.comparing(JavaTypeDecl::getQualifiedName))
                .toList();

        for (JavaTypeDecl type : types.stream().limit(8).toList()) {
            String kind = type.getKind() == null ? "type" : type.getKind();
            clues.add(clue(seq,
                    "This Java " + kind + " lives in package `" + type.getPackageName()
                            + "` under module `" + shortModule(type.getModuleId()) + "`.",
                    "What is " + type.getName() + "?",
                    type.getQualifiedName() + " @ " + type.getSourceFile(),
                    type.getSourceFile()));
        }

        types.stream()
                .filter(t -> t.getMethods() != null && !t.getMethods().isEmpty())
                .max(Comparator.comparingInt(t -> t.getMethods().size()))
                .ifPresent(t -> clues.add(0, clue(seq,
                        "This type declares the most methods (" + t.getMethods().size() + ").",
                        "What is " + t.getName() + "?",
                        t.getQualifiedName(),
                        t.getSourceFile())));

        return new Category("cat-types", "NAME THAT TYPE", clues);
    }

    private Category propositionPoints(IngestResult result, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        List<Proposition> props = result.getPropositions();

        clues.add(clue(seq,
                "How many DICE propositions did skgraph emit for this ingest?",
                "What is " + props.size() + "?",
                "Proposition list size on IngestResult.", null));

        Map<String, Long> byPredicate = props.stream()
                .collect(Collectors.groupingBy(Proposition::getPredicate, Collectors.counting()));
        byPredicate.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(4)
                .forEach(e -> clues.add(clue(seq,
                        "This predicate appears " + e.getValue() + " time(s) in the proposition set.",
                        "What is " + e.getKey() + "?",
                        "Grouped Proposition.predicate values.", null)));

        props.stream()
                .filter(p -> p.getEvidence() != null && p.getEvidence().getFile() != null)
                .findFirst()
                .ifPresent(p -> clues.add(clue(seq,
                        "A proposition with predicate `" + p.getPredicate()
                                + "` cites evidence in this file.",
                        "What is " + p.getEvidence().getFile() + "?",
                        truncate(p.getText(), 160),
                        p.getEvidence().getFile())));

        return new Category("cat-props", "PROPOSITION POINTS", clues);
    }

    private Category osgiOrBust(IngestResult result, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        if (result.getOsgi() == null || result.getOsgi().getBundles() == null
                || result.getOsgi().getBundles().isEmpty()) {
            clues.add(clue(seq,
                    "When OSGi analysis finds no bundles, the bundle count is this number.",
                    "What is 0?",
                    "OsgiAnalysis.bundles empty for this fixture/options.", null));
            clues.add(clue(seq,
                    "skgraph's OSGi extractor looks for this classic JAR metadata file.",
                    "What is META-INF/MANIFEST.MF?",
                    "ManifestParser / OsgiExtractor path.", null));
            return new Category("cat-osgi", "OSGi OR BUST", clues);
        }

        List<OsgiBundleMeta> bundles = result.getOsgi().getBundles();
        clues.add(clue(seq,
                "How many OSGi bundles did skgraph discover?",
                "What is " + bundles.size() + "?",
                "OsgiAnalysis.bundles size.", null));
        for (OsgiBundleMeta bundle : bundles.stream().limit(5).toList()) {
            String bsn = bundle.getSymbolicName() == null || bundle.getSymbolicName().isBlank()
                    ? bundle.getModulePath()
                    : bundle.getSymbolicName();
            clues.add(clue(seq,
                    "skgraph linked an OSGi bundle from `" + bundle.getSourceFile()
                            + "` with this symbolic name (or module path fallback).",
                    "What is " + bsn + "?",
                    "extractor=" + bundle.getExtractor(),
                    bundle.getSourceFile()));
        }
        return new Category("cat-osgi", "OSGi OR BUST", clues);
    }

    private Category reactorFacts(IngestResult result, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        String rootName = Path.of(result.getContext().getRootPath()).getFileName().toString();

        clues.add(clue(seq,
                "The ingested reactor root directory is named this.",
                "What is " + rootName + "?",
                result.getContext().getRootPath(), null));
        clues.add(clue(seq,
                "skgraph recorded this repository key for the ingest context.",
                "What is " + result.getContext().getRepo() + "?",
                "IngestContext.repo", null));
        clues.add(clue(seq,
                "The branch label attached to this ingest snapshot.",
                "What is " + result.getContext().getBranch() + "?",
                "IngestContext.branch", null));

        MavenModule root = result.getReactor().getRoot();
        clues.add(clue(seq,
                "The root module artifactId of the reactor.",
                "What is " + root.getCoordinate().getArtifactId() + "?",
                root.getCoordinate().ga(),
                root.getRelativePath()));

        long javaFiles = result.getJava() == null ? 0 : result.getJava().getFiles().size();
        clues.add(clue(seq,
                "Java AST extraction indexed this many source files.",
                "What is " + javaFiles + "?",
                "JavaAnalysis.files size.", null));

        return new Category("cat-reactor", "REACTOR FACTS", clues);
    }

    private Clue clue(AtomicInteger seq, String prompt, String response, String explanation, String source) {
        return new Clue("c" + seq.getAndIncrement(), 0, prompt, response, explanation, source, false);
    }

    private String shortModule(String moduleId) {
        if (moduleId == null) {
            return "?";
        }
        int idx = moduleId.lastIndexOf(':');
        return idx >= 0 ? moduleId.substring(idx + 1) : moduleId.replace("module:", "");
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max).trim() + "…";
    }
}
