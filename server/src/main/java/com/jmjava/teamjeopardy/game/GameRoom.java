package com.jmjava.teamjeopardy.game;

import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class GameRoom {

    private final String id;
    private final String code;
    private final String hostPlayerId;
    private final Instant createdAt;
    private String title;
    private GamePhase phase = GamePhase.LOBBY;
    private Board board;
    private ActiveClue activeClue;
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final Map<String, BoardCellState> cells = new LinkedHashMap<>();
    private String questionHints = "";
    private int revision;

    public GameRoom(String id, String code, String hostPlayerId, String title) {
        this.id = id;
        this.code = code;
        this.hostPlayerId = hostPlayerId;
        this.title = title;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getHostPlayerId() {
        return hostPlayerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public GamePhase getPhase() {
        return phase;
    }

    public void setPhase(GamePhase phase) {
        this.phase = phase;
    }

    public Board getBoard() {
        return board;
    }

    public ActiveClue getActiveClue() {
        return activeClue;
    }

    public void setActiveClue(ActiveClue activeClue) {
        this.activeClue = activeClue;
    }

    public Map<String, Team> getTeams() {
        return teams;
    }

    public Map<String, Player> getPlayers() {
        return players;
    }

    public Map<String, BoardCellState> getCells() {
        return cells;
    }

    public int getRevision() {
        return revision;
    }

    public void bumpRevision() {
        revision++;
    }

    public void installBoard(Board newBoard) {
        installBoard(newBoard, this.questionHints);
    }

    public void installBoard(Board newBoard, String hints) {
        this.board = newBoard;
        this.title = newBoard.title();
        this.questionHints = hints == null ? "" : hints.trim();
        this.cells.clear();
        for (Category category : newBoard.categories()) {
            for (Clue clue : category.clues()) {
                cells.put(clue.id(), new BoardCellState(
                        clue.id(),
                        category.id(),
                        clue.value(),
                        false,
                        clue.dailyDouble()
                ));
            }
        }
        this.activeClue = null;
        this.phase = GamePhase.LOBBY;
        bumpRevision();
    }

    public String getQuestionHints() {
        return questionHints;
    }

    public Optional<Clue> findClue(String clueId) {
        if (board == null) {
            return Optional.empty();
        }
        for (Category category : board.categories()) {
            for (Clue clue : category.clues()) {
                if (clue.id().equals(clueId)) {
                    return Optional.of(clue);
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Category> findCategory(String categoryId) {
        if (board == null) {
            return Optional.empty();
        }
        return board.categories().stream().filter(c -> c.id().equals(categoryId)).findFirst();
    }

    public boolean allCluesAnswered() {
        return !cells.isEmpty() && cells.values().stream().allMatch(BoardCellState::answered);
    }

    /** Full snapshot for the moderator (includes answers while previewing). */
    public GameSnapshot hostSnapshot() {
        return buildSnapshot(board, activeClue);
    }

    /**
     * Public/shared snapshot: answers redacted until revealed; during host preview
     * players only see category + dollar value (no prompt).
     */
    public GameSnapshot publicSnapshot() {
        return buildSnapshot(redactBoard(board), redactActiveClue(activeClue, phase));
    }

    /** @deprecated prefer {@link #hostSnapshot()} / {@link #publicSnapshot()} */
    public GameSnapshot snapshot() {
        return hostSnapshot();
    }

    private GameSnapshot buildSnapshot(Board boardView, ActiveClue clueView) {
        return new GameSnapshot(
                id,
                code,
                title,
                phase,
                hostPlayerId,
                revision,
                createdAt,
                boardView,
                clueView,
                new ArrayList<>(teams.values()),
                new ArrayList<>(players.values()),
                new ArrayList<>(cells.values()),
                questionHints
        );
    }

    private static Board redactBoard(Board source) {
        if (source == null) {
            return null;
        }
        List<Category> cats = source.categories().stream()
                .map(cat -> new Category(
                        cat.id(),
                        cat.title(),
                        cat.clues().stream()
                                .map(c -> new Clue(
                                        c.id(),
                                        c.value(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        c.dailyDouble()
                                ))
                                .toList()
                ))
                .toList();
        return new Board(source.title(), source.sourceRoot(), cats, source.graphDigest());
    }

    private static ActiveClue redactActiveClue(ActiveClue clue, GamePhase phase) {
        if (clue == null) {
            return null;
        }
        if (phase == GamePhase.HOST_PREVIEW) {
            return clue.asHostPreviewTeaser();
        }
        if (clue.responseVisible()) {
            return clue;
        }
        return clue.withoutAnswer();
    }
}
