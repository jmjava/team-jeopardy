package com.jmjava.teamjeopardy.game;

public enum GamePhase {
    /** Players may join; moderator admits them and prepares the board. */
    LOBBY,
    /** Shared Jeopardy board visible; host selects clues. */
    BOARD,
    /** Host/moderator reads the clue (+ answer) before players see the prompt. */
    HOST_PREVIEW,
    /** Clue visible to everyone; buzzers open. */
    CLUE_OPEN,
    /** First buzz locked in; host judges. */
    BUZZ_LOCKED,
    ANSWER_REVEALED,
    FINAL,
    FINISHED
}
