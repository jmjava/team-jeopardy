package com.jmjava.teamjeopardy.persist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class QuestionBankRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public QuestionBankRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public SavedBoardRecord insert(SavedBoardRecord record) {
        jdbc.update(
                """
                INSERT INTO saved_boards (
                    id, title, source_kind, source_key, fingerprint,
                    question_hints, question_focuses, source_root,
                    graph_nodes, graph_edges, graph_files, graph_types, graph_functions,
                    category_count, clue_count, summary_json, board_json,
                    created_at, last_used_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                record.id(),
                record.title(),
                record.sourceKind(),
                record.sourceKey(),
                record.fingerprint(),
                nullToEmpty(record.questionHints()),
                writeJson(record.questionFocuses() == null ? List.of() : record.questionFocuses()),
                record.sourceRoot(),
                digestInt(record.graphDigest(), "nodes"),
                digestInt(record.graphDigest(), "edges"),
                digestInt(record.graphDigest(), "files"),
                digestInt(record.graphDigest(), "types"),
                digestInt(record.graphDigest(), "functions"),
                record.categoryCount(),
                record.clueCount(),
                writeJson(record.summary() == null ? Map.of() : record.summary()),
                writeJson(record.board()),
                record.createdAt().toString(),
                record.lastUsedAt().toString()
        );

        if (record.board() != null && record.board().categories() != null) {
            for (Category category : record.board().categories()) {
                if (category.clues() == null) {
                    continue;
                }
                for (Clue clue : category.clues()) {
                    jdbc.update(
                            """
                            INSERT INTO saved_clues (
                                id, board_id, category_id, category_title, clue_id,
                                value, prompt, response, explanation, source_path, daily_double
                            ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                            """,
                            UUID.randomUUID().toString(),
                            record.id(),
                            category.id(),
                            category.title(),
                            clue.id(),
                            clue.value(),
                            clue.prompt(),
                            clue.response(),
                            clue.explanation(),
                            clue.sourcePath(),
                            clue.dailyDouble() ? 1 : 0
                    );
                }
            }
        }
        return record;
    }

    public List<SavedBoardSummary> list(int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return jdbc.query(
                """
                SELECT id, title, source_kind, source_key, fingerprint,
                       question_hints, question_focuses, source_root,
                       graph_nodes, graph_edges, graph_files, graph_types, graph_functions,
                       category_count, clue_count, created_at, last_used_at
                FROM saved_boards
                ORDER BY datetime(created_at) DESC
                LIMIT ?
                """,
                summaryMapper(),
                capped
        );
    }

    public Optional<SavedBoardRecord> findById(String id) {
        List<SavedBoardRecord> rows = jdbc.query(
                """
                SELECT id, title, source_kind, source_key, fingerprint,
                       question_hints, question_focuses, source_root,
                       graph_nodes, graph_edges, graph_files, graph_types, graph_functions,
                       category_count, clue_count, summary_json, board_json,
                       created_at, last_used_at
                FROM saved_boards
                WHERE id = ?
                """,
                fullMapper(),
                id
        );
        return rows.stream().findFirst();
    }

    public Optional<SavedBoardRecord> findLatestByFingerprint(String fingerprint) {
        List<SavedBoardRecord> rows = jdbc.query(
                """
                SELECT id, title, source_kind, source_key, fingerprint,
                       question_hints, question_focuses, source_root,
                       graph_nodes, graph_edges, graph_files, graph_types, graph_functions,
                       category_count, clue_count, summary_json, board_json,
                       created_at, last_used_at
                FROM saved_boards
                WHERE fingerprint = ?
                ORDER BY datetime(created_at) DESC
                LIMIT 1
                """,
                fullMapper(),
                fingerprint
        );
        return rows.stream().findFirst();
    }

    public void touchLastUsed(String id, Instant when) {
        jdbc.update(
                "UPDATE saved_boards SET last_used_at = ? WHERE id = ?",
                when.toString(),
                id
        );
    }

    public boolean delete(String id) {
        jdbc.update("DELETE FROM saved_clues WHERE board_id = ?", id);
        return jdbc.update("DELETE FROM saved_boards WHERE id = ?", id) > 0;
    }

    public int deleteAll() {
        jdbc.update("DELETE FROM saved_clues");
        return jdbc.update("DELETE FROM saved_boards");
    }

    public Optional<SavedClueRecord> findClueById(String clueRowId) {
        List<SavedClueRecord> rows = jdbc.query(
                """
                SELECT id, board_id, category_id, category_title, clue_id,
                       value, prompt, response, explanation, source_path, daily_double
                FROM saved_clues
                WHERE id = ?
                """,
                clueMapper(),
                clueRowId
        );
        return rows.stream().findFirst();
    }

    public List<SavedClueRecord> listCluesForBoard(String boardId) {
        return jdbc.query(
                """
                SELECT id, board_id, category_id, category_title, clue_id,
                       value, prompt, response, explanation, source_path, daily_double
                FROM saved_clues
                WHERE board_id = ?
                ORDER BY category_title ASC, value ASC
                """,
                clueMapper(),
                boardId
        );
    }

    public boolean deleteClueRow(String clueRowId) {
        return jdbc.update("DELETE FROM saved_clues WHERE id = ?", clueRowId) > 0;
    }

    public void updateBoardContent(String boardId, Board board, Instant lastUsedAt) {
        int categories = board.categories() == null ? 0 : board.categories().size();
        int clues = board.categories() == null
                ? 0
                : board.categories().stream()
                .mapToInt(c -> c.clues() == null ? 0 : c.clues().size())
                .sum();
        Board.GraphDigest digest = board.graphDigest();
        jdbc.update(
                """
                UPDATE saved_boards
                SET title = ?,
                    source_root = ?,
                    graph_nodes = ?, graph_edges = ?, graph_files = ?, graph_types = ?, graph_functions = ?,
                    category_count = ?, clue_count = ?, board_json = ?, last_used_at = ?
                WHERE id = ?
                """,
                board.title(),
                board.sourceRoot(),
                digestInt(digest, "nodes"),
                digestInt(digest, "edges"),
                digestInt(digest, "files"),
                digestInt(digest, "types"),
                digestInt(digest, "functions"),
                categories,
                clues,
                writeJson(board),
                lastUsedAt.toString(),
                boardId
        );
    }

    public SavedClueRecord insertClue(SavedClueRecord clue) {
        jdbc.update(
                """
                INSERT INTO saved_clues (
                    id, board_id, category_id, category_title, clue_id,
                    value, prompt, response, explanation, source_path, daily_double
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                clue.id(),
                clue.boardId(),
                clue.categoryId(),
                clue.categoryTitle(),
                clue.clueId(),
                clue.value(),
                clue.prompt(),
                clue.response(),
                clue.explanation(),
                clue.sourcePath(),
                clue.dailyDouble() ? 1 : 0
        );
        return clue;
    }

    public List<SavedClueRecord> searchClues(String query, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return jdbc.query(
                    """
                    SELECT id, board_id, category_id, category_title, clue_id,
                           value, prompt, response, explanation, source_path, daily_double
                    FROM saved_clues
                    ORDER BY value ASC
                    LIMIT ?
                    """,
                    clueMapper(),
                    capped
            );
        }
        String like = "%" + q.toLowerCase() + "%";
        return jdbc.query(
                """
                SELECT id, board_id, category_id, category_title, clue_id,
                       value, prompt, response, explanation, source_path, daily_double
                FROM saved_clues
                WHERE lower(prompt) LIKE ?
                   OR lower(response) LIKE ?
                   OR lower(category_title) LIKE ?
                   OR lower(COALESCE(explanation, '')) LIKE ?
                ORDER BY value ASC
                LIMIT ?
                """,
                clueMapper(),
                like,
                like,
                like,
                like,
                capped
        );
    }

    public long countBoards() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM saved_boards", Long.class);
        return count == null ? 0L : count;
    }

    public long countClues() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM saved_clues", Long.class);
        return count == null ? 0L : count;
    }

    private RowMapper<SavedBoardSummary> summaryMapper() {
        return (rs, rowNum) -> new SavedBoardSummary(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("source_kind"),
                rs.getString("source_key"),
                rs.getString("fingerprint"),
                rs.getString("question_hints"),
                readStringList(rs.getString("question_focuses")),
                rs.getString("source_root"),
                digest(rs),
                rs.getInt("category_count"),
                rs.getInt("clue_count"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("last_used_at"))
        );
    }

    private RowMapper<SavedBoardRecord> fullMapper() {
        return (rs, rowNum) -> {
            Board board = readJson(rs.getString("board_json"), Board.class);
            Map<String, Object> summary = readJson(
                    rs.getString("summary_json"),
                    new TypeReference<Map<String, Object>>() {
                    }
            );
            return new SavedBoardRecord(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("source_kind"),
                    rs.getString("source_key"),
                    rs.getString("fingerprint"),
                    rs.getString("question_hints"),
                    readStringList(rs.getString("question_focuses")),
                    rs.getString("source_root"),
                    digest(rs),
                    rs.getInt("category_count"),
                    rs.getInt("clue_count"),
                    summary == null ? Map.of() : summary,
                    board,
                    Instant.parse(rs.getString("created_at")),
                    Instant.parse(rs.getString("last_used_at"))
            );
        };
    }

    private RowMapper<SavedClueRecord> clueMapper() {
        return (rs, rowNum) -> new SavedClueRecord(
                rs.getString("id"),
                rs.getString("board_id"),
                rs.getString("category_id"),
                rs.getString("category_title"),
                rs.getString("clue_id"),
                rs.getInt("value"),
                rs.getString("prompt"),
                rs.getString("response"),
                rs.getString("explanation"),
                rs.getString("source_path"),
                rs.getInt("daily_double") != 0
        );
    }

    private static Board.GraphDigest digest(ResultSet rs) throws SQLException {
        return new Board.GraphDigest(
                rs.getInt("graph_nodes"),
                rs.getInt("graph_edges"),
                rs.getInt("graph_files"),
                rs.getInt("graph_types"),
                rs.getInt("graph_functions")
        );
    }

    private static int digestInt(Board.GraphDigest digest, String field) {
        if (digest == null) {
            return 0;
        }
        return switch (field) {
            case "nodes" -> digest.nodes();
            case "edges" -> digest.edges();
            case "files" -> digest.files();
            case "types" -> digest.types();
            case "functions" -> digest.functions();
            default -> 0;
        };
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> list = readJson(json, new TypeReference<List<String>>() {
        });
        return list == null ? List.of() : List.copyOf(list);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize for SQLite", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize from SQLite", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize from SQLite", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Helper for tests that need a mutable summary map copy. */
    static Map<String, Object> copySummary(Map<String, Object> summary) {
        return summary == null ? new LinkedHashMap<>() : new LinkedHashMap<>(summary);
    }

    static List<Category> emptyCategories() {
        return new ArrayList<>();
    }
}
