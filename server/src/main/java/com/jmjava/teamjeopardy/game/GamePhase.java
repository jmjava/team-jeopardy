package com.jmjava.teamjeopardy.game;

public enum GamePhase {
    /** Players may join; moderator admits them and prepares the board. */
    LOBBY,
    /** Shared Jeopardy board visible; host selects clues. */
    BOARD,
    /** Host/moderator reads the clue (+ answer) before players see the prompt. */
    HOST_PREVIEW,
    /** Clue visible to everyone; buzzers open. Daily Double claim-buzz also uses this. */
    CLUE_OPEN,
    /** Daily Double: selected/buzzed player types a wager before the clue is shown. */
    DD_WAGER,
    /** First buzz locked in; host judges. */
    BUZZ_LOCKED,
    ANSWER_REVEALED,
    /** Final Jeopardy: category only. */
    FINAL_CATEGORY,
    /** Final Jeopardy: each team types a wager. */
    FINAL_WAGER,
    /** Final Jeopardy: clue visible; teams type answers (no buzz). */
    FINAL_WRITING,
    /** Final Jeopardy: writing time ended; answers locked. */
    FINAL_LOCKED,
    /** Final Jeopardy: official response visible; host marks each line. */
    FINAL_REVEALED,
    FINISHED;

    public boolean isFinalRound() {
        return this == FINAL_CATEGORY
                || this == FINAL_WAGER
                || this == FINAL_WRITING
                || this == FINAL_LOCKED
                || this == FINAL_REVEALED;
    }
}
