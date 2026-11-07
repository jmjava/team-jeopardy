package com.jmjava.teamjeopardy.game;

/**
 * Daily Double and Final Jeopardy wager bounds.
 *
 * <ul>
 *   <li>Minimum wager is {@code $5}.</li>
 *   <li>Maximum is the team's current score when that score is at least {@code $5}.</li>
 *   <li>If the score is below {@code $5} (zero, negative, or a small positive), the maximum is {@code $1000}.</li>
 * </ul>
 */
public final class WagerRules {

    public static final int MIN_WAGER = 5;
    public static final int ZERO_SCORE_MAX = 1000;

    private WagerRules() {
    }

    public static int maxWager(int score) {
        return score < MIN_WAGER ? ZERO_SCORE_MAX : score;
    }

    public static boolean isValid(int wager, int score) {
        return wager >= MIN_WAGER && wager <= maxWager(score);
    }

    public static int requireValid(int wager, int score) {
        if (!isValid(wager, score)) {
            throw new IllegalArgumentException(
                    "Wager must be between $" + MIN_WAGER + " and $" + maxWager(score)
            );
        }
        return wager;
    }
}
