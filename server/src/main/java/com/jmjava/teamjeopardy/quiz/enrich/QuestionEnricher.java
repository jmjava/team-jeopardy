package com.jmjava.teamjeopardy.quiz.enrich;

import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;

import java.util.List;

/**
 * Optional second pass over heuristic clues. Implementations must preserve answers
 * (response text) and never invent symbols that are not grounded in the graph/evidence.
 */
public interface QuestionEnricher {

    /**
     * @return same categories, possibly with polished prompts/explanations
     */
    List<Category> enrich(CodeGraph graph, List<Category> categories);

    /**
     * Enrich with optional moderator question hints for tone/focus.
     */
    default List<Category> enrich(CodeGraph graph, List<Category> categories, String questionHints) {
        return enrich(graph, categories);
    }

    default Board enrichBoard(CodeGraph graph, Board board) {
        return new Board(
                board.title(),
                board.sourceRoot(),
                enrich(graph, board.categories(), graph.getQuestionHints()),
                board.graphDigest()
        );
    }
}
