package com.jmjava.teamjeopardy.persist;

import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import com.jmjava.teamjeopardy.quiz.QuestionHints;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persists guide-generated boards/clues in SQLite so moderators can reuse them
 * without re-ingesting a project.
 */
@Service
public class QuestionBankService {

    private final QuestionBankRepository repository;
    private final boolean enabled;

    public QuestionBankService(
            QuestionBankRepository repository,
            @Value("${team-jeopardy.persistence.enabled:true}") boolean enabled
    ) {
        this.repository = repository;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<SavedBoardRecord> saveGenerated(
            Board board,
            Map<String, Object> ingestSummary,
            String sourceKind,
            String sourceKey,
            QuestionHints hints
    ) {
        if (!enabled || board == null) {
            return Optional.empty();
        }
        QuestionHints effective = hints == null ? QuestionHints.empty() : hints;
        String kind = blankTo(sourceKind, "unknown").toLowerCase(Locale.ROOT);
        String key = blankTo(sourceKey, board.sourceRoot() == null ? board.title() : board.sourceRoot());
        String fingerprint = fingerprint(kind, key, effective);

        Instant now = Instant.now();
        int categories = board.categories() == null ? 0 : board.categories().size();
        int clues = board.categories() == null
                ? 0
                : board.categories().stream()
                .mapToInt(c -> c.clues() == null ? 0 : c.clues().size())
                .sum();

        Map<String, Object> summary = ingestSummary == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(ingestSummary);
        summary.put("persisted", true);
        summary.put("sourceKind", kind);
        summary.put("sourceKey", key);
        summary.put("fingerprint", fingerprint);

        SavedBoardRecord record = new SavedBoardRecord(
                UUID.randomUUID().toString(),
                board.title(),
                kind,
                key,
                fingerprint,
                effective.combined(),
                effective.focuses(),
                board.sourceRoot(),
                board.graphDigest(),
                categories,
                clues,
                summary,
                board,
                now,
                now
        );
        return Optional.of(repository.insert(record));
    }

    public List<SavedBoardSummary> list(int limit) {
        requireEnabled();
        return repository.list(limit);
    }

    public SavedBoardRecord get(String id) {
        requireEnabled();
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved board not found"));
    }

    public Board loadBoard(String id) {
        SavedBoardRecord record = get(id);
        repository.touchLastUsed(id, Instant.now());
        return record.board();
    }

    public boolean delete(String id) {
        requireEnabled();
        return repository.delete(id);
    }

    public int deleteAll() {
        requireEnabled();
        return repository.deleteAll();
    }

    /**
     * Manually create a board record from admin maintenance UI / API.
     */
    public SavedBoardRecord createManual(
            String title,
            String sourceKind,
            String sourceKey,
            String sourceRoot,
            QuestionHints hints,
            List<Category> categories,
            Board.GraphDigest digest
    ) {
        requireEnabled();
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        List<Category> cats = normalizeCategories(categories);
        if (cats.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one category with clues is required");
        }
        Board board = new Board(
                title.trim(),
                sourceRoot == null ? "" : sourceRoot.trim(),
                cats,
                digest == null ? new Board.GraphDigest(0, 0, 0, 0, 0) : digest
        );
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", "manual");
        summary.put("createdVia", "question-bank-admin");
        return saveGenerated(
                board,
                summary,
                blankTo(sourceKind, "manual"),
                blankTo(sourceKey, "manual:" + title.trim().toLowerCase(Locale.ROOT)),
                hints == null ? QuestionHints.empty() : hints
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Question bank persistence is disabled"
        ));
    }

    public List<SavedClueRecord> listCluesForBoard(String boardId) {
        requireEnabled();
        get(boardId);
        return repository.listCluesForBoard(boardId);
    }

    public SavedClueRecord addClue(
            String boardId,
            String categoryId,
            String categoryTitle,
            Integer value,
            String prompt,
            String response,
            String explanation,
            String sourcePath,
            Boolean dailyDouble
    ) {
        requireEnabled();
        SavedBoardRecord existing = get(boardId);
        if (prompt == null || prompt.isBlank() || response == null || response.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt and response are required");
        }
        String catTitle = blankTo(categoryTitle, "Manual");
        String catId = blankTo(categoryId, slug(catTitle));
        int clueValue = value == null ? 200 : value;
        String clueId = "manual-" + UUID.randomUUID().toString().substring(0, 8);

        Clue clue = new Clue(
                clueId,
                clueValue,
                prompt.trim(),
                response.trim(),
                explanation == null ? "" : explanation.trim(),
                sourcePath == null ? "" : sourcePath.trim(),
                Boolean.TRUE.equals(dailyDouble)
        );

        List<Category> categories = new ArrayList<>();
        boolean found = false;
        for (Category category : existing.board().categories()) {
            if (category.id().equals(catId) || category.title().equalsIgnoreCase(catTitle)) {
                List<Clue> clues = new ArrayList<>(category.clues() == null ? List.of() : category.clues());
                clues.add(clue);
                categories.add(new Category(category.id(), category.title(), clues));
                found = true;
            } else {
                categories.add(category);
            }
        }
        if (!found) {
            categories.add(new Category(catId, catTitle, List.of(clue)));
        }

        Board updated = new Board(
                existing.board().title(),
                existing.board().sourceRoot(),
                categories,
                existing.board().graphDigest()
        );
        repository.updateBoardContent(boardId, updated, Instant.now());

        SavedClueRecord row = new SavedClueRecord(
                UUID.randomUUID().toString(),
                boardId,
                catId,
                catTitle,
                clueId,
                clueValue,
                clue.prompt(),
                clue.response(),
                clue.explanation(),
                clue.sourcePath(),
                clue.dailyDouble()
        );
        return repository.insertClue(row);
    }

    public boolean deleteClue(String clueRowId) {
        requireEnabled();
        SavedClueRecord clue = repository.findClueById(clueRowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved clue not found"));
        SavedBoardRecord board = get(clue.boardId());

        List<Category> categories = new ArrayList<>();
        for (Category category : board.board().categories()) {
            List<Clue> remaining = category.clues() == null
                    ? List.of()
                    : category.clues().stream()
                    .filter(c -> !c.id().equals(clue.clueId()))
                    .toList();
            if (!remaining.isEmpty()) {
                categories.add(new Category(category.id(), category.title(), remaining));
            }
        }

        if (categories.isEmpty()) {
            // Last clue removed — drop the whole board.
            return repository.delete(board.id());
        }

        Board updated = new Board(
                board.board().title(),
                board.board().sourceRoot(),
                categories,
                board.board().graphDigest()
        );
        repository.updateBoardContent(board.id(), updated, Instant.now());
        return repository.deleteClueRow(clueRowId);
    }

    public List<SavedClueRecord> searchClues(String query, int limit) {
        requireEnabled();
        return repository.searchClues(query, limit);
    }

    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        if (enabled) {
            body.put("boardCount", repository.countBoards());
            body.put("clueCount", repository.countClues());
        }
        return body;
    }

    private static List<Category> normalizeCategories(List<Category> categories) {
        if (categories == null) {
            return List.of();
        }
        List<Category> out = new ArrayList<>();
        int catSeq = 1;
        for (Category category : categories) {
            if (category == null || category.clues() == null || category.clues().isEmpty()) {
                continue;
            }
            String title = blankTo(category.title(), "Category " + catSeq);
            String id = blankTo(category.id(), slug(title));
            List<Clue> clues = new ArrayList<>();
            int clueSeq = 1;
            for (Clue clue : category.clues()) {
                if (clue == null || clue.prompt() == null || clue.prompt().isBlank()
                        || clue.response() == null || clue.response().isBlank()) {
                    continue;
                }
                clues.add(new Clue(
                        blankTo(clue.id(), id + "-" + clueSeq),
                        clue.value() <= 0 ? clueSeq * 200 : clue.value(),
                        clue.prompt().trim(),
                        clue.response().trim(),
                        clue.explanation() == null ? "" : clue.explanation().trim(),
                        clue.sourcePath() == null ? "" : clue.sourcePath().trim(),
                        clue.dailyDouble()
                ));
                clueSeq++;
            }
            if (!clues.isEmpty()) {
                out.add(new Category(id, title, clues));
                catSeq++;
            }
        }
        return out;
    }

    private static String slug(String title) {
        String cleaned = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        cleaned = cleaned.replaceAll("^-+|-+$", "");
        return cleaned.isBlank() ? "category" : cleaned;
    }

    public static String fingerprint(String sourceKind, String sourceKey, QuestionHints hints) {
        QuestionHints effective = hints == null ? QuestionHints.empty() : hints;
        String focuses = effective.focuses() == null
                ? ""
                : effective.focuses().stream().sorted().collect(Collectors.joining(","));
        String raw = blankTo(sourceKind, "")
                + "|"
                + blankTo(sourceKey, "")
                + "|"
                + blankTo(effective.text(), "")
                + "|"
                + focuses;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sourceKeyForSample(String sampleType) {
        return "sample:" + blankTo(sampleType, "maven").toLowerCase(Locale.ROOT);
    }

    public static String sourceKeyForPath(String path) {
        return "path:" + blankTo(path, "");
    }

    public static String sourceKeyForGithub(String repo, String ref, List<String> folders) {
        String folderPart = folders == null || folders.isEmpty()
                ? "*"
                : folders.stream().sorted().collect(Collectors.joining(","));
        return "github:" + blankTo(repo, "") + "@" + blankTo(ref, "HEAD") + "/" + folderPart;
    }

    public static String sourceKeyForPulls(String repo) {
        return "pulls:" + blankTo(repo, "");
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Question bank persistence is disabled");
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
