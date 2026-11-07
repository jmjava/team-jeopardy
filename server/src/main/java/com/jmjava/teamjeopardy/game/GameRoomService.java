package com.jmjava.teamjeopardy.game;

import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Service
public class GameRoomService {

    private static final String[] TEAM_COLORS = {
            "#E8C547", "#3D8BFF", "#E85D4C", "#3ECF8E", "#C084FC", "#FB923C"
    };
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final Map<String, GameRoom> roomsById = new ConcurrentHashMap<>();
    private final Map<String, String> roomIdByCode = new ConcurrentHashMap<>();
    /** Join secret. Never copied onto a snapshot; player ids are already public. */
    private final Map<String, String> playerTokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Value("${team-jeopardy.max-teams:6}")
    private int maxTeams;

    @Value("${team-jeopardy.max-players-per-team:8}")
    private int maxPlayersPerTeam;

    @Value("${team-jeopardy.final.writing-seconds:30}")
    private int finalWritingSeconds;

    public record CreateRoomResult(
            GameSnapshot snapshot,
            String hostPlayerId,
            String hostName,
            String hostToken
    ) {
    }

    /**
     * These actions name a contestant. The id is on every public snapshot, so
     * the join secret is required. Without it, a spectator can read or replace
     * that seat's sealed Final wager and answer.
     */
    private static final Set<String> PLAYER_ACTIONS = Set.of(
            "BUZZ",
            "SET_WAGER",
            "SET_FINAL_ANSWER",
            "CREATE_TEAM"
    );

    private static final Set<String> HOST_ACTIONS = Set.of(
            "START",
            "SELECT_CLUE",
            "OPEN_BUZZERS",
            "SHOW_CLUE",
            "SELECT_WAGERER",
            "JUDGE",
            "REVEAL",
            "RETURN_BOARD",
            "START_FINAL",
            "START_FINAL_WAGER",
            "START_FINAL_WRITING",
            "LOCK_FINAL_ANSWERS",
            "END_WRITING",
            "JUDGE_FINAL",
            "ADMIT_PLAYER",
            "ADMIT_ALL"
    );

    public record JoinResult(GameSnapshot snapshot, String playerId, String playerToken) {
    }

    public CreateRoomResult createRoom(String hostName, String title) {
        String roomId = UUID.randomUUID().toString();
        String code = allocateCode(roomId);
        String hostId = UUID.randomUUID().toString();
        String hostToken = UUID.randomUUID().toString();
        String effectiveHost = blankTo(hostName, "Host");
        GameRoom room = new GameRoom(roomId, code, hostId, hostToken, blankTo(title, "Team Jeopardy"));
        room.getPlayers().put(hostId, new Player(hostId, effectiveHost, null, true, true, null));
        roomsById.put(roomId, room);
        return withRoom(room, r -> {
            r.bumpRevision();
            return new CreateRoomResult(r.hostSnapshot(), hostId, effectiveHost, hostToken);
        });
    }

    public JoinResult joinRoom(String code, String displayName, String teamName) {
        GameRoom room = requireByCode(code);
        return withRoom(room, r -> {
            if (r.getPhase() == GamePhase.FINISHED) {
                throw conflict("Room already finished");
            }
            String playerId = UUID.randomUUID().toString();
            String playerToken = UUID.randomUUID().toString();
            String name = blankTo(displayName, "Player");
            final String teamId;
            if (teamName != null && !teamName.isBlank()) {
                String ensuredTeamId = ensureTeam(r, teamName.trim()).id();
                long onTeam = r.getPlayers().values().stream()
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
            r.getPlayers().put(playerId, new Player(playerId, name, teamId, false, false, null));
            playerTokens.put(playerId, playerToken);
            r.bumpRevision();
            return new JoinResult(r.publicSnapshot(), playerId, playerToken);
        });
    }

    public GameSnapshot installBoard(String roomId, String playerId, Board board) {
        return installBoard(roomId, playerId, board, null);
    }

    public GameSnapshot installBoard(String roomId, String playerId, Board board, String questionHints) {
        return withRoomId(roomId, room -> {
            requireHost(room, playerId);
            room.installBoard(board, questionHints);
            return snapshotFor(room, playerId);
        });
    }

    public GameSnapshot admitPlayer(String roomId, String hostPlayerId, String targetPlayerId) {
        return withRoomId(roomId, room -> {
            requireHost(room, hostPlayerId);
            Player target = Optional.ofNullable(room.getPlayers().get(targetPlayerId))
                    .orElseThrow(() -> notFound("Unknown player"));
            if (target.host()) {
                throw conflict("Host is already in the room");
            }
            room.getPlayers().put(targetPlayerId, target.withAdmitted(true));
            room.bumpRevision();
            return room.hostSnapshot();
        });
    }

    public GameSnapshot admitAll(String roomId, String hostPlayerId) {
        return withRoomId(roomId, room -> {
            requireHost(room, hostPlayerId);
            for (Player player : List.copyOf(room.getPlayers().values())) {
                if (!player.host() && !player.admitted()) {
                    room.getPlayers().put(player.id(), player.withAdmitted(true));
                }
            }
            room.bumpRevision();
            return room.hostSnapshot();
        });
    }

    public GameSnapshot startGame(String roomId, String playerId) {
        return withRoomId(roomId, room -> {
            requireHost(room, playerId);
            if (room.getPhase() != GamePhase.LOBBY) {
                throw conflict("The game has already started");
            }
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
        });
    }

    public GameSnapshot selectClue(String roomId, String playerId, String clueId) {
        return withRoomId(roomId, room -> {
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
            room.clearLockouts();
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
                    null, null, null, null, null,
                    null
            ));
            // Host reads first; players see only a teaser until OPEN_BUZZERS.
            room.setPhase(GamePhase.HOST_PREVIEW);
            room.bumpRevision();
            return room.hostSnapshot();
        });
    }

    public GameSnapshot openBuzzers(String roomId, String hostPlayerId) {
        return withRoomId(roomId, room -> {
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
        });
    }

    public GameSnapshot selectWagerer(String roomId, String hostPlayerId, String targetPlayerId) {
        return withRoomId(roomId, room -> {
            requireHost(room, hostPlayerId);
            ActiveClue active = room.getActiveClue();
            if (active == null || !active.dailyDouble()) {
                throw conflict("A Daily Double must be open to select a wagerer");
            }
            if (room.getPhase() != GamePhase.HOST_PREVIEW && room.getPhase() != GamePhase.CLUE_OPEN) {
                throw conflict("Select the Daily Double player before the wager is locked");
            }
            Player player = requireAdmittedTeamedPlayer(room, targetPlayerId);
            Team team = room.getTeams().get(player.teamId());
            room.setActiveClue(active.withBuzz(
                    player.id(),
                    player.displayName(),
                    player.teamId(),
                    team == null ? null : team.name(),
                    team == null ? null : team.color()
            ));
            room.setPhase(GamePhase.DD_WAGER);
            room.bumpRevision();
            return room.hostSnapshot();
        });
    }

    public GameSnapshot buzz(String roomId, String playerId) {
        return withRoomId(roomId, room -> {
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
            if (room.isLockedOut(playerId)) {
                throw conflict("You already answered this clue");
            }
            ActiveClue active = room.getActiveClue();
            if (active == null) {
                throw conflict("No active clue");
            }
            if (active.buzzedPlayerId() != null) {
                throw conflict("Another player already buzzed in");
            }
            Team team = room.getTeams().get(player.teamId());
            room.setActiveClue(active.withBuzz(
                    player.id(),
                    player.displayName(),
                    player.teamId(),
                    team == null ? null : team.name(),
                    team == null ? null : team.color()
            ));
            room.setPhase(active.dailyDouble() ? GamePhase.DD_WAGER : GamePhase.BUZZ_LOCKED);
            room.bumpRevision();
            return snapshotFor(room, playerId);
        });
    }

    public GameSnapshot setWager(String roomId, String playerId, int amount) {
        return withRoomId(roomId, room -> {
            Player player = requireAdmittedTeamedPlayer(room, playerId);
            Team team = room.getTeams().get(player.teamId());
            if (team == null) {
                throw notFound("Team missing");
            }
            int wager;
            try {
                wager = WagerRules.requireValid(amount, team.score());
            } catch (IllegalArgumentException ex) {
                throw conflict(ex.getMessage());
            }

            if (room.getPhase() == GamePhase.DD_WAGER) {
                ActiveClue active = room.getActiveClue();
                if (active == null || !player.id().equals(active.buzzedPlayerId())) {
                    throw conflict("Only the selected Daily Double player can wager");
                }
                room.setActiveClue(active.withWager(wager));
                room.setPhase(GamePhase.BUZZ_LOCKED);
                room.bumpRevision();
                return snapshotFor(room, playerId);
            }

            if (room.getPhase() == GamePhase.FINAL_WAGER) {
                FinalRound round = requireFinal(room);
                List<FinalEntry> next = new ArrayList<>();
                boolean found = false;
                for (FinalEntry entry : round.entries()) {
                    if (player.teamId().equals(entry.teamId())) {
                        if (entry.wagerLocked()) {
                            throw conflict("This team's Final wager is already locked");
                        }
                        next.add(entry.withWager(wager, player.id(), player.displayName()));
                        found = true;
                    } else {
                        next.add(entry);
                    }
                }
                if (!found) {
                    throw conflict("Your team is not in Final Jeopardy");
                }
                room.setFinalRound(round.withEntries(next));
                room.bumpRevision();
                return snapshotFor(room, playerId);
            }

            throw conflict("Wagers are not open");
        });
    }

    public GameSnapshot judge(String roomId, String hostPlayerId, boolean correct) {
        return withRoomId(roomId, room -> {
            requireHost(room, hostPlayerId);
            if (room.getPhase() != GamePhase.BUZZ_LOCKED) {
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
            int delta = active.scoreDelta();
            int nextScore = ScoreMath.apply(team.score(), delta, correct);
            room.getTeams().put(team.id(), team.withScore(nextScore));

            if (correct) {
                room.setLastCorrectPlayerId(active.buzzedPlayerId());
                markAnswered(room, active.clueId());
                room.setActiveClue(active.withResponseVisible(true));
                room.setPhase(GamePhase.ANSWER_REVEALED);
            } else if (active.dailyDouble()) {
                markAnswered(room, active.clueId());
                room.setActiveClue(active.withResponseVisible(true));
                room.setPhase(GamePhase.ANSWER_REVEALED);
            } else {
                room.lockOut(active.buzzedPlayerId());
                room.setActiveClue(active.clearBuzz());
                room.setPhase(GamePhase.CLUE_OPEN);
            }
            room.bumpRevision();
            return room.hostSnapshot();
        });
    }

    public GameSnapshot revealAnswer(String roomId, String hostPlayerId) {
        return withRoomId(roomId, room -> {
            requireHost(room, hostPlayerId);
            if (room.getPhase().isFinalRound()) {
                return revealFinal(room);
            }
            ActiveClue active = room.getActiveClue();
            if (active == null) {
                throw conflict("No active clue");
            }
            if (room.getPhase() == GamePhase.ANSWER_REVEALED && active.responseVisible()) {
                return room.hostSnapshot();
            }
            markAnswered(room, active.clueId());
            room.setActiveClue(active.withResponseVisible(true));
            room.setPhase(GamePhase.ANSWER_REVEALED);
            room.bumpRevision();
            return room.hostSnapshot();
        });
    }

    public GameSnapshot returnToBoard(String roomId, String hostPlayerId) {
        return withRoomId(roomId, room -> {
            requireHost(room, hostPlayerId);
            room.setActiveClue(null);
            room.clearLockouts();
            if (room.getPhase() == GamePhase.FINAL_REVEALED || room.getPhase() == GamePhase.FINISHED) {
                room.setPhase(GamePhase.FINISHED);
            } else if (room.allCluesAnswered() && room.hasFinalJeopardy() && room.getFinalRound() == null) {
                beginFinal(room);
            } else if (room.allCluesAnswered()) {
                room.setPhase(GamePhase.FINISHED);
            } else {
                room.setPhase(GamePhase.BOARD);
            }
            room.bumpRevision();
            return room.hostSnapshot();
        });
    }

    public GameSnapshot startFinal(String roomId, String hostPlayerId) {
        return withRoomId(roomId, room -> {
            requireHost(room, hostPlayerId);
            if (room.getPhase() != GamePhase.BOARD && room.getPhase() != GamePhase.FINISHED) {
                throw conflict("Final Jeopardy can start from the board");
            }
            if (room.getPhase() == GamePhase.FINISHED && room.getFinalRound() != null) {
                throw conflict("Final Jeopardy already finished");
            }
            beginFinal(room);
            room.bumpRevision();
            return room.hostSnapshot();
        });
    }

    public GameSnapshot startFinalWager(String roomId, String hostPlayerId) {
        return withRoomId(roomId, room -> {
            requireHost(room, hostPlayerId);
            if (room.getPhase() != GamePhase.FINAL_CATEGORY) {
                throw conflict("Open Final wagers after the category is shown");
            }
            room.setPhase(GamePhase.FINAL_WAGER);
            room.bumpRevision();
            return room.hostSnapshot();
        });
    }

    public GameSnapshot startFinalWriting(String roomId, String hostPlayerId, Integer seconds) {
        return withRoomId(roomId, room -> {
            requireHost(room, hostPlayerId);
            if (room.getPhase() != GamePhase.FINAL_WAGER) {
                throw conflict("Writing starts after wagers");
            }
            FinalRound round = requireFinal(room);
            int secs = seconds == null || seconds < 5 ? finalWritingSeconds : Math.min(seconds, 180);
            List<FinalEntry> next = new ArrayList<>();
            for (FinalEntry entry : round.entries()) {
                if (entry.wagerLocked()) {
                    next.add(entry);
                } else {
                    next.add(entry.withWager(WagerRules.MIN_WAGER, entry.playerId(), entry.playerName()));
                }
            }
            room.setFinalRound(round.withEntries(next).withWriting(Instant.now().plusSeconds(secs), secs));
            room.setPhase(GamePhase.FINAL_WRITING);
            room.bumpRevision();
            return room.hostSnapshot();
        });
    }

    public GameSnapshot setFinalAnswer(String roomId, String playerId, String text) {
        return withRoomId(roomId, room -> {
            if (room.getPhase() != GamePhase.FINAL_WRITING) {
                throw conflict("Final answers are not being written");
            }
            Player player = requireAdmittedTeamedPlayer(room, playerId);
            FinalRound round = requireFinal(room);
            if (round.writingEndsAt() != null && Instant.now().isAfter(round.writingEndsAt())) {
                lockFinalAnswers(room);
                throw conflict("Writing time has ended");
            }
            String answer = text == null ? "" : text.trim();
            List<FinalEntry> next = new ArrayList<>();
            boolean found = false;
            for (FinalEntry entry : round.entries()) {
                if (player.teamId().equals(entry.teamId())) {
                    if (entry.answerLocked()) {
                        throw conflict("This team's Final answer is already locked");
                    }
                    next.add(entry.withAnswer(answer, player.id(), player.displayName()));
                    found = true;
                } else {
                    next.add(entry);
                }
            }
            if (!found) {
                throw conflict("Your team is not in Final Jeopardy");
            }
            room.setFinalRound(round.withEntries(next));
            room.bumpRevision();
            return snapshotFor(room, playerId);
        });
    }

    public GameSnapshot lockFinalAnswers(String roomId, String hostPlayerId) {
        return withRoomId(roomId, room -> {
            requireHost(room, hostPlayerId);
            if (room.getPhase() != GamePhase.FINAL_WRITING && room.getPhase() != GamePhase.FINAL_LOCKED) {
                throw conflict("Nothing to lock");
            }
            lockFinalAnswers(room);
            room.bumpRevision();
            return room.hostSnapshot();
        });
    }

    public GameSnapshot judgeFinal(String roomId, String hostPlayerId, String teamId, boolean correct) {
        return withRoomId(roomId, room -> {
            requireHost(room, hostPlayerId);
            if (room.getPhase() != GamePhase.FINAL_REVEALED && room.getPhase() != GamePhase.FINAL_LOCKED) {
                throw conflict("Reveal the official response before judging Final");
            }
            if (room.getPhase() == GamePhase.FINAL_LOCKED) {
                room.setPhase(GamePhase.FINAL_REVEALED);
            }
            FinalRound round = requireFinal(room);
            Team team = room.getTeams().get(teamId);
            if (team == null) {
                throw notFound("Unknown team");
            }
            List<FinalEntry> next = new ArrayList<>();
            boolean found = false;
            for (FinalEntry entry : round.entries()) {
                if (teamId.equals(entry.teamId())) {
                    if (entry.correct() != null) {
                        throw conflict("This team has already been judged");
                    }
                    int wager = entry.wager() == null ? 0 : entry.wager();
                    int nextScore = ScoreMath.apply(team.score(), wager, correct);
                    room.getTeams().put(team.id(), team.withScore(nextScore));
                    next.add(entry.withJudgement(correct));
                    found = true;
                } else {
                    next.add(entry);
                }
            }
            if (!found) {
                throw conflict("Team is not in Final Jeopardy");
            }
            room.setFinalRound(round.withEntries(next));
            boolean allJudged = next.stream().allMatch(e -> e.correct() != null);
            if (allJudged) {
                room.setPhase(GamePhase.FINISHED);
            }
            room.bumpRevision();
            return room.hostSnapshot();
        });
    }

    public GameSnapshot createTeam(String roomId, String playerId, String teamName) {
        return withRoomId(roomId, room -> {
            Player player = Optional.ofNullable(room.getPlayers().get(playerId))
                    .orElseThrow(() -> notFound("Unknown player"));
            Team team = ensureTeam(room, blankTo(teamName, "Team"));
            room.getPlayers().put(playerId, player.withTeam(team.id()));
            room.bumpRevision();
            return snapshotFor(room, playerId);
        });
    }

    public Optional<GameSnapshot> findByCode(String code) {
        String roomId = roomIdByCode.get(normalizeCode(code));
        if (roomId == null) {
            return Optional.empty();
        }
        GameRoom room = roomsById.get(roomId);
        if (room == null) {
            return Optional.empty();
        }
        return Optional.of(withRoom(room, GameRoom::publicSnapshot));
    }

    public GameSnapshot requireSnapshot(String roomId) {
        return publicSnapshot(roomId);
    }

    public GameSnapshot requireSnapshot(String roomId, String playerId) {
        return withRoomId(roomId, room -> snapshotFor(room, playerId));
    }

    public GameSnapshot applyAction(String roomId, GameAction action) {
        return applyAction(roomId, action, null);
    }

    /**
     * @param hostToken secret from room creation; required for moderator actions and a host SYNC view
     */
    public GameSnapshot applyAction(String roomId, GameAction action, String hostToken) {
        return applyAction(roomId, action, hostToken, null);
    }

    /**
     * @param playerToken secret from join; required for contestant actions and that seat's Final line
     */
    public GameSnapshot applyAction(String roomId, GameAction action, String hostToken, String playerToken) {
        String type = action.type() == null ? "" : action.type().toUpperCase(Locale.ROOT);
        String playerId = action.playerId();
        Map<String, Object> payload = action.payload() == null ? Map.of() : action.payload();
        if (HOST_ACTIONS.contains(type)) {
            assertHostCredential(roomId, playerId, hostToken);
        }
        if (PLAYER_ACTIONS.contains(type)) {
            assertPlayerCredential(playerId, playerToken);
        }
        return switch (type) {
            case "START" -> startGame(roomId, playerId);
            case "SELECT_CLUE" -> selectClue(roomId, playerId, stringPayload(payload, "clueId"));
            case "OPEN_BUZZERS", "SHOW_CLUE" -> openBuzzers(roomId, playerId);
            case "SELECT_WAGERER" -> selectWagerer(roomId, playerId, stringPayload(payload, "playerId"));
            case "BUZZ" -> buzz(roomId, playerId);
            case "SET_WAGER" -> setWager(roomId, playerId, intPayload(payload, "amount"));
            case "JUDGE" -> judge(roomId, playerId, boolPayload(payload, "correct", false));
            case "REVEAL" -> revealAnswer(roomId, playerId);
            case "RETURN_BOARD" -> returnToBoard(roomId, playerId);
            case "START_FINAL" -> startFinal(roomId, playerId);
            case "START_FINAL_WAGER" -> startFinalWager(roomId, playerId);
            case "START_FINAL_WRITING" -> startFinalWriting(roomId, playerId, optionalIntPayload(payload, "seconds"));
            case "SET_FINAL_ANSWER" -> setFinalAnswer(roomId, playerId, optionalStringPayload(payload, "text"));
            case "LOCK_FINAL_ANSWERS", "END_WRITING" -> lockFinalAnswers(roomId, playerId);
            case "JUDGE_FINAL" -> judgeFinal(
                    roomId,
                    playerId,
                    stringPayload(payload, "teamId"),
                    boolPayload(payload, "correct", false)
            );
            case "ADMIT_PLAYER" -> admitPlayer(roomId, playerId, stringPayload(payload, "playerId"));
            case "ADMIT_ALL" -> admitAll(roomId, playerId);
            case "CREATE_TEAM" -> createTeam(roomId, playerId, stringPayload(payload, "teamName"));
            case "SYNC" -> viewFor(roomId, playerId, hostToken, playerToken);
            default -> throw conflict("Unknown action: " + type);
        };
    }

    /**
     * Host id is on every public snapshot, so it is not a secret. Moderator
     * actions and the unredacted snapshot require the create-room token.
     */
    public boolean hostCredentialMatches(String roomId, String playerId, String hostToken) {
        GameRoom room = roomsById.get(roomId);
        if (room == null || playerId == null || hostToken == null) {
            return false;
        }
        return withRoom(room, r -> playerId.equals(r.getHostPlayerId()) && tokenMatches(r.getHostToken(), hostToken));
    }

    public void assertHostCredential(String roomId, String playerId, String hostToken) {
        if (!hostCredentialMatches(roomId, playerId, hostToken)) {
            throw conflict("Only the host can perform this action");
        }
    }

    public boolean playerCredentialMatches(String playerId, String playerToken) {
        if (playerId == null || playerToken == null) {
            return false;
        }
        return tokenMatches(playerTokens.get(playerId), playerToken);
    }

    public void assertPlayerCredential(String playerId, String playerToken) {
        if (!playerCredentialMatches(playerId, playerToken)) {
            throw conflict("Player credential required");
        }
    }

    /**
     * Host view only when the create-room token is presented.
     * A seated player sees their own Final line only with the join secret.
     * Everyone else gets the public snapshot.
     */
    public GameSnapshot viewFor(String roomId, String playerId, String hostToken) {
        return viewFor(roomId, playerId, hostToken, null);
    }

    public GameSnapshot viewFor(String roomId, String playerId, String hostToken, String playerToken) {
        return withRoomId(roomId, room -> {
            if (playerId != null && playerId.equals(room.getHostPlayerId()) && tokenMatches(room.getHostToken(), hostToken)) {
                return room.hostSnapshot();
            }
            if (playerId != null
                    && !playerId.equals(room.getHostPlayerId())
                    && room.getPlayers().containsKey(playerId)
                    && playerCredentialMatches(playerId, playerToken)) {
                return room.playerSnapshot(playerId);
            }
            return room.publicSnapshot();
        });
    }

    public GameSnapshot publicSnapshot(String roomId) {
        return withRoomId(roomId, GameRoom::publicSnapshot);
    }

    public GameSnapshot hostSnapshot(String roomId) {
        return withRoomId(roomId, GameRoom::hostSnapshot);
    }

    /**
     * Public and host views of the same revision, captured under one room lock
     * so a concurrent buzz/judge cannot split the pair mid-broadcast.
     */
    public record SnapshotPair(GameSnapshot pub, GameSnapshot host) {
    }

    public SnapshotPair snapshotPair(String roomId) {
        return withRoomId(roomId, room -> new SnapshotPair(room.publicSnapshot(), room.hostSnapshot()));
    }

    private void beginFinal(GameRoom room) {
        if (!room.hasFinalJeopardy()) {
            throw conflict("This board has no Final Jeopardy clue");
        }
        if (room.getFinalRound() != null && room.getPhase().isFinalRound()) {
            throw conflict("Final Jeopardy is already in progress");
        }
        Clue clue = room.getBoard().finalJeopardy();
        List<FinalEntry> entries = new ArrayList<>();
        for (Team team : room.getTeams().values()) {
            boolean playing = room.getPlayers().values().stream()
                    .anyMatch(p -> !p.host() && p.admitted() && team.id().equals(p.teamId()));
            if (!playing) {
                continue;
            }
            entries.add(new FinalEntry(
                    team.id(), team.name(), team.color(),
                    null, null, null, false, null, false, null
            ));
        }
        if (entries.isEmpty()) {
            throw conflict("Admit at least one teamed player before Final Jeopardy");
        }
        room.setActiveClue(null);
        room.setFinalRound(new FinalRound(
                room.getBoard().finalCategoryLabel(),
                clue.prompt(),
                clue.response(),
                clue.explanation(),
                clue.sourcePath(),
                null,
                null,
                entries
        ));
        room.setPhase(GamePhase.FINAL_CATEGORY);
    }

    private GameSnapshot revealFinal(GameRoom room) {
        if (room.getPhase() != GamePhase.FINAL_LOCKED && room.getPhase() != GamePhase.FINAL_REVEALED) {
            if (room.getPhase() == GamePhase.FINAL_WRITING) {
                lockFinalAnswers(room);
            } else {
                throw conflict("Lock Final answers before revealing");
            }
        }
        room.setPhase(GamePhase.FINAL_REVEALED);
        room.bumpRevision();
        return room.hostSnapshot();
    }

    private void lockFinalAnswers(GameRoom room) {
        FinalRound round = requireFinal(room);
        List<FinalEntry> next = round.entries().stream().map(FinalEntry::lockAnswer).toList();
        room.setFinalRound(round.withEntries(next));
        room.setPhase(GamePhase.FINAL_LOCKED);
    }

    private static FinalRound requireFinal(GameRoom room) {
        FinalRound round = room.getFinalRound();
        if (round == null) {
            throw conflict("Final Jeopardy has not started");
        }
        return round;
    }

    private GameSnapshot snapshotFor(GameRoom room, String playerId) {
        if (playerId != null && playerId.equals(room.getHostPlayerId())) {
            return room.hostSnapshot();
        }
        return room.playerSnapshot(playerId);
    }

    private void markAnswered(GameRoom room, String clueId) {
        BoardCellState cell = room.getCells().get(clueId);
        if (cell != null) {
            room.getCells().put(clueId, new BoardCellState(
                    cell.clueId(), cell.categoryId(), cell.value(), true, cell.dailyDouble()
            ));
        }
    }

    private Player requireAdmittedTeamedPlayer(GameRoom room, String playerId) {
        Player player = Optional.ofNullable(room.getPlayers().get(playerId))
                .orElseThrow(() -> notFound("Unknown player"));
        if (player.host() || !player.admitted()) {
            throw conflict("Only admitted players can do this");
        }
        if (player.teamId() == null) {
            throw conflict("Join a team first");
        }
        return player;
    }

    private Team ensureTeam(GameRoom room, String teamName) {
        if (room.getPhase() != GamePhase.LOBBY) {
            throw conflict("Teams can only be chosen in the lobby");
        }
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

    private <T> T withRoomId(String roomId, Function<GameRoom, T> action) {
        return withRoom(requireById(roomId), action);
    }

    private <T> T withRoom(GameRoom room, Function<GameRoom, T> action) {
        synchronized (room.lock()) {
            return action.apply(room);
        }
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

    private String allocateCode(String roomId) {
        for (int attempt = 0; attempt < 50; attempt++) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
            String code = sb.toString();
            if (roomIdByCode.putIfAbsent(code, roomId) == null) {
                return code;
            }
        }
        throw new IllegalStateException("Unable to allocate room code");
    }

    private static boolean tokenMatches(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8)
        );
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

    private static String optionalStringPayload(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
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

    private static int intPayload(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw conflict("Missing payload field: " + key);
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            throw conflict("Invalid number for " + key);
        }
    }

    private static Integer optionalIntPayload(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw conflict("Invalid number for " + key);
        }
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
