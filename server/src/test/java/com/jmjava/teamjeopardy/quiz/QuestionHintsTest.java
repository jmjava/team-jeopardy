package com.jmjava.teamjeopardy.quiz;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionHintsTest {

    @Test
    void scoresPrAndQaCategoriesFromHints() {
        QuestionHints hints = QuestionHints.of(
                "Focus on recent pull requests and QA risk",
                List.of("pull-requests", "qa")
        );
        Category pr = new Category("cat-pr", "PR: NAME THAT PULL", List.of(
                new Clue("c1", 200, "PR titled X", "What is PR #1?", null, null, false)
        ));
        Category qa = new Category("cat-qa", "QA: BLAST RADIUS", List.of(
                new Clue("c2", 200, "High fan-in risk", "What is Foo?", null, null, false)
        ));
        Category other = new Category("cat-files", "FILE CABINET", List.of(
                new Clue("c3", 200, "How many files?", "What is 3?", null, null, false)
        ));

        assertTrue(hints.scoreCategory(pr) > hints.scoreCategory(other));
        assertTrue(hints.scoreCategory(qa) > hints.scoreCategory(other));
    }
}
