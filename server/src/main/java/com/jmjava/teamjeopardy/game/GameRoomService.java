package com.jmjava.teamjeopardy.game;

import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameRoomService {

    private static final String[] TEAM_COLORS = {
            "#E8C547", "#3D8BFF", "#E85D4C", "#3ECF8E", "#C084FC", "#FB923C"
    };
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final Map<String, GameRoom> roomsById = new ConcurrentHashMap<>();
    private final Map<String, String> roomIdByCode = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Value("${team-jeopardy.max-teams:6}")
    private int maxTeams;

    @Value("${team-jeopardy.max-players-per-team:8}")
    private int maxPlayersPerTeam;

    public record CreateRoomResult(GameSnapshot snapshot, String hostPlayerId, String hostName) {
    }

    public record JoinResult(GameSnapshot snapshot, String playerId) {
    }

    public CreateRoomResult createRoom(String hostName, String title) {
        String roomId = UUID.randomUUID().toString();
        String code = allocateCode();
        String hostId = UUID.randomUUID().toString();
        String effectiveHost = blankTo(hostName, "Host");
        GameRoom room = new GameRoom(roomId, code, hostId, blankTo(title, "Team Jeopardy"));
        room.getPlayers().put(hostId, new Player(hostId, effectiveHost, null, true, true, null));
        roomsById.put(roomId, room);
        roomIdByCode.put(code, roomId);
        room.bumpRevision();
        return new CreateRoomResult(room.hostSnapshot(), hostId, effectiveHost);
    }

    public JoinResult joinRoom(String code, String displayName, String teamName) {
        GameRoom room = requireByCode(code);
        if (room.getPhase() == GamePhase.FINISHED) {
            throw conflict("Room already finished");
        }
        String playerId = UUID.randomUUID().toString();
        String name = blankTo(displayName, "Player");
        final String teamId;
        if (teamName != null && !teamName.isBlank()) {
            String ensuredTeamId = ensureTeam(room, teamName.trim()).id();
            long onTeam = room.getPlayers().values().stream()
                    .filter(p -> ensuredTeamId.equals(p.teamId()))
                    .count();
            if (onTeam >= maxPlayersPerTeam) {
                throw conflict("Team is full");
            }
            teamId = ensuredTeamId;
        } else {
            teamId = null;
        }
        // Players wait in lobby until the moderator admits them.
        room.getPlayers().put(playerId, new Player(playerId, name, teamId, false, false, null));
        room.bumpRevision();
        return new JoinResult(room.publicSnapshot(), playerId);
    }

    public GameSnapshot installBoard(String roomId, String playerId, Board board) {
        return installBoard(roomId, playerId, board, null);
    }

    public GameSnapshot installBoard(String roomId, String playerId, Board board, String questionHints) {
        GameRoom room = requireById(roomId);
        requireHost(room, playerId);
        room.installBoard(board, questionHints);
        return snapshotFor(room, playerId);
    }

    public GameSnapshot admitPlayer(String roomId, String hostPlayerId, String targetPlayerId) {
        GameRoom room = requireById(roomId);
        requireHost(room, hostPlayerId);
        Player target = Optional.ofNullable(room.getPlayers().get(targetPlayerId))
                .orElseThrow(() -> notFound("Unknown player"));
        if (target.host()) {
            throw conflict("Host is already in the room");
        }
        room.getPlayers().put(targetPlayerId, target.withAdmitted(true));
        room.bumpRevision();
        return room.hostSnapshot();
    }

    public GameSnapshot admitAll(String roomId, String hostPlayerId) {
        GameRoom room = requireById(roomId);
        requireHost(room, hostPlayerId);
        for (Player player : List.copyOf(room.getPlayers().values())) {
            if (!player.host() && !player.admitted()) {
                room.getPlayers().put(player.id(), player.withAdmitted(true));
            }
        }
        room.bumpRevision();
        return room.hostSnapshot();
    }

    public GameSnapshot startGame(String roomId, String playerId) {
        GameRoom room = requireById(roomId);
        requireHost(room, playerId);
        if (room.getBoard() == null) {
            throw conflict("Ingest a codebase and generate a board before starting");
        }
        long admittedPlayers = room.getPlayers().values().stream()
                .filter(p -> !p.host() && p.admitted() && p.teamId() != null)
                .count();
        if (admittedPlayers == 0) {
            throw conflict("Admit at least one teamed player before starting");
        }
        room.setPhase(GamePhase.BOARD);
        room.setActiveClue(null);
        room.bumpRevision();
        return room.hostSnapshot();
    }

    public GameSnapshot selectClue(String roomId, String playerId, String clueId) {
        GameRoom room = requireById(roomId);
        requireHost(room, playerId);
        if (room.getPhase() != GamePhase.BOARD) {
            throw conflict("Clues can only be selected from the board");
        }
        BoardCellState cell = room.getCells().get(clueId);
        if (cell == null) {
            throw notFound("Unknown clue");
        }
        if (cell.answered()) {
            throw conflict("Clue already answered");
        }
        Clue clue = room.findClue(clueId).orElseThrow(() -> notFound("Unknown clue"));
        Category category = room.findCategory(cell.categoryId())
                .orElseThrow(() -> notFound("Unknown category"));
        room.setActiveClue(new ActiveClue(
                clue.id(),
                category.id(),
                category.title(),
                clue.value(),
                clue.prompt(),
                clue.response(),
                clue.explanation(),
                clue.sourcePath(),
                clue.dailyDouble(),
                false,
                null, null, null, null, null
        ));
        // Host reads first; players see only a teaser until OPEN_BUZZERS.
        room.setPhase(GamePhase.HOST_PREVIEW);
        room.bumpRevision();
        return room.hostSnapshot();
    }

    public GameSnapshot openBuzzers(String roomId, String hostPlayerId) {
        GameRoom room = requireById(roomId);
        requireHost(room, hostPlayerId);
        if (room.getPhase() != GamePhase.HOST_PREVIEW) {
            throw conflict("Buzzers can only open after the host preview");
        }
        if (room.getActiveClue() == null) {
            throw conflict("No active clue");
        }
        room.setPhase(GamePhase.CLUE_OPEN);
        room.bumpRevision();
        return room.hostSnapshot();
    }

    public GameSnapshot buzz(String roomId, String playerId) {
        GameRoom room = requireById(roomId);
        if (room.getPhase() != GamePhase.CLUE_OPEN) {
            throw conflict("Buzzing is not open");
        }
        Player player = Optional.ofNullable(room.getPlayers().get(playerId))
                .orElseThrow(() -> notFound("Unknown player"));
        if (player.host() || !player.admitted()) {
            throw conflict("Only admitted players can buzz");
        }
        if (player.teamId() == null) {
            throw conflict("Join a team before buzzing");
        }
        ActiveClue active = room.getActiveClue();
        if (active == null) {
            throw conflict("No active clue");
        }
        if (active.buzzedPlayerId() != null) {
            throw conflict("Another player already buzzed in");
        }
        Team team = room.getTeams().get(player.teamId());
        room.setActiveClue(new ActiveClue(
                active.clueId(),
                active.categoryId(),
                active.categoryTitle(),
                active.value(),
                active.prompt(),
                active.response(),
                active.explanation(),
                active.sourcePath(),
                active.dailyDouble(),
                false,
                player.id(),
                player.displayName(),
                player.teamId(),
                team == null ? null : team.name(),
                team == null ? null : team.color()
        ));
        room.setPhase(GamePhase.BUZZ_LOCKED);
        room.bumpRevision();
        return snapshotFor(room, playerId);
    }

    public GameSnapshot judge(String roomId, String hostPlayerId, boolean correct) {
        GameRoom room = requireById(roomId);
        requireHost(room, hostPlayerId);
        if (room.getPhase() != GamePhase.BUZZ_LOCKED && room.getPhase() != GamePhase.ANSWER_REVEALED) {
            throw conflict("Nothing to judge");
        }
        ActiveClue active = room.getActiveClue();
        if (active == null || active.buzzedTeamId() == null) {
            throw conflict("No buzzed team to score");
        }
        Team team = room.getTeams().get(active.buzzedTeamId());
        if (team == null) {
            throw notFound("Buzzed team missing");
        }
        int delta = active.value();
        int nextScore = correct ? team.score() + delta : team.score() - delta;
        room.getTeams().put(team.id(), team.withScore(nextScore));

        if (correct) {
            markAnswered(room, active.clueId());
            room.setActiveClue(withResponseVisible(active, true));
            room.setPhase(GamePhase.ANSWER_REVEALED);
        } else {
            room.setActiveClue(new ActiveClue(
                    active.clueId(),
                    active.categoryId(),
                    active.categoryTitle(),
                    active.value(),
                    active.prompt(),
                    active.response(),
                    active.explanation(),
                    active.sourcePath(),
                    active.dailyDouble(),
                    false,
                    null, null, null, null, null
            ));
            room.setPhase(GamePhase.CLUE_OPEN);
        }
        room.bumpRevision();
        return room.hostSnapshot();
    }

    public GameSnapshot revealAnswer(String roomId, String hostPlayerId) {
        GameRoom room = requireById(roomId);
        requireHost(room, hostPlayerId);
        ActiveClue active = room.getActiveClue();
        if (active == null) {
            throw conflict("No active clue");
        }
        markAnswered(room, active.clueId());
        room.setActiveClue(withResponseVisible(active, true));
        room.setPhase(GamePhase.ANSWER_REVEALED);
        room.bumpRevision();
        return room.hostSnapshot();
    }

    public GameSnapshot returnToBoard(String roomId, String hostPlayerId) {
        GameRoom room = requireById(roomId);
        requireHost(room, hostPlayerId);
        room.setActiveClue(null);
        if (room.allCluesAnswered()) {
            room.setPhase(GamePhase.FINISHED);
        } else {
            room.setPhase(GamePhase.BOARD);
        }
        room.bumpRevision();
        return room.hostSnapshot();
    }

    public GameSnapshot createTeam(String roomId, String playerId, String teamName) {
        GameRoom room = requireById(roomId);
        Player player = Optional.ofNullable(room.getPlayers().get(playerId))
                .orElseThrow(() -> notFound("Unknown player"));
        Team team = ensureTeam(room, blankTo(teamName, "Team"));
        room.getPlayers().put(playerId, player.withTeam(team.id()));
        room.bumpRevision();
        return snapshotFor(room, playerId);
    }

    public Optional<GameSnapshot> findByCode(String code) {
        String roomId = roomIdByCode.get(normalizeCode(code));
        if (roomId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(roomsById.get(roomId)).map(GameRoom::publicSnapshot);
    }

    public GameSnapshot requireSnapshot(String roomId) {
        return requireById(roomId).publicSnapshot();
    }

    public GameSnapshot requireSnapshot(String roomId, String playerId) {
        return snapshotFor(requireById(roomId), playerId);
    }

    public GameSnapshot applyAction(String roomId, GameAction action) {
        String type = action.type() == null ? "" : action.type().toUpperCase(Locale.ROOT);
        String playerId = action.playerId();
        Map<String, Object> payload = action.payload() == null ? Map.of() : action.payload();
        return switch (type) {
            case "START" -> startGame(roomId, playerId);
            case "SELECT_CLUE" -> selectClue(roomId, playerId, stringPayload(payload, "clueId"));
            case "OPEN_BUZZERS", "SHOW_CLUE" -> openBuzzers(roomId, playerId);
            case "BUZZ" -> buzz(roomId, playerId);
            case "JUDGE" -> judge(roomId, playerId, boolPayload(payload, "correct", false));
            case "REVEAL" -> revealAnswer(roomId, playerId);
            case "RETURN_BOARD" -> returnToBoard(roomId, playerId);
            case "ADMIT_PLAYER" -> admitPlayer(roomId, playerId, stringPayload(payload, "playerId"));
            case "ADMIT_ALL" -> admitAll(roomId, playerId);
            case "CREATE_TEAM" -> createTeam(roomId, playerId, stringPayload(payload, "teamName"));
            case "SYNC" -> requireSnapshot(roomId, playerId);
            default -> throw conflict("Unknown action: " + type);
        };
    }

    public GameSnapshot publicSnapshot(String roomId) {
        return requireById(roomId).publicSnapshot();
    }

    public GameSnapshot hostSnapshot(String roomId) {
        return requireById(roomId).hostSnapshot();
    }

    private GameSnapshot snapshotFor(GameRoom room, String playerId) {
        if (playerId != null && playerId.equals(room.getHostPlayerId())) {
            return room.hostSnapshot();
        }
        return room.publicSnapshot();
    }

    private void markAnswered(GameRoom room, String clueId) {
        BoardCellState cell = room.getCells().get(clueId);
        if (cell != null) {
            room.getCells().put(clueId, new BoardCellState(
                    cell.clueId(), cell.categoryId(), cell.value(), true, cell.dailyDouble()
            ));
        }
    }

    private ActiveClue withResponseVisible(ActiveClue active, boolean visible) {
        return new ActiveClue(
                active.clueId(),
                active.categoryId(),
                active.categoryTitle(),
                active.value(),
                active.prompt(),
                active.response(),
                active.explanation(),
                active.sourcePath(),
                active.dailyDouble(),
                visible,
                active.buzzedPlayerId(),
                active.buzzedPlayerName(),
                active.buzzedTeamId(),
                active.buzzedTeamName(),
                active.buzzedTeamColor()
        );
    }

    private Team ensureTeam(GameRoom room, String teamName) {
        Optional<Team> existing = room.getTeams().values().stream()
                .filter(t -> t.name().equalsIgnoreCase(teamName))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        if (room.getTeams().size() >= maxTeams) {
            throw conflict("Maximum number of teams reached");
        }
        String id = UUID.randomUUID().toString();
        String color = TEAM_COLORS[room.getTeams().size() % TEAM_COLORS.length];
        Team team = new Team(id, teamName, 0, color);
        room.getTeams().put(id, team);
        return team;
    }

    private GameRoom requireById(String roomId) {
        GameRoom room = roomsById.get(roomId);
        if (room == null) {
            throw notFound("Room not found");
        }
        return room;
    }

    private GameRoom requireByCode(String code) {
        String roomId = roomIdByCode.get(normalizeCode(code));
        if (roomId == null) {
            throw notFound("Room code not found");
        }
        return requireById(roomId);
    }

    private void requireHost(GameRoom room, String playerId) {
        if (!room.getHostPlayerId().equals(playerId)) {
            throw conflict("Only the host can perform this action");
        }
    }

    private String allocateCode() {
        for (int attempt = 0; attempt < 50; attempt++) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
            String code = sb.toString();
            if (!roomIdByCode.containsKey(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Unable to allocate room code");
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stringPayload(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw conflict("Missing payload field: " + key);
        }
        return String.valueOf(value);
    }

    private static boolean boolPayload(Map<String, Object> payload, String key, boolean defaultValue) {
        Object value = payload.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public List<String> listRoomCodes() {
        return new ArrayList<>(roomIdByCode.keySet());
    }
}
