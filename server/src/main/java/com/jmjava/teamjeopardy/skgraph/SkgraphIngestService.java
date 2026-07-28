package com.jmjava.teamjeopardy.skgraph;

import com.skgraph.maven.IngestRunner;
import com.skgraph.model.IngestOptions;
import com.skgraph.model.IngestResult;
import com.skgraph.neo4j.GraphStore;
import com.skgraph.neo4j.InMemoryGraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin adapter over {@code skgraph-core} ingest. Keeps the latest IngestResult
 * per room (and a shared sample) so Jeopardy boards can be generated from the
 * same Maven/OSGi/Java propositions that power skgraph Ask.
 */
@Service
public class SkgraphIngestService {

    private static final Logger log = LoggerFactory.getLogger(SkgraphIngestService.class);

    private final IngestRunner ingestRunner = new IngestRunner();
    private final GraphStore graphStore = new InMemoryGraphStore();
    private final Map<String, IngestResult> byKey = new ConcurrentHashMap<>();

    @Value("${team-jeopardy.sample-code-path}")
    private String sampleCodePath;

    @Value("${team-jeopardy.skgraph.default-repo:sample-reactor}")
    private String defaultRepo;

    @Value("${team-jeopardy.skgraph.default-branch:release/7.1}")
    private String defaultBranch;

    public IngestResult ingestPath(Path root, String repo, String branch, String commitSha) {
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("Not a directory: " + absolute);
        }
        if (!Files.exists(absolute.resolve("pom.xml"))) {
            throw new IllegalArgumentException(
                    "skgraph ingest expects a Maven reactor root with pom.xml: " + absolute);
        }

        String effectiveRepo = blankTo(repo, defaultRepo);
        String effectiveBranch = blankTo(branch, defaultBranch);
        String effectiveCommit = blankTo(commitSha, "local");

        log.info("skgraph ingest root={} repo={} branch={}", absolute, effectiveRepo, effectiveBranch);
        IngestResult result = ingestRunner.ingest(
                absolute,
                effectiveRepo,
                effectiveBranch,
                effectiveCommit,
                "jeopardy-" + System.currentTimeMillis(),
                new IngestOptions(
                        true,  // enableOsgi
                        false, // enableM2Inspection (avoid host m2 dependency in demos)
                        false, // enableMavenEnrichment
                        true,  // enableJavaAst
                        null,
                        true,
                        null,
                        false,
                        true,  // forceFull
                        null
                ),
                null
        );
        graphStore.project(result);
        byKey.put(key(effectiveRepo, effectiveBranch), result);
        return result;
    }

    public IngestResult ingestSample() {
        return ingestPath(Path.of(sampleCodePath), defaultRepo, defaultBranch, "sample");
    }

    public Optional<IngestResult> latest(String repo, String branch) {
        return Optional.ofNullable(byKey.get(key(
                blankTo(repo, defaultRepo),
                blankTo(branch, defaultBranch)
        )));
    }

    public GraphStore graphStore() {
        return graphStore;
    }

    public String sampleCodePath() {
        return Path.of(sampleCodePath).toAbsolutePath().normalize().toString();
    }

    private static String key(String repo, String branch) {
        return repo + "@" + branch;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
