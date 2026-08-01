package com.jmjava.teamjeopardy.api;

import com.jmjava.teamjeopardy.game.GameRoomService;
import com.jmjava.teamjeopardy.game.GameSnapshot;
import com.jmjava.teamjeopardy.persist.QuestionBankService;
import com.jmjava.teamjeopardy.persist.SavedBoardRecord;
import com.jmjava.teamjeopardy.persist.SavedBoardSummary;
import com.jmjava.teamjeopardy.persist.SavedClueRecord;
import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import com.jmjava.teamjeopardy.quiz.QuestionHints;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class QuestionBankController {

    private final QuestionBankService questionBankService;
    private final GameRoomService gameRoomService;
    private final SimpMessagingTemplate messagingTemplate;

    public QuestionBankController(
            QuestionBankService questionBankService,
            GameRoomService gameRoomService,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.questionBankService = questionBankService;
        this.gameRoomService = gameRoomService;
        this.messagingTemplate = messagingTemplate;
    }

    @GetMapping("/question-bank")
    public Map<String, Object> list(@RequestParam(defaultValue = "100") int limit) {
        List<SavedBoardSummary> boards = questionBankService.list(limit);
        Map<String, Object> body = new LinkedHashMap<>(questionBankService.status());
        body.put("count", boards.size());
        body.put("boards", boards);
        return body;
    }

    @GetMapping("/question-bank/status")
    public Map<String, Object> status() {
        return questionBankService.status();
    }

    @GetMapping("/question-bank/clues")
    public Map<String, Object> searchClues(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "100") int limit
    ) {
        List<SavedClueRecord> clues = questionBankService.searchClues(q, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", q == null ? "" : q);
        body.put("count", clues.size());
        body.put("clues", clues);
        return body;
    }

    @GetMapping("/question-bank/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        SavedBoardRecord record = questionBankService.get(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("board", record);
        body.put("clues", questionBankService.listCluesForBoard(id));
        return body;
    }

    @PostMapping("/question-bank")
    @ResponseStatus(HttpStatus.CREATED)
    public SavedBoardRecord create(@Valid @RequestBody Dto.CreateSavedBoardRequest request) {
        List<Category> categories = new ArrayList<>();
        if (request.categories() != null) {
            for (Dto.CategoryInput cat : request.categories()) {
                List<Clue> clues = new ArrayList<>();
                if (cat.clues() != null) {
                    for (Dto.ClueInput clue : cat.clues()) {
                        clues.add(new Clue(
                                clue.id(),
                                clue.value() == null ? 200 : clue.value(),
                                clue.prompt(),
                                clue.response(),
                                clue.explanation(),
                                clue.sourcePath(),
                                Boolean.TRUE.equals(clue.dailyDouble())
                        ));
                    }
                }
                categories.add(new Category(cat.id(), cat.title(), clues));
            }
        }
        return questionBankService.createManual(
                request.title(),
                request.sourceKind(),
                request.sourceKey(),
                request.sourceRoot(),
                QuestionHints.of(request.questionHints(), request.questionFocuses()),
                categories,
                new Board.GraphDigest(0, 0, 0, 0, 0)
        );
    }

    @PostMapping("/question-bank/{id}/clues")
    @ResponseStatus(HttpStatus.CREATED)
    public SavedClueRecord addClue(
            @PathVariable String id,
            @Valid @RequestBody Dto.AddClueRequest request
    ) {
        return questionBankService.addClue(
                id,
                request.categoryId(),
                request.categoryTitle(),
                request.value(),
                request.prompt(),
                request.response(),
                request.explanation(),
                request.sourcePath(),
                request.dailyDouble()
        );
    }

    @DeleteMapping("/question-bank/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean deleted = questionBankService.delete(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", deleted);
        body.put("id", id);
        return body;
    }

    @DeleteMapping("/question-bank/clues/{clueId}")
    public Map<String, Object> deleteClue(@PathVariable String clueId) {
        boolean deleted = questionBankService.deleteClue(clueId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", deleted);
        body.put("id", clueId);
        return body;
    }

    @DeleteMapping("/question-bank")
    public Map<String, Object> deleteAll() {
        int deleted = questionBankService.deleteAll();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deletedBoards", deleted);
        return body;
    }

    /**
     * Install a previously saved guide-generated board into a room (no re-ingest).
     */
    @PostMapping("/rooms/load-board")
    public Dto.IngestResponse loadBoard(@Valid @RequestBody Dto.LoadBoardRequest request) {
        Board board = questionBankService.loadBoard(request.savedBoardId());
        SavedBoardRecord meta = questionBankService.get(request.savedBoardId());
        GameSnapshot snapshot = gameRoomService.installBoard(
                request.roomId(),
                request.playerId(),
                board,
                meta.questionHints()
        );
        broadcast(request.roomId());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", "question-bank");
        summary.put("savedBoardId", meta.id());
        summary.put("sourceKind", meta.sourceKind());
        summary.put("sourceKey", meta.sourceKey());
        summary.put("fingerprint", meta.fingerprint());
        summary.put("categories", meta.categoryCount());
        summary.put("clues", meta.clueCount());
        summary.put("questionHints", meta.questionHints());
        return new Dto.IngestResponse(snapshot, board, summary);
    }

    private void broadcast(String roomId) {
        GameSnapshot pub = gameRoomService.publicSnapshot(roomId);
        GameSnapshot host = gameRoomService.hostSnapshot(roomId);
        messagingTemplate.convertAndSend("/topic/room." + pub.roomId(), pub);
        messagingTemplate.convertAndSend("/topic/room-code." + pub.code(), pub);
        messagingTemplate.convertAndSend("/topic/room." + host.roomId() + ".host", host);
    }
}
