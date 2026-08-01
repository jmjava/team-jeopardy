package com.jmjava.teamjeopardy.api;

import com.jmjava.teamjeopardy.game.GameAction;
import com.jmjava.teamjeopardy.game.GameRoomService;
import com.jmjava.teamjeopardy.game.GameSnapshot;
import com.jmjava.teamjeopardy.github.GitHubRepoFetcher;
import com.jmjava.teamjeopardy.persist.QuestionBankService;
import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.QuestionHints;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class GameController {

    private final GameRoomService gameRoomService;
    private final BoardFactory boardFactory;
    private final QuestionBankService questionBankService;
    private final SimpMessagingTemplate messagingTemplate;

    public GameController(
            GameRoomService gameRoomService,
            BoardFactory boardFactory,
            QuestionBankService questionBankService,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.gameRoomService = gameRoomService;
        this.boardFactory = boardFactory;
        this.questionBankService = questionBankService;
        this.messagingTemplate = messagingTemplate;
    }

    @PostMapping("/rooms")
    public Dto.CreateRoomResponse createRoom(@RequestBody Dto.CreateRoomRequest request) {
        var created = gameRoomService.createRoom(
                request == null ? null : request.hostName(),
                request == null ? null : request.title()
        );
        return new Dto.CreateRoomResponse(created.snapshot(), created.hostPlayerId(), created.hostName());
    }

    @PostMapping("/rooms/join")
    public Dto.JoinRoomResponse join(@Valid @RequestBody Dto.JoinRoomRequest request) {
        var joined = gameRoomService.joinRoom(request.code(), request.displayName(), request.teamName());
        broadcast(joined.snapshot().roomId());
        return new Dto.JoinRoomResponse(joined.snapshot(), joined.playerId());
    }

    @GetMapping("/rooms/{roomId}")
    public GameSnapshot getRoom(
            @PathVariable String roomId,
            @RequestParam(required = false) String playerId
    ) {
        return gameRoomService.requireSnapshot(roomId, playerId);
    }

    @GetMapping("/rooms/code/{code}")
    public GameSnapshot getByCode(@PathVariable String code) {
        return gameRoomService.findByCode(code)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Room code not found"));
    }

    @PostMapping("/rooms/ingest")
    public Dto.IngestResponse ingest(@Valid @RequestBody Dto.IngestRequest request) throws IOException {
        BoardFactory.BuiltBoard built = buildBoard(request);
        Board board = built.board();
        QuestionHints hints = QuestionHints.of(request.questionHints(), request.questionFocuses());
        Map<String, Object> summary = new LinkedHashMap<>(built.summary());
        persistGeneratedBoard(request, board, summary, hints);
        GameSnapshot snapshot = gameRoomService.installBoard(
                request.roomId(),
                request.playerId(),
                board,
                hints.combined()
        );
        broadcast(request.roomId());
        return new Dto.IngestResponse(snapshot, board, summary);
    }

    /**
     * Admin helper: list directories in a GitHub repo for selective ingest.
     */
    @PostMapping("/github/browse")
    public Map<String, Object> browseGithub(@Valid @RequestBody Dto.GitHubBrowseRequest request) throws IOException {
        GitHubRepoFetcher fetcher = boardFactory.repoFetcher();
        String ref = request.ref() == null || request.ref().isBlank() ? "HEAD" : request.ref();
        List<GitHubRepoFetcher.RepoFolder> folders = Boolean.TRUE.equals(request.recursive())
                ? fetcher.listDirectoriesRecursive(request.repo(), ref, 400)
                : fetcher.listFolders(request.repo(), ref, request.path());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repo", request.repo());
        body.put("ref", ref);
        body.put("path", request.path() == null ? "" : request.path());
        body.put("folders", folders);
        return body;
    }

    private BoardFactory.BuiltBoard buildBoard(Dto.IngestRequest request) throws IOException {
        QuestionHints hints = QuestionHints.of(request.questionHints(), request.questionFocuses());
        String sampleType = request.sampleType();

        if (BoardFactory.isPullsType(sampleType)) {
            String repo = blankTo(request.repo(), boardFactory.defaultRepo());
            return boardFactory.fromPullRequests(repo, request.prLimit(), request.boardTitle(), hints);
        }

        if (BoardFactory.isGitHubType(sampleType)
                || (request.repo() != null && !request.repo().isBlank()
                && Boolean.FALSE.equals(request.useSample())
                && (request.path() == null || request.path().isBlank()))) {
            String repo = blankTo(request.repo(), boardFactory.defaultRepo());
            String ref = BoardFactory.resolveRef(request.ref(), request.branch(), request.commitSha());
            boolean includePulls = request.includePulls() == null || request.includePulls();
            return boardFactory.fromGitHub(
                    repo,
                    ref,
                    request.folders(),
                    includePulls,
                    request.prLimit(),
                    request.boardTitle(),
                    hints
            );
        }

        boolean useSample = request.useSample() == null || request.useSample();
        if (useSample) {
            return boardFactory.fromSample(sampleType, request.boardTitle(), hints);
        }
        if (request.repo() != null && !request.repo().isBlank() && request.path() != null) {
            return boardFactory.fromPathWithPullRequests(
                    Path.of(request.path()),
                    request.boardTitle(),
                    null,
                    request.repo(),
                    request.prLimit(),
                    hints
            );
        }
        return boardFactory.fromPath(Path.of(request.path()), request.boardTitle(), null, hints);
    }

    @PostMapping("/rooms/{roomId}/actions")
    public GameSnapshot action(
            @PathVariable String roomId,
            @Valid @RequestBody Dto.ActionRequest request
    ) {
        GameSnapshot snapshot = gameRoomService.applyAction(
                roomId,
                new GameAction(request.type(), request.playerId(), null, request.payload())
        );
        broadcast(roomId);
        return snapshot;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("engine", "team-jeopardy-ingest");
        body.put("supported", new String[]{
                "maven", "gradle", "vue", "npm", "python", "github", "pulls", "generic"
        });
        body.put("samples", boardFactory.samplePaths());
        body.put("defaultRepo", boardFactory.defaultRepo());
        body.put("questionFocuses", List.of(
                "patterns", "pull-requests", "qa", "apis", "architecture", "components", "security"
        ));
        body.put("questionBank", questionBankService.status());
        return body;
    }

    private void persistGeneratedBoard(
            Dto.IngestRequest request,
            Board board,
            Map<String, Object> summary,
            QuestionHints hints
    ) {
        String sampleType = request.sampleType();
        String sourceKind;
        String sourceKey;
        if (BoardFactory.isPullsType(sampleType)) {
            sourceKind = "pulls";
            sourceKey = QuestionBankService.sourceKeyForPulls(blankTo(request.repo(), boardFactory.defaultRepo()));
        } else if (BoardFactory.isGitHubType(sampleType)
                || (request.repo() != null && !request.repo().isBlank()
                && Boolean.FALSE.equals(request.useSample())
                && (request.path() == null || request.path().isBlank()))) {
            sourceKind = "github";
            String repo = blankTo(request.repo(), boardFactory.defaultRepo());
            String ref = BoardFactory.resolveRef(request.ref(), request.branch(), request.commitSha());
            sourceKey = QuestionBankService.sourceKeyForGithub(repo, ref, request.folders());
        } else if (request.useSample() == null || request.useSample()) {
            sourceKind = "sample";
            sourceKey = QuestionBankService.sourceKeyForSample(sampleType);
        } else {
            sourceKind = "path";
            sourceKey = QuestionBankService.sourceKeyForPath(request.path());
        }

        questionBankService.saveGenerated(board, summary, sourceKind, sourceKey, hints)
                .ifPresent(saved -> {
                    summary.put("savedBoardId", saved.id());
                    summary.put("fingerprint", saved.fingerprint());
                    summary.put("persisted", true);
                });
    }

    private void broadcast(String roomId) {
        GameSnapshot pub = gameRoomService.publicSnapshot(roomId);
        GameSnapshot host = gameRoomService.hostSnapshot(roomId);
        messagingTemplate.convertAndSend("/topic/room." + pub.roomId(), pub);
        messagingTemplate.convertAndSend("/topic/room-code." + pub.code(), pub);
        messagingTemplate.convertAndSend("/topic/room." + host.roomId() + ".host", host);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
