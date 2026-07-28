package com.jmjava.teamjeopardy.quiz;

import java.util.List;

public record Category(
        String id,
        String title,
        List<Clue> clues
) {
}
