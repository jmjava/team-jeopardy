package com.jmjava.teamjeopardy.jira;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Read-only JIRA search for a release ({@code fixVersion}).
 * Cloud v3 or Server/DC v2. Credentials from env / {@code team-jeopardy.jira.*} only.
 * The API token is never copied onto {@link JiraIssueFact}s or sent to OpenAI.
 */
@Component
public class JiraReleaseClient {

    private static final Logger log = LoggerFactory.getLogger(JiraReleaseClient.class);
    private static final String SAMPLE_HOST = "https://jira.example";
    private static final List<String> SEARCH_FIELDS = List.of(
            "summary", "description", "issuetype", "status", "labels",
            "components", "fixVersions", "project", "parent",
            "customfield_10014", "customfield_10011", "customfield_10015"
    );

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String baseUrl;
    private final String email;
    private final String apiToken;
    private final Duration timeout;
    private final int maxIssues;
    private final String fixtureMultiPath;
    private final String fixtureOneProjectPath;
    private final String acceptanceField;
    private final String epicNameField;

    public JiraReleaseClient(
            ObjectMapper mapper,
            @Value("${team-jeopardy.jira.base-url:}") String baseUrl,
            @Value("${team-jeopardy.jira.email:}") String email,
            @Value("${team-jeopardy.jira.api-token:}") String apiToken,
            @Value("${team-jeopardy.jira.timeout-seconds:30}") int timeoutSeconds,
            @Value("${team-jeopardy.jira.max-issues:200}") int maxIssues,
            @Value("${team-jeopardy.jira.fixture-multi-path:../samples/jira-release-multi-project.json}")
            String fixtureMultiPath,
            @Value("${team-jeopardy.jira.fixture-one-project-path:../samples/jira-release-one-project.json}")
            String fixtureOneProjectPath,
            @Value("${team-jeopardy.jira.acceptance-field:}") String acceptanceField,
            @Value("${team-jeopardy.jira.epic-name-field:}") String epicNameField
    ) {
        this.mapper = mapper;
        this.baseUrl = trimSlash(baseUrl);
        this.email = email == null ? "" : email.trim();
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        this.maxIssues = Math.min(Math.max(maxIssues, 10), 500);
        this.fixtureMultiPath = fixtureMultiPath;
        this.fixtureOneProjectPath = fixtureOneProjectPath;
        this.acceptanceField = acceptanceField == null ? "" : acceptanceField.trim();
        this.epicNameField = epicNameField == null ? "" : epicNameField.trim();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && !email.isBlank() && !apiToken.isBlank();
    }

    public String sampleHost() {
        return SAMPLE_HOST;
    }

    public Path fixturePath(String kind) {
        String name = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        boolean oneProject = name.equals("one") || name.equals("one-project") || name.equals("single");
        return resolveExisting(oneProject ? fixtureOneProjectPath : fixtureMultiPath);
    }

    public List<JiraIssueFact> loadNamedFixture(String kind) throws IOException {
        return loadFixture(fixturePath(kind));
    }

    public List<JiraIssueFact> loadFixture(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("JIRA fixture not found: " + path);
        }
        JsonNode root = mapper.readTree(Files.readString(path));
        return parseIssues(root);
    }

    /**
     * Live read-only JQL search. Never writes issues. Token stays on this client.
     */
    public List<JiraIssueFact> search(List<String> projects, String release, String extraJql) throws IOException {
        if (!isConfigured()) {
            throw new IOException("JIRA is not configured. Set JIRA_BASE_URL, JIRA_EMAIL, and JIRA_API_TOKEN, "
                    + "or POST ingest-jira with useFixture=true.");
        }
        String jql = buildJql(projects, release, extraJql);
        if (isCloud()) {
            try {
                return searchCloud(jql);
            } catch (IOException e) {
                log.debug("Cloud enhanced search failed, trying legacy /search: {}", e.toString());
                return searchLegacy(jql, "/rest/api/3/search");
            }
        }
        return searchLegacy(jql, "/rest/api/2/search");
    }

    public static String buildJql(List<String> projects, String release, String extraJql) {
        if (release == null || release.isBlank()) {
            throw new IllegalArgumentException("release (fixVersion) is required");
        }
        StringBuilder jql = new StringBuilder();
        List<String> keys = normalizeProjects(projects);
        if (!keys.isEmpty()) {
            jql.append("project in (")
                    .append(String.join(", ", keys.stream().map(JiraReleaseClient::quoteJql).toList()))
                    .append(") AND ");
        }
        jql.append("fixVersion = ").append(quoteJql(release.trim()));
        if (extraJql != null && !extraJql.isBlank()) {
            jql.append(" AND (").append(extraJql.trim()).append(')');
        }
        jql.append(" ORDER BY key ASC");
        return jql.toString();
    }

    public List<JiraIssueFact> parseIssues(JsonNode root) {
        if (root == null || root.isNull()) {
            return List.of();
        }
        JsonNode array = root.get("issues");
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("JIRA payload must contain an issues array");
        }
        List<JiraIssueFact> facts = new ArrayList<>();
        for (JsonNode issue : array) {
            if (issue == null || issue.isNull()) {
                continue;
            }
            if (issue.has("fields")) {
                facts.add(fromJiraIssue(issue));
            } else {
                facts.add(fromSanitized(issue));
            }
        }
        return List.copyOf(facts);
    }

    private List<JiraIssueFact> searchCloud(String jql) throws IOException {
        List<JiraIssueFact> all = new ArrayList<>();
        String nextPageToken = null;
        do {
            var body = mapper.createObjectNode();
            body.put("jql", jql);
            body.put("maxResults", Math.min(100, maxIssues - all.size()));
            var fields = body.putArray("fields");
            requestedFields().forEach(fields::add);
            if (nextPageToken != null && !nextPageToken.isBlank()) {
                body.put("nextPageToken", nextPageToken);
            }
            JsonNode root = postJson("/rest/api/3/search/jql", body.toString());
            all.addAll(parseIssues(root));
            nextPageToken = root.path("nextPageToken").asText("");
        } while (!nextPageToken.isBlank() && all.size() < maxIssues);
        log.info("JIRA Cloud search returned {} issues (read-only)", all.size());
        return List.copyOf(all);
    }

    private List<JiraIssueFact> searchLegacy(String jql, String path) throws IOException {
        List<JiraIssueFact> all = new ArrayList<>();
        int startAt = 0;
        int page = Math.min(100, maxIssues);
        while (all.size() < maxIssues) {
            String query = path + "?jql=" + enc(jql)
                    + "&startAt=" + startAt
                    + "&maxResults=" + page
                    + "&fields=" + enc(String.join(",", requestedFields()));
            JsonNode root = getJson(query);
            all.addAll(parseIssues(root));
            int total = root.path("total").asInt(all.size());
            startAt += page;
            if (startAt >= total || root.path("issues").size() < page) {
                break;
            }
        }
        log.info("JIRA search returned {} issues (read-only)", all.size());
        return List.copyOf(all);
    }

    private JiraIssueFact fromSanitized(JsonNode node) {
        List<String> labels = new ArrayList<>();
        JsonNode labelNode = node.get("labels");
        if (labelNode != null && labelNode.isArray()) {
            for (JsonNode label : labelNode) {
                String name = label.isTextual() ? label.asText("") : label.path("name").asText("");
                if (!name.isBlank()) {
                    labels.add(name);
                }
            }
        } else if (labelNode != null && labelNode.isTextual() && !labelNode.asText().isBlank()) {
            labels.add(labelNode.asText());
        }
        return new JiraIssueFact(
                node.path("key").asText(""),
                node.path("projectKey").asText(""),
                node.path("projectName").asText(""),
                node.path("summary").asText(""),
                flattenText(node.get("description")),
                flattenText(node.get("acceptance")),
                node.path("issueType").asText(""),
                node.path("epic").asText(""),
                firstText(node, "component", "components"),
                labels,
                node.path("fixVersion").asText(""),
                node.path("status").asText("")
        );
    }

    private JiraIssueFact fromJiraIssue(JsonNode issue) {
        JsonNode fields = issue.path("fields");
        String key = issue.path("key").asText("");
        String projectKey = fields.path("project").path("key").asText("");
        String projectName = fields.path("project").path("name").asText("");
        String summary = fields.path("summary").asText("");
        String description = flattenText(fields.get("description"));
        String acceptance = extractAcceptance(fields, description);
        String issueType = fields.path("issuetype").path("name").asText("");
        String status = fields.path("status").path("name").asText("");
        String epic = extractEpic(fields);
        String component = firstNamed(fields.get("components"));
        String fixVersion = firstNamed(fields.get("fixVersions"));
        List<String> labels = new ArrayList<>();
        JsonNode labelNode = fields.get("labels");
        if (labelNode != null && labelNode.isArray()) {
            for (JsonNode label : labelNode) {
                String name = label.asText("");
                if (!name.isBlank()) {
                    labels.add(name);
                }
            }
        }
        return new JiraIssueFact(
                key, projectKey, projectName, summary, description, acceptance,
                issueType, epic, component, labels, fixVersion, status
        );
    }

    private String extractAcceptance(JsonNode fields, String description) {
        if (!acceptanceField.isBlank() && fields.has(acceptanceField)) {
            String value = flattenText(fields.get(acceptanceField));
            if (!value.isBlank()) {
                return value;
            }
        }
        for (String candidate : List.of(
                "customfield_10015", "acceptance", "acceptanceCriteria", "Acceptance Criteria"
        )) {
            if (fields.has(candidate)) {
                String value = flattenText(fields.get(candidate));
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        Iterator<String> names = fields.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (name.toLowerCase(Locale.ROOT).contains("accept")) {
                String value = flattenText(fields.get(name));
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return extractAcceptanceSection(description);
    }

    private String extractEpic(JsonNode fields) {
        if (!epicNameField.isBlank()) {
            String configured = flattenText(fields.get(epicNameField));
            if (!configured.isBlank()) {
                return configured;
            }
        }
        String parentType = fields.path("parent").path("fields").path("issuetype").path("name").asText("");
        if (parentType.toLowerCase(Locale.ROOT).contains("epic")) {
            String name = fields.path("parent").path("fields").path("summary").asText("");
            if (!name.isBlank()) {
                return name;
            }
        }
        String epicName = flattenText(fields.get("customfield_10011"));
        if (!epicName.isBlank()) {
            return epicName;
        }
        String epicLink = flattenText(fields.get("customfield_10014"));
        if (!epicLink.isBlank()) {
            return epicLink;
        }
        JsonNode epicNode = fields.get("epic");
        if (epicNode != null && !epicNode.isNull()) {
            String name = epicNode.path("name").asText("");
            if (!name.isBlank()) {
                return name;
            }
            String summary = epicNode.path("summary").asText("");
            if (!summary.isBlank()) {
                return summary;
            }
        }
        return "";
    }

    private List<String> requestedFields() {
        List<String> fields = new ArrayList<>(SEARCH_FIELDS);
        if (!acceptanceField.isBlank() && !fields.contains(acceptanceField)) {
            fields.add(acceptanceField);
        }
        if (!epicNameField.isBlank() && !fields.contains(epicNameField)) {
            fields.add(epicNameField);
        }
        return fields;
    }

    private JsonNode getJson(String path) throws IOException {
        return exchange(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", basicAuth())
                .GET()
                .build());
    }

    private JsonNode postJson(String path, String json) throws IOException {
        return exchange(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", basicAuth())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private JsonNode exchange(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new IOException("JIRA API auth failed (" + status
                        + "). Check JIRA_EMAIL / JIRA_API_TOKEN (read-only search).");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("JIRA API HTTP " + status + ": " + truncate(response.body(), 180));
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("JIRA request interrupted", e);
        }
    }

    private String basicAuth() {
        String raw = email + ":" + apiToken;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isCloud() {
        return baseUrl.toLowerCase(Locale.ROOT).contains("atlassian.net");
    }

    static String flattenText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("").trim();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.has("content") && node.get("content").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode child : node.get("content")) {
                String part = flattenText(child);
                if (part.isBlank()) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(part);
            }
            return sb.toString().trim();
        }
        if (node.has("text")) {
            return node.path("text").asText("").trim();
        }
        if (node.has("value")) {
            return flattenText(node.get("value"));
        }
        return "";
    }

    static String extractAcceptanceSection(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String lower = description.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("acceptance criteria");
        if (idx < 0) {
            idx = lower.indexOf("acceptance:");
        }
        if (idx < 0) {
            return "";
        }
        String rest = description.substring(idx);
        return rest.length() <= 400 ? rest.trim() : rest.substring(0, 400).trim();
    }

    static String quoteJql(String value) {
        if (value.matches("[A-Za-z][A-Za-z0-9_]*")) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static List<String> normalizeProjects(List<String> projects) {
        if (projects == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String project : projects) {
            if (project != null && !project.isBlank()) {
                keys.add(project.trim());
            }
        }
        return keys;
    }

    private static String firstNamed(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return "";
        }
        JsonNode first = array.get(0);
        String name = first.path("name").asText("");
        return name.isBlank() ? first.asText("") : name;
    }

    private static String firstText(JsonNode node, String singular, String plural) {
        String single = node.path(singular).asText("");
        if (!single.isBlank()) {
            return single;
        }
        JsonNode many = node.get(plural);
        if (many != null && many.isArray() && !many.isEmpty()) {
            return many.get(0).asText("");
        }
        return "";
    }

    private static Path resolveExisting(String configured) {
        if (configured == null || configured.isBlank()) {
            return Path.of("missing-jira-fixture.json");
        }
        Path direct = Path.of(configured);
        if (Files.isRegularFile(direct)) {
            return direct.toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(
                cwd.resolve(configured),
                cwd.resolve("samples").resolve(Path.of(configured).getFileName()),
                cwd.getParent() == null
                        ? cwd.resolve(configured)
                        : cwd.getParent().resolve("samples").resolve(Path.of(configured).getFileName())
        )) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return direct.toAbsolutePath().normalize();
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
