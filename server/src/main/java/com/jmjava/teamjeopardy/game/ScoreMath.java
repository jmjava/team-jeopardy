package com.jmjava.teamjeopardy.game;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Applies a clue or Final delta without wrapping a 32-bit score.
 */
final class ScoreMath {

    private ScoreMath() {
    }

    static int apply(int score, int delta, boolean correct) {
        try {
            return correct ? Math.addExact(score, delta) : Math.subtractExact(score, delta);
        } catch (ArithmeticException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Score would overflow");
        }
    }
}
