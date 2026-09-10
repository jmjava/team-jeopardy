package com.jmjava.teamjeopardy.api;

import com.jmjava.teamjeopardy.github.GitHubPullRequestClient;
import com.jmjava.teamjeopardy.github.GitHubRepoFetcher;
import com.jmjava.teamjeopardy.github.PullRequestFact;
import com.jmjava.teamjeopardy.graph.CodeEdge;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeGraphIngester;
import com.jmjava.teamjeopardy.graph.CodeNode;
import com.jmjava.teamjeopardy.graph.ProjectKind;
import com.jmjava.teamjeopardy.jira.JiraCategoryBucket;
import com.jmjava.teamjeopardy.jira.JiraIssueFact;
import com.jmjava.teamjeopardy.jira.JiraReleaseCategorizer;
import com.jmjava.teamjeopardy.jira.JiraReleaseClient;
import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.QuestionGenerator;
import com.jmjava.teamjeopardy.quiz.QuestionHints;
import com.jmjava.teamjeopardy.quiz.strategy.JiraReleaseQuestionStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Builds Jeopardy boards from code ingest and/or recent GitHub pull requests.
 */
@Service
public class BoardFactory {

    private final CodeGraphIngester codeGraphIngester;
    private final QuestionGenerator questionGenerator;
    private final GitHubPullRequestClient pullRequestClient;
    private final GitHubRepoFetcher repoFetcher;
    private final JiraReleaseClient jiraReleaseClient;
    private final JiraReleaseCategorizer jiraReleaseCategorizer;
    private final JiraReleaseQuestionStrategy jiraReleaseQuestionStrategy;

    @Value("${team-jeopardy.sample-code-path}")
    private String sampleMavenPath;

    @Value("${team-jeopardy.sample-gradle-path:../samples/sample-gradle}")
    private String sampleGradlePath;

    @Value("${team-jeopardy.sample-vue-path:../samples/sample-vue}")
    private String sampleVuePath;

    @Value("${team-jeopardy.sample-python-path:../samples/sample-python}")
    private String samplePythonPath;

    @Value("${team-jeopardy.github.default-repo:jmjava/team-jeopardy}")
    private String defaultRepo;

    @Value("${team-jeopardy.github.pr-limit:12}")
    private int defaultPrLimit;

    public BoardFactory(
            CodeGraphIngester codeGraphIngester,
            QuestionGenerator questionGenerator,
            GitHubPullRequestClient pullRequestClient,
            GitHubRepoFetcher repoFetcher,
            JiraReleaseClient jiraReleaseClient,
            JiraReleaseCategorizer jiraReleaseCategorizer,
            JiraReleaseQuestionStrategy jiraReleaseQuestionStrategy
    ) {
        this.codeGraphIngester = codeGraphIngester;
        this.questionGenerator = questionGenerator;
        this.pullRequestClient = pullRequestClient;
        this.repoFetcher = repoFetcher;
        this.jiraReleaseClient = jiraReleaseClient;
        this.jiraReleaseCategorizer = jiraReleaseCategorizer;
        this.jiraReleaseQuestionStrategy = jiraReleaseQuestionStrategy;
    }

    public record BuiltBoard(Board board, Map<String, Object> summary) {
    }

    public BuiltBoard fromSample(String sampleType, String boardTitle) throws IOException {
        return fromSample(sampleType, boardTitle, QuestionHints.empty());
    }

    public BuiltBoard fromSample(String sampleType, String boardTitle, QuestionHints hints) throws IOException {
        String type = sampleType == null ? "maven" : sampleType.trim().toLowerCase(Locale.ROOT);
        if (isPullsType(type)) {
            return fromPullRequests(defaultRepo, defaultPrLimit, boardTitle, hints);
        }
        if (isGitHubType(type)) {
            return fromGitHub(defaultRepo, "HEAD", List.of(), false, defaultPrLimit, boardTitle, hints);
        }
        ProjectKind kind = ProjectKind.fromSampleType(sampleType);
        Path path = switch (kind) {
            case GRADLE -> Path.of(sampleGradlePath);
            case VUE, NPM -> Path.of(sampleVuePath);
            case PYTHON -> Path.of(samplePythonPath);
            default -> Path.of(sampleMavenPath);
        };
        return fromPath(path, boardTitle, kind, hints);
    }

    public BuiltBoard fromPath(Path path, String boardTitle) throws IOException {
        return fromPath(path, boardTitle, null, QuestionHints.empty());
    }

    public BuiltBoard fromPath(Path path, String boardTitle, ProjectKind forcedKind) throws IOException {
        return fromPath(path, boardTitle, forcedKind, QuestionHints.empty());
    }

    public BuiltBoard fromPath(Path path, String boardTitle, ProjectKind forcedKind, QuestionHints hints)
            throws IOException {
        CodeGraph graph = codeGraphIngester.ingest(path, forcedKind);
        Board board = questionGenerator.generate(graph, boardTitle, hints == null ? QuestionHints.empty() : hints);
        Map<String, Object> summary = summary(graph, board);
        summary.put("questionHints", graph.getQuestionHints());
        return new BuiltBoard(board, summary);
    }

    /**
     * Build a board primarily from recent GitHub PRs (coder + QA lenses).
     */
    public BuiltBoard fromPullRequests(String ownerRepo, Integer limit, String boardTitle) throws IOException {
        return fromPullRequests(ownerRepo, limit, boardTitle, QuestionHints.empty());
    }

    public BuiltBoard fromPullRequests(
            String ownerRepo,
            Integer limit,
            String boardTitle,
            QuestionHints hints
    ) throws IOException {
        String repo = (ownerRepo == null || ownerRepo.isBlank()) ? defaultRepo : ownerRepo.trim();
        int prLimit = limit == null || limit < 1 ? defaultPrLimit : Math.min(limit, 30);
        List<PullRequestFact> prs = pullRequestClient.fetchRecent(repo, prLimit);

        CodeGraph graph = new CodeGraph("github:" + repo);
        graph.setProjectKind(ProjectKind.GENERIC);
        graph.setProjectName(repo);
        graph.setPullRequests(prs);

        // Light graph nodes so digest/stats still make sense on PR-only boards.
        for (PullRequestFact pr : prs) {
            String prId = "pr:" + pr.number();
            graph.addNode(new CodeNode(
                    prId,
                    CodeNode.NodeKind.MODULE,
                    "#" + pr.number(),
                    repo + "#" + pr.number(),
                    "github",
                    pr.htmlUrl(),
                    pr.title(),
                    pr.author()
            ));
            for (String file : pr.files().stream().limit(8).toList()) {
                String fileId = "pr-file:" + file;
                graph.addNode(new CodeNode(
                        fileId,
                        CodeNode.NodeKind.FILE,
                        Path.of(file).getFileName().toString(),
                        file,
                        languageGuess(file),
                        file,
                        null,
                        null
                ));
                graph.addEdge(new CodeEdge(prId, fileId, CodeEdge.Relation.CONTAINS));
            }
        }

        String title = boardTitle != null && !boardTitle.isBlank()
                ? boardTitle
                : "PR Jeopardy: " + repo;
        Board board = questionGenerator.generate(graph, title, hints == null ? QuestionHints.empty() : hints);
        Map<String, Object> summary = summary(graph, board);
        summary.put("source", "github-pull-requests");
        summary.put("repo", repo);
        summary.put("pullRequests", prs.size());
        summary.put("authors", prs.stream().map(PullRequestFact::author).distinct().toList());
        summary.put("questionHints", graph.getQuestionHints());
        return new BuiltBoard(board, summary);
    }

    /**
     * Clone/download a GitHub repo (whole tree or selected folders), ingest into the
     * research graph, optionally attach recent PR facts.
     */
    public BuiltBoard fromGitHub(
            String ownerRepo,
            String ref,
            List<String> folders,
            boolean includePulls,
            Integer prLimit,
            String boardTitle
    ) throws IOException {
        return fromGitHub(ownerRepo, ref, folders, includePulls, prLimit, boardTitle, QuestionHints.empty());
    }

    public BuiltBoard fromGitHub(
            String ownerRepo,
            String ref,
            List<String> folders,
            boolean includePulls,
            Integer prLimit,
            String boardTitle,
            QuestionHints hints
    ) throws IOException {
        String repo = (ownerRepo == null || ownerRepo.isBlank()) ? defaultRepo : ownerRepo.trim();
        String effectiveRef = resolveRef(ref, null, null);
        GitHubRepoFetcher.MaterializedRepo materialized = repoFetcher.fetch(repo, effectiveRef, folders);

        CodeGraph graph = codeGraphIngester.ingest(materialized.root());
        graph.setProjectName(repo);
        if (includePulls) {
            int limit = prLimit == null || prLimit < 1 ? defaultPrLimit : Math.min(prLimit, 30);
            try {
                graph.setPullRequests(pullRequestClient.fetchRecent(repo, limit));
            } catch (IOException e) {
                // Code board still succeeds if PR fetch fails.
                graph.setPullRequests(List.of());
            }
        }

        String title = boardTitle != null && !boardTitle.isBlank()
                ? boardTitle
                : "GitHub Jeopardy: " + repo + (folders == null || folders.isEmpty()
                ? ""
                : " / " + String.join(",", folders));
        Board board = questionGenerator.generate(graph, title, hints == null ? QuestionHints.empty() : hints);
        Map<String, Object> summary = summary(graph, board);
        summary.put("source", "github-repo");
        summary.put("repo", repo);
        summary.put("ref", materialized.ref());
        summary.put("folders", materialized.folders());
        summary.put("fetchMethod", materialized.method());
        summary.put("materializedPath", materialized.root().toString());
        summary.put("pullRequests", graph.getPullRequests().size());
        summary.put("questionHints", graph.getQuestionHints());
        return new BuiltBoard(board, summary);
    }

    public GitHubRepoFetcher repoFetcher() {
        return repoFetcher;
    }

    /**
     * Code ingest plus optional PR enrichment when a repo is supplied.
     */
    public BuiltBoard fromPathWithPullRequests(
            Path path,
            String boardTitle,
            ProjectKind forcedKind,
            String ownerRepo,
            Integer limit
    ) throws IOException {
        return fromPathWithPullRequests(path, boardTitle, forcedKind, ownerRepo, limit, QuestionHints.empty());
    }

    public BuiltBoard fromPathWithPullRequests(
            Path path,
            String boardTitle,
            ProjectKind forcedKind,
            String ownerRepo,
            Integer limit,
            QuestionHints hints
    ) throws IOException {
        CodeGraph graph = codeGraphIngester.ingest(path, forcedKind);
        if (ownerRepo != null && !ownerRepo.isBlank()) {
            int prLimit = limit == null || limit < 1 ? defaultPrLimit : Math.min(limit, 30);
            graph.setPullRequests(pullRequestClient.fetchRecent(ownerRepo.trim(), prLimit));
        }
        Board board = questionGenerator.generate(graph, boardTitle, hints == null ? QuestionHints.empty() : hints);
        Map<String, Object> summary = summary(graph, board);
        summary.put("pullRequests", graph.getPullRequests().size());
        summary.put("repo", ownerRepo);
        summary.put("questionHints", graph.getQuestionHints());
        return new BuiltBoard(board, summary);
    }

    public Map<String, String> samplePaths() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("maven", Path.of(sampleMavenPath).toAbsolutePath().normalize().toString());
        paths.put("gradle", Path.of(sampleGradlePath).toAbsolutePath().normalize().toString());
        paths.put("vue", Path.of(sampleVuePath).toAbsolutePath().normalize().toString());
        paths.put("python", Path.of(samplePythonPath).toAbsolutePath().normalize().toString());
        paths.put("pulls", "github:" + defaultRepo);
        paths.put("github", "github:" + defaultRepo);
        paths.put("jira-multi", jiraReleaseClient.fixturePath("multi").toString());
        paths.put("jira-one-project", jiraReleaseClient.fixturePath("one-project").toString());
        return paths;
    }

    public String defaultRepo() {
        return defaultRepo;
    }

    public JiraReleaseClient jiraReleaseClient() {
        return jiraReleaseClient;
    }

    /**
     * Dedicated JIRA path: load issues → categorize → SPEC/REL clues → optional enricher.
     * Does not run {@link QuestionGenerator#generate} (that would scramble SPEC/REL).
     */
    public BuiltBoard fromJiraRelease(
            List<String> projects,
            String release,
            String extraJql,
            boolean useFixture,
            String fixtureKind,
            String boardTitle,
            QuestionHints hints
    ) throws IOException {
        QuestionHints effective = hints == null ? QuestionHints.empty() : hints;
        String version = release == null ? "" : release.trim();
        if (version.isBlank() && !useFixture) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "release (fixVersion) is required");
        }

        List<JiraIssueFact> loaded = useFixture
                ? jiraReleaseClient.loadNamedFixture(resolveFixtureKind(projects, fixtureKind))
                : jiraReleaseClient.search(projects, version, extraJql);
        List<JiraIssueFact> issues = filterProjects(loaded, projects);
        if (issues.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No JIRA issues matched this release/projects"
            );
        }
        if (version.isBlank()) {
            version = issues.stream()
                    .map(JiraIssueFact::fixVersion)
                    .filter(v -> v != null && !v.isBlank())
                    .findFirst()
                    .orElse("release");
        }

        List<JiraCategoryBucket> buckets = jiraReleaseCategorizer.categorize(issues);
        List<Category> categories = jiraReleaseQuestionStrategy.build(buckets, new AtomicInteger(1));

        CodeGraph graph = new CodeGraph("jira:" + version);
        graph.setProjectKind(ProjectKind.GENERIC);
        graph.setProjectName("JIRA " + version);
        if (!effective.isBlank()) {
            graph.setQuestionHints(effective.combined());
        }
        categories = questionGenerator.polish(graph, categories, effective);

        String title = boardTitle != null && !boardTitle.isBlank()
                ? boardTitle
                : "REL Jeopardy: " + version;
        Board board = new Board(
                title,
                useFixture ? jiraReleaseClient.sampleHost() : "jira:" + version,
                categories,
                new Board.GraphDigest(issues.size(), 0, 0, 0, 0)
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("engine", "team-jeopardy-ingest");
        summary.put("derivedFrom", "JIRA release fields (not a code graph)");
        summary.put("source", useFixture ? "jira-fixture" : "jira");
        summary.put("sourceKind", "jira");
        summary.put("release", version);
        summary.put("projects", JiraReleaseClient.normalizeProjects(projects));
        summary.put("issues", issues.size());
        summary.put("fixture", useFixture);
        summary.put("jiraConfigured", jiraReleaseClient.isConfigured());
        summary.put("categories", board.categories().size());
        summary.put("categoryTitles", board.categories().stream().map(Category::title).toList());
        summary.put("bucketDimensions", buckets.stream().map(JiraCategoryBucket::dimension).toList());
        summary.put("questionHints", graph.getQuestionHints());
        return new BuiltBoard(board, summary);
    }

    static String resolveFixtureKind(List<String> projects, String fixtureKind) {
        if (fixtureKind != null && !fixtureKind.isBlank()) {
            return fixtureKind.trim();
        }
        List<String> keys = JiraReleaseClient.normalizeProjects(projects);
        return keys.size() <= 1 ? "one-project" : "multi";
    }

    static List<JiraIssueFact> filterProjects(List<JiraIssueFact> issues, List<String> projects) {
        List<String> keys = JiraReleaseClient.normalizeProjects(projects).stream()
                .map(k -> k.toUpperCase(Locale.ROOT))
                .toList();
        if (keys.isEmpty() || issues == null) {
            return issues == null ? List.of() : issues;
        }
        List<JiraIssueFact> filtered = new ArrayList<>();
        for (JiraIssueFact issue : issues) {
            if (keys.contains(issue.projectKey().toUpperCase(Locale.ROOT))) {
                filtered.add(issue);
            }
        }
        return filtered.isEmpty() ? issues : filtered;
    }

    private Map<String, Object> summary(CodeGraph graph, Board board) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("engine", "team-jeopardy-ingest");
        summary.put("derivedFrom", "code graph + optional GitHub pull requests");
        summary.put("projectKind", graph.getProjectKind().name());
        summary.put("projectName", graph.getProjectName());
        summary.put("rootPath", graph.getRootPath());
        summary.put("nodes", graph.stats().nodeCount());
        summary.put("edges", graph.stats().edgeCount());
        summary.put("modules", graph.nodesOfKind(CodeNode.NodeKind.MODULE).size());
        summary.put("dependencies", graph.nodesOfKind(CodeNode.NodeKind.DEPENDENCY).size());
        summary.put("components", graph.nodesOfKind(CodeNode.NodeKind.COMPONENT).size());
        summary.put("extendsEdges", graph.edgesOf(CodeEdge.Relation.EXTENDS).size());
        summary.put("implementsEdges", graph.edgesOf(CodeEdge.Relation.IMPLEMENTS).size());
        summary.put("vueUsesEdges", graph.edgesOf(CodeEdge.Relation.USES).size());
        summary.put("patternFacts", graph.getPatternFacts().size());
        summary.put("pullRequests", graph.getPullRequests().size());
        summary.put("patterns", graph.getPatternFacts().stream()
                .map(f -> String.valueOf(f.attributes().getOrDefault("pattern", f.predicate())))
                .distinct()
                .toList());
        summary.put("patternsByLanguage", graph.getPatternFacts().stream()
                .collect(Collectors.groupingBy(
                        f -> f.language() == null ? "unknown" : f.language(),
                        LinkedHashMap::new,
                        Collectors.counting())));
        summary.put("categories", board.categories().size());
        summary.put("categoryTitles", board.categories().stream().map(c -> c.title()).toList());
        return summary;
    }

    static boolean isPullsType(String sampleType) {
        if (sampleType == null) {
            return false;
        }
        String t = sampleType.trim().toLowerCase(Locale.ROOT);
        return t.equals("pulls") || t.equals("prs") || t.equals("pull-requests") || t.equals("github-prs");
    }

    static boolean isGitHubType(String sampleType) {
        if (sampleType == null) {
            return false;
        }
        String t = sampleType.trim().toLowerCase(Locale.ROOT);
        return t.equals("github") || t.equals("gh") || t.equals("repo") || t.equals("repository");
    }

    static String resolveRef(String ref, String branch, String commitSha) {
        if (ref != null && !ref.isBlank()) {
            return ref.trim();
        }
        if (commitSha != null && !commitSha.isBlank()) {
            return commitSha.trim();
        }
        if (branch != null && !branch.isBlank()) {
            return branch.trim();
        }
        return "HEAD";
    }

    private static String languageGuess(String file) {
        String lower = file.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".vue")) return "vue";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs")) return "javascript";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml";
        if (lower.endsWith(".md")) return "markdown";
        return "text";
    }
}
