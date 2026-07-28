package com.jmjava.teamjeopardy.quiz;

/**
 * Audience for generated clues. Boards mix both by default so engineers and QA
 * can play the same game with overlapping but distinct angles on the same code.
 */
public enum QuestionPersona {
    /** Implementation, APIs, patterns, structure. */
    CODER,
    /** Contracts, risk, regression targets, boundaries. */
    QA
}
