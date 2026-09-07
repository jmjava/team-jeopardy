package com.jmjava.teamjeopardy.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class QuestionBankController {

    private final QuestionBankService questionBankService;
    private final GameRoomService gameRoomService;
    private final GameBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    public QuestionBankController(
            QuestionBankService questionBankService,
            GameRoomService gameRoomService,
            GameBroadcaster broadcaster,
            ObjectMapper objectMapper
    ) {
        this.questionBankService = questionBankService;
        this.gameRoomService = gameRoomService;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
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

    /**
     * Export saved boards in the same JSON shape accepted by bulk upload.
     */
    @GetMapping("/question-bank/export")
    public ResponseEntity<Map<String, Object>> export(@RequestParam(defaultValue = "200") int limit) {
        List<SavedBoardSummary> summaries = questionBankService.list(limit);
        List<Map<String, Object>> boards = new ArrayList<>();
        for (SavedBoardSummary summary : summaries) {
            SavedBoardRecord record = questionBankService.get(summary.id());
            Map<String, Object> board = new LinkedHashMap<>();
            board.put("title", record.title());
            board.put("sourceKind", record.sourceKind());
            board.put("sourceKey", record.sourceKey());
            board.put("sourceRoot", record.sourceRoot());
            board.put("questionHints", record.questionHints());
            board.put("questionFocuses", record.questionFocuses());
            List<Map<String, Object>> categories = new ArrayList<>();
            if (record.board() != null && record.board().categories() != null) {
                for (Category category : record.board().categories()) {
                    Map<String, Object> cat = new LinkedHashMap<>();
                    cat.put("id", category.id());
                    cat.put("title", category.title());
                    List<Map<String, Object>> clues = new ArrayList<>();
                    if (category.clues() != null) {
                        for (Clue clue : category.clues()) {
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("id", clue.id());
                            c.put("value", clue.value());
                            c.put("prompt", clue.prompt());
                            c.put("response", clue.response());
                            c.put("explanation", clue.explanation());
                            c.put("sourcePath", clue.sourcePath());
                            c.put("dailyDouble", clue.dailyDouble());
                            clues.add(c);
                        }
                    }
                    cat.put("clues", clues);
                    categories.add(cat);
                }
            }
            board.put("categories", categories);
            boards.add(board);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("skipDuplicates", true);
        payload.put("boards", boards);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"question-bank-export.json\"")
                .body(payload);
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
        return createFromRequest(request);
    }

    /**
     * Bulk upload boards as JSON. Body may be {@code {"boards":[...],"skipDuplicates":true}}
     * or a bare array of board objects.
     */
    @PostMapping(path = "/question-bank/bulk", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Dto.BulkUploadResponse bulkUploadJson(@RequestBody JsonNode body) {
        return toResponse(questionBankService.bulkCreate(
                parseBulkSpecs(body),
                readSkipDuplicates(body)
        ));
    }

    /**
     * Bulk upload from a JSON file (multipart field {@code file}).
     * Optional form field {@code skipDuplicates} (default true).
     */
    @PostMapping(path = "/question-bank/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Dto.BulkUploadResponse bulkUploadFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean skipDuplicates
    ) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
        }
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        JsonNode body = objectMapper.readTree(text);
        boolean skip = body.has("skipDuplicates")
                ? body.get("skipDuplicates").asBoolean(skipDuplicates)
                : skipDuplicates;
        return toResponse(questionBankService.bulkCreate(parseBulkSpecs(body), skip));
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
        broadcaster.broadcast(request.roomId());

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

    private SavedBoardRecord createFromRequest(Dto.CreateSavedBoardRequest request) {
        return questionBankService.createManual(
                request.title(),
                request.sourceKind(),
                request.sourceKey(),
                request.sourceRoot(),
                QuestionHints.of(request.questionHints(), request.questionFocuses()),
                toCategories(request.categories()),
                new Board.GraphDigest(0, 0, 0, 0, 0)
        );
    }

    private List<QuestionBankService.ManualBoardSpec> parseBulkSpecs(JsonNode body) {
        if (body == null || body.isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON body is required");
        }
        JsonNode boardsNode;
        if (body.isArray()) {
            boardsNode = body;
        } else if (body.has("boards") && body.get("boards").isArray()) {
            boardsNode = body.get("boards");
        } else {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Expected {\"boards\":[...]} or a JSON array of boards"
            );
        }
        List<QuestionBankService.ManualBoardSpec> specs = new ArrayList<>();
        for (JsonNode node : boardsNode) {
            Dto.CreateSavedBoardRequest request = objectMapper.convertValue(node, Dto.CreateSavedBoardRequest.class);
            specs.add(new QuestionBankService.ManualBoardSpec(
                    request.title(),
                    request.sourceKind(),
                    request.sourceKey(),
                    request.sourceRoot(),
                    QuestionHints.of(request.questionHints(), request.questionFocuses()),
                    toCategories(request.categories()),
                    new Board.GraphDigest(0, 0, 0, 0, 0)
            ));
        }
        return specs;
    }

    private static boolean readSkipDuplicates(JsonNode body) {
        if (body != null && body.isObject() && body.has("skipDuplicates")) {
            return body.get("skipDuplicates").asBoolean(true);
        }
        return true;
    }

    private static Dto.BulkUploadResponse toResponse(QuestionBankService.BulkResult result) {
        List<Dto.BulkUploadItemResult> items = result.results().stream()
                .map(item -> new Dto.BulkUploadItemResult(
                        item.index(),
                        item.title(),
                        item.status(),
                        item.id(),
                        item.message()
                ))
                .toList();
        return new Dto.BulkUploadResponse(result.created(), result.skipped(), result.failed(), items);
    }

    private static List<Category> toCategories(List<Dto.CategoryInput> inputs) {
        List<Category> categories = new ArrayList<>();
        if (inputs == null) {
            return categories;
        }
        for (Dto.CategoryInput cat : inputs) {
            if (cat == null) {
                continue;
            }
            List<Clue> clues = new ArrayList<>();
            if (cat.clues() != null) {
                for (Dto.ClueInput clue : cat.clues()) {
                    if (clue == null) {
                        continue;
                    }
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
        return categories;
    }
}
