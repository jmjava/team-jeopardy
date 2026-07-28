package com.jmjava.teamjeopardy.api;

import com.jmjava.teamjeopardy.game.GameAction;
import com.jmjava.teamjeopardy.game.GameRoomService;
import com.jmjava.teamjeopardy.game.GameSnapshot;
import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.skgraph.SkgraphIngestService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class GameController {

    private final GameRoomService gameRoomService;
    private final SkgraphIngestService skgraphIngestService;
    private final BoardFactory boardFactory;
    private final SimpMessagingTemplate messagingTemplate;

    public GameController(
            GameRoomService gameRoomService,
            SkgraphIngestService skgraphIngestService,
            BoardFactory boardFactory,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.gameRoomService = gameRoomService;
        this.skgraphIngestService = skgraphIngestService;
        this.boardFactory = boardFactory;
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
        broadcast(joined.snapshot());
        return new Dto.JoinRoomResponse(joined.snapshot(), joined.playerId());
    }

    @GetMapping("/rooms/{roomId}")
    public GameSnapshot getRoom(@PathVariable String roomId) {
        return gameRoomService.requireSnapshot(roomId);
    }

    @GetMapping("/rooms/code/{code}")
    public GameSnapshot getByCode(@PathVariable String code) {
        return gameRoomService.findByCode(code)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Room code not found"));
    }

    @PostMapping("/rooms/ingest")
    public Dto.IngestResponse ingest(@Valid @RequestBody Dto.IngestRequest request) throws IOException {
        boolean useSample = request.useSample() == null || request.useSample();
        BoardFactory.BuiltBoard built = useSample
                ? boardFactory.fromSample(request.boardTitle())
                : boardFactory.fromPath(
                        Path.of(request.path()),
                        request.repo(),
                        request.branch(),
                        request.commitSha(),
                        request.boardTitle()
                );

        Board board = built.board();
        GameSnapshot snapshot = gameRoomService.installBoard(request.roomId(), request.playerId(), board);
        broadcast(snapshot);
        return new Dto.IngestResponse(snapshot, board, built.summary());
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
        broadcast(snapshot);
        return snapshot;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "samplePath", skgraphIngestService.sampleCodePath(),
                "engine", "skgraph-core"
        );
    }

    private void broadcast(GameSnapshot snapshot) {
        messagingTemplate.convertAndSend("/topic/room." + snapshot.roomId(), snapshot);
        messagingTemplate.convertAndSend("/topic/room-code." + snapshot.code(), snapshot);
    }
}
