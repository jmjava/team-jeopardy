package com.jmjava.teamjeopardy.quiz.enrich;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Polishes heuristic Jeopardy clues with OpenAI Chat Completions.
 * Enabled only when {@code team-jeopardy.openai.enabled=true} and an API key is set.
 * Failures fall back to the original heuristic text so ingest always succeeds offline.
 */
@Component
@ConditionalOnProperty(name = "team-jeopardy.openai.enabled", havingValue = "true")
public class OpenAiQuestionEnricher implements QuestionEnricher {

    private static final Logger log = LoggerFactory.getLogger(OpenAiQuestionEnricher.class);

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int maxClues;
    private final Duration timeout;

    public OpenAiQuestionEnricher(
            ObjectMapper mapper,
            @Value("${team-jeopardy.openai.api-key:}") String apiKey,
            @Value("${team-jeopardy.openai.model:gpt-4o-mini}") String model,
            @Value("${team-jeopardy.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${team-jeopardy.openai.max-clues:24}") int maxClues,
            @Value("${team-jeopardy.openai.timeout-seconds:20}") int timeoutSeconds
    ) {
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.maxClues = Math.max(1, maxClues);
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public List<Category> enrich(CodeGraph graph, List<Category> categories) {
        return enrich(graph, categories, graph == null ? "" : graph.getQuestionHints());
    }

    @Override
    public List<Category> enrich(CodeGraph graph, List<Category> categories, String questionHints) {
        if (apiKey.isBlank()) {
            log.warn("OpenAI enricher enabled but team-jeopardy.openai.api-key / OPENAI_API_KEY is empty; skipping");
            return categories;
        }
        String hints = questionHints == null || questionHints.isBlank()
                ? (graph == null ? "" : graph.getQuestionHints())
                : questionHints;
        List<Category> out = new ArrayList<>();
        int remaining = maxClues;
        for (Category category : categories) {
            if (remaining <= 0) {
                out.add(category);
                continue;
            }
            List<Clue> clues = new ArrayList<>();
            for (Clue clue : category.clues()) {
                if (remaining <= 0) {
                    clues.add(clue);
                    continue;
                }
                clues.add(polish(category.title(), clue, graph, hints));
                remaining--;
            }
            out.add(new Category(category.id(), category.title(), clues));
        }
        return out;
    }

    private Clue polish(String categoryTitle, Clue clue, CodeGraph graph, String hints) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.4);
            ArrayNode messages = body.putArray("messages");
            messages.addObject()
                    .put("role", "system")
                    .put("content", systemPrompt());
            messages.addObject()
                    .put("role", "user")
                    .put("content", userPrompt(categoryTitle, clue, graph, hints));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("OpenAI enrich HTTP {}: {}", response.statusCode(), truncate(response.body(), 200));
                return clue;
            }
            JsonNode root = mapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            return applyModelJson(clue, content);
        } catch (Exception e) {
            log.warn("OpenAI enrich failed for clue {}: {}", clue.id(), e.toString());
            return clue;
        }
    }

    private Clue applyModelJson(Clue clue, String content) throws Exception {
        String json = extractJsonObject(content);
        if (json == null) {
            return clue;
        }
        JsonNode node = mapper.readTree(json);
        String prompt = textOr(node, "prompt", clue.prompt());
        String explanation = textOr(node, "explanation", clue.explanation());
        String response = clue.response(); // never trust model to change the answer
        // Guard: prompt must still be grounded — keep original if model emptied it
        if (prompt == null || prompt.isBlank() || prompt.length() < 12) {
            return clue;
        }
        return new Clue(
                clue.id(),
                clue.value(),
                prompt.trim(),
                response,
                explanation == null ? clue.explanation() : explanation.trim(),
                clue.sourcePath(),
                clue.dailyDouble()
        );
    }

    private static String systemPrompt() {
        return """
                You enrich Team Jeopardy clues for software engineers and QA.
                Return ONLY a JSON object with keys: prompt, explanation.
                Rules:
                - Keep Jeopardy style: the clue is a statement; players respond as a question.
                - Do NOT change the correct answer; the response field is fixed by the server.
                - Prefer concrete code/architecture wording over trivia counts.
                - For QA-oriented categories, emphasize contracts, regressions, risk, and test targets.
                - For coder categories, emphasize APIs, patterns, call structure, and ownership.
                - Stay grounded in the provided evidence; invent nothing.
                - When moderator hints are provided, bias wording and emphasis toward those themes
                  without changing the fixed answer or inventing facts.
                - Keep prompt under 280 characters when possible.
                """;
    }

    private String userPrompt(String categoryTitle, Clue clue, CodeGraph graph, String hints) {
        String project = graph.getProjectName() == null ? "" : graph.getProjectName();
        String kind = graph.getProjectKind() == null ? "" : graph.getProjectKind().name();
        return """
                Category: %s
                Project: %s (%s)
                Moderator question hints: %s
                Fixed answer (must remain correct): %s
                Evidence path: %s
                Current prompt:
                %s
                Current explanation:
                %s
                """.formatted(
                categoryTitle,
                project,
                kind,
                hints == null || hints.isBlank() ? "(none)" : hints,
                clue.response(),
                clue.sourcePath() == null ? "(none)" : clue.sourcePath(),
                clue.prompt(),
                clue.explanation() == null ? "" : clue.explanation()
        );
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText("");
        return text.isBlank() ? fallback : text;
    }

    private static String extractJsonObject(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max).toLowerCase(Locale.ROOT) + "…";
    }
}
