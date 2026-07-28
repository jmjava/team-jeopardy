package com.jmjava.teamjeopardy.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fetches recent pull requests from the GitHub REST API.
 * Token from {@code team-jeopardy.github.token} / {@code GITHUB_TOKEN} / {@code GH_TOKEN}.
 */
@Component
public class GitHubPullRequestClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestClient.class);

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String token;
    private final String apiBase;
    private final Duration timeout;

    public GitHubPullRequestClient(
            ObjectMapper mapper,
            @Value("${team-jeopardy.github.token:${GITHUB_TOKEN:}}") String token,
            @Value("${team-jeopardy.github.api-base:https://api.github.com}") String apiBase,
            @Value("${team-jeopardy.github.timeout-seconds:25}") int timeoutSeconds
    ) {
        this.mapper = mapper;
        String resolved = token == null ? "" : token.trim();
        if (resolved.isBlank()) {
            String gh = System.getenv("GH_TOKEN");
            resolved = gh == null ? "" : gh.trim();
        }
        this.token = resolved;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public boolean hasToken() {
        return !token.isBlank();
    }

    public List<PullRequestFact> fetchRecent(String ownerRepo, int limit) throws IOException {
        RepoRef repo = RepoRef.parse(ownerRepo);
        int perPage = Math.min(Math.max(limit, 1), 30);
        String path = "/repos/" + enc(repo.owner()) + "/" + enc(repo.name())
                + "/pulls?state=all&sort=updated&direction=desc&per_page=" + perPage;
        JsonNode array = getJson(path);
        if (!array.isArray()) {
            throw new IOException("Unexpected GitHub pulls response");
        }

        List<PullRequestFact> facts = new ArrayList<>();
        int count = 0;
        for (JsonNode pr : array) {
            if (count >= limit) {
                break;
            }
            int number = pr.path("number").asInt();
            List<String> files = List.of();
            int additions = pr.path("additions").asInt(0);
            int deletions = pr.path("deletions").asInt(0);
            int changedFiles = pr.path("changed_files").asInt(0);
            // List endpoint often omits file stats — enrich top PRs.
            if (count < Math.min(limit, 12)) {
                try {
                    FileStats stats = fetchFiles(repo, number);
                    files = stats.files();
                    if (additions == 0 && deletions == 0) {
                        additions = stats.additions();
                        deletions = stats.deletions();
                    }
                    if (changedFiles == 0) {
                        changedFiles = stats.files().size();
                    }
                } catch (Exception e) {
                    log.debug("Could not load files for PR #{}: {}", number, e.toString());
                }
            }
            facts.add(new PullRequestFact(
                    number,
                    pr.path("title").asText(""),
                    pr.path("user").path("login").asText("unknown"),
                    pr.path("state").asText("open"),
                    pr.path("merged_at").asText(null) != null && !pr.path("merged_at").isNull(),
                    truncate(pr.path("body").asText(""), 600),
                    labels(pr.path("labels")),
                    files,
                    additions,
                    deletions,
                    changedFiles,
                    pr.path("html_url").asText(""),
                    parseInstant(pr.path("updated_at").asText(null)),
                    pr.path("base").path("ref").asText(""),
                    pr.path("head").path("ref").asText("")
            ));
            count++;
        }
        log.info("Fetched {} pull requests from {}", facts.size(), repo.fullName());
        return facts;
    }

    private FileStats fetchFiles(RepoRef repo, int number) throws IOException, InterruptedException {
        String path = "/repos/" + enc(repo.owner()) + "/" + enc(repo.name())
                + "/pulls/" + number + "/files?per_page=30";
        JsonNode array = getJson(path);
        List<String> files = new ArrayList<>();
        int additions = 0;
        int deletions = 0;
        if (array.isArray()) {
            for (JsonNode file : array) {
                files.add(file.path("filename").asText(""));
                additions += file.path("additions").asInt(0);
                deletions += file.path("deletions").asInt(0);
            }
        }
        return new FileStats(files.stream().filter(f -> f != null && !f.isBlank()).toList(), additions, deletions);
    }

    private JsonNode getJson(String path) throws IOException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + path))
                    .timeout(timeout)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "team-jeopardy")
                    .GET();
            if (!token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IOException("GitHub API auth failed (" + response.statusCode()
                        + "). Set GITHUB_TOKEN / team-jeopardy.github.token.");
            }
            if (response.statusCode() == 404) {
                throw new IOException("GitHub repo or resource not found: " + path);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("GitHub API HTTP " + response.statusCode() + ": "
                        + truncate(response.body(), 180));
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("GitHub request interrupted", e);
        }
    }

    private static List<String> labels(JsonNode labels) {
        List<String> out = new ArrayList<>();
        if (labels != null && labels.isArray()) {
            for (JsonNode label : labels) {
                String name = label.path("name").asText("");
                if (!name.isBlank()) {
                    out.add(name);
                }
            }
        }
        return List.copyOf(out);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\r', ' ').trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max).trim() + "…";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record RepoRef(String owner, String name) {
        public String fullName() {
            return owner + "/" + name;
        }

        public static RepoRef parse(String ownerRepo) {
            if (ownerRepo == null || ownerRepo.isBlank()) {
                throw new IllegalArgumentException("repo is required (owner/name)");
            }
            String cleaned = ownerRepo.trim()
                    .replace("https://github.com/", "")
                    .replace("http://github.com/", "")
                    .replaceAll("\\.git$", "");
            String[] parts = cleaned.split("/");
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("repo must look like owner/name");
            }
            return new RepoRef(parts[0], parts[1].split("/")[0]);
        }
    }

    private record FileStats(List<String> files, int additions, int deletions) {
    }
}
