package com.jmjava.teamjeopardy.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Materializes a GitHub repository (whole tree or selected folders) into a temp workspace
 * for code-graph ingest. Prefers shallow git clone + sparse-checkout; falls back to zipball.
 */
@Component
public class GitHubRepoFetcher {

    private static final Logger log = LoggerFactory.getLogger(GitHubRepoFetcher.class);

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String token;
    private final String apiBase;
    private final Duration timeout;
    private final Path cacheRoot;

    public GitHubRepoFetcher(
            ObjectMapper mapper,
            @Value("${team-jeopardy.github.token:${GITHUB_TOKEN:}}") String token,
            @Value("${team-jeopardy.github.api-base:https://api.github.com}") String apiBase,
            @Value("${team-jeopardy.github.timeout-seconds:25}") int timeoutSeconds,
            @Value("${team-jeopardy.github.workspace:${java.io.tmpdir}/team-jeopardy-github}") String workspace
    ) throws IOException {
        this.mapper = mapper;
        String resolved = token == null ? "" : token.trim();
        if (resolved.isBlank()) {
            String gh = System.getenv("GH_TOKEN");
            resolved = gh == null ? "" : gh.trim();
        }
        this.token = resolved;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.timeout = Duration.ofSeconds(Math.max(8, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.cacheRoot = Path.of(workspace).toAbsolutePath().normalize();
        Files.createDirectories(this.cacheRoot);
    }

    public record MaterializedRepo(
            String ownerRepo,
            String ref,
            List<String> folders,
            Path root,
            String method
    ) {
    }

    public record RepoFolder(String path, String type) {
    }

    public MaterializedRepo fetch(String ownerRepo, String ref, List<String> folders) throws IOException {
        GitHubPullRequestClient.RepoRef repo = GitHubPullRequestClient.RepoRef.parse(ownerRepo);
        String effectiveRef = (ref == null || ref.isBlank()) ? "HEAD" : ref.trim();
        List<String> normalizedFolders = normalizeFolders(folders);

        Path target = cacheRoot.resolve(
                safe(repo.owner()) + "_" + safe(repo.name()) + "_" + safe(effectiveRef)
                        + "_" + Integer.toHexString(normalizedFolders.hashCode())
        );
        deleteRecursive(target);
        Files.createDirectories(target.getParent());

        try {
            shallowClone(repo, effectiveRef, normalizedFolders, target);
            return new MaterializedRepo(repo.fullName(), effectiveRef, normalizedFolders, target, "git-sparse");
        } catch (Exception gitError) {
            log.warn("Git sparse clone failed for {}@{} ({}), trying zipball",
                    repo.fullName(), effectiveRef, gitError.toString());
            deleteRecursive(target);
            Files.createDirectories(target);
            downloadZipball(repo, effectiveRef, normalizedFolders, target);
            return new MaterializedRepo(repo.fullName(), effectiveRef, normalizedFolders, target, "zipball");
        }
    }

    /**
     * Lists directories (and optionally files) under a path using the GitHub contents API.
     */
    public List<RepoFolder> listFolders(String ownerRepo, String ref, String path) throws IOException {
        GitHubPullRequestClient.RepoRef repo = GitHubPullRequestClient.RepoRef.parse(ownerRepo);
        String effectiveRef = (ref == null || ref.isBlank()) ? "" : ref.trim();
        String contentsPath = (path == null || path.isBlank()) ? "" : "/" + trimSlashes(path);
        String url = "/repos/" + enc(repo.owner()) + "/" + enc(repo.name()) + "/contents" + contentsPath;
        if (!effectiveRef.isBlank()) {
            url += "?ref=" + enc(effectiveRef);
        }
        JsonNode node = getJson(url);
        List<RepoFolder> folders = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String type = item.path("type").asText("");
                String itemPath = item.path("path").asText("");
                if ("dir".equals(type) && !itemPath.isBlank()) {
                    folders.add(new RepoFolder(itemPath, "dir"));
                }
            }
        }
        folders.sort(Comparator.comparing(RepoFolder::path));
        return folders;
    }

    /**
     * Recursive directory listing (capped) via git tree API — useful for admin folder pickers.
     */
    public List<RepoFolder> listDirectoriesRecursive(String ownerRepo, String ref, int limit) throws IOException {
        GitHubPullRequestClient.RepoRef repo = GitHubPullRequestClient.RepoRef.parse(ownerRepo);
        String effectiveRef = (ref == null || ref.isBlank()) ? "HEAD" : ref.trim();
        JsonNode refNode = getJson("/repos/" + enc(repo.owner()) + "/" + enc(repo.name())
                + "/commits/" + enc(effectiveRef));
        String sha = refNode.path("commit").path("tree").path("sha").asText(null);
        if (sha == null || sha.isBlank()) {
            sha = refNode.path("sha").asText("");
        }
        JsonNode tree = getJson("/repos/" + enc(repo.owner()) + "/" + enc(repo.name())
                + "/git/trees/" + enc(sha) + "?recursive=1");
        List<RepoFolder> dirs = new ArrayList<>();
        JsonNode items = tree.path("tree");
        if (items.isArray()) {
            for (JsonNode item : items) {
                if (!"tree".equals(item.path("type").asText())) {
                    continue;
                }
                String path = item.path("path").asText("");
                if (path.isBlank() || path.contains("/.")) {
                    continue;
                }
                dirs.add(new RepoFolder(path, "dir"));
                if (dirs.size() >= limit) {
                    break;
                }
            }
        }
        return dirs;
    }

    private void shallowClone(
            GitHubPullRequestClient.RepoRef repo,
            String ref,
            List<String> folders,
            Path target
    ) throws IOException, InterruptedException {
        String cloneUrl = cloneUrl(repo);
        List<String> cloneCmd = List.of(
                "git", "clone",
                "--depth", "1",
                "--no-tags",
                "--branch", refEqualsHead(ref) ? "HEAD" : ref,
                cloneUrl,
                target.toString()
        );
        // git clone --branch HEAD is invalid; use default branch then checkout when HEAD.
        if (refEqualsHead(ref)) {
            cloneCmd = List.of(
                    "git", "clone",
                    "--depth", "1",
                    "--no-tags",
                    cloneUrl,
                    target.toString()
            );
        }
        run(cloneCmd, cacheRoot, 120);

        if (!folders.isEmpty()) {
            run(List.of("git", "sparse-checkout", "init", "--cone"), target, 30);
            List<String> sparse = new ArrayList<>();
            sparse.add("git");
            sparse.add("sparse-checkout");
            sparse.add("set");
            sparse.addAll(folders);
            run(sparse, target, 30);
        }
        log.info("Cloned {}@{} into {} (folders={})", repo.fullName(), ref, target, folders);
    }

    private void downloadZipball(
            GitHubPullRequestClient.RepoRef repo,
            String ref,
            List<String> folders,
            Path target
    ) throws IOException {
        String url = apiBase + "/repos/" + enc(repo.owner()) + "/" + enc(repo.name())
                + "/zipball/" + enc(refEqualsHead(ref) ? "HEAD" : ref);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "team-jeopardy")
                .GET();
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Zipball download failed HTTP " + response.statusCode());
            }
            Path staging = Files.createTempDirectory(cacheRoot, "zip-");
            try (InputStream in = response.body(); ZipInputStream zis = new ZipInputStream(in)) {
                ZipEntry entry;
                String rootPrefix = null;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (rootPrefix == null) {
                        int slash = name.indexOf('/');
                        rootPrefix = slash >= 0 ? name.substring(0, slash + 1) : "";
                    }
                    String relative = rootPrefix.isEmpty() ? name : name.substring(rootPrefix.length());
                    if (relative.isBlank()) {
                        continue;
                    }
                    if (!folders.isEmpty() && !matchesFolder(relative, folders)) {
                        continue;
                    }
                    Path out = target.resolve(relative).normalize();
                    if (!out.startsWith(target)) {
                        continue;
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } finally {
                deleteRecursive(staging);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Zipball download interrupted", e);
        }
        log.info("Downloaded zipball {}@{} into {} (folders={})", repo.fullName(), ref, target, folders);
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
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("GitHub API HTTP " + response.statusCode() + " for " + path);
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("GitHub request interrupted", e);
        }
    }

    private String cloneUrl(GitHubPullRequestClient.RepoRef repo) {
        if (!token.isBlank()) {
            return "https://x-access-token:" + token + "@github.com/"
                    + repo.owner() + "/" + repo.name() + ".git";
        }
        return "https://github.com/" + repo.owner() + "/" + repo.name() + ".git";
    }

    private static void run(List<String> command, Path cwd, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + command + "\n" + output);
        }
        if (process.exitValue() != 0) {
            throw new IOException("Command failed (" + process.exitValue() + "): "
                    + command + "\n" + output);
        }
    }

    private static List<String> normalizeFolders(List<String> folders) {
        if (folders == null || folders.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String folder : folders) {
            if (folder == null) {
                continue;
            }
            String cleaned = trimSlashes(folder.replace('\\', '/'));
            if (!cleaned.isBlank() && !cleaned.equals(".")) {
                out.add(cleaned);
            }
        }
        return List.copyOf(out);
    }

    private static boolean matchesFolder(String relative, List<String> folders) {
        String path = relative.replace('\\', '/');
        for (String folder : folders) {
            if (path.equals(folder) || path.startsWith(folder + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean refEqualsHead(String ref) {
        return ref == null || ref.isBlank() || "HEAD".equalsIgnoreCase(ref) || "head".equals(ref);
    }

    private static String trimSlashes(String value) {
        String v = value.trim();
        while (v.startsWith("/")) {
            v = v.substring(1);
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static String safe(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static void deleteRecursive(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
