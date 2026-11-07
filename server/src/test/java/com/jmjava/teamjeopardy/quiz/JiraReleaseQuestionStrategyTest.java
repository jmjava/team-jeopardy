package com.jmjava.teamjeopardy.quiz;

import com.jmjava.teamjeopardy.jira.JiraCategoryBucket;
import com.jmjava.teamjeopardy.jira.JiraIssueFact;
import com.jmjava.teamjeopardy.quiz.strategy.JiraReleaseQuestionStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JiraReleaseQuestionStrategyTest {

    private final JiraReleaseQuestionStrategy strategy = new JiraReleaseQuestionStrategy();

    @Test
    void prefixesSpecAndRelAndLocksAnswersToFields() {
        JiraIssueFact story = new JiraIssueFact(
                "PROJ-1", "PROJ", "Storefront", "Add guest checkout flag",
                "Expose a checkout flag.",
                "Given a guest cart, when they checkout, then the flag is stored.",
                "Story", "Checkout", "web", List.of("checkout"), "2.4.0", "Done"
        );
        JiraIssueFact bug = new JiraIssueFact(
                "PROJ-20", "PROJ", "Storefront", "Fix double-submit",
                "Second click duplicated orders.",
                "",
                "Bug", "", "web", List.of(), "2.4.0", "Done"
        );

        List<Category> categories = strategy.build(List.of(
                new JiraCategoryBucket("Checkout", "epic", List.of(story, story, story)),
                new JiraCategoryBucket("Also in this release.", "leftover", List.of(bug, bug, bug))
        ), new AtomicInteger(1));

        assertEquals(2, categories.size());
        assertTrue(categories.getFirst().title().startsWith("SPEC:"));
        assertTrue(categories.get(1).title().startsWith("REL:"));
        assertEquals(5, categories.getFirst().clues().size());

        for (Clue clue : categories.getFirst().clues()) {
            assertTrue(
                    clue.response().contains("PROJ-1") || clue.response().contains("Checkout")
                            || clue.response().contains("web") || clue.response().contains("Story"),
                    clue.response()
            );
            assertTrue(
                    clue.prompt().toLowerCase().contains("guest")
                            || clue.prompt().contains("PROJ-1")
                            || clue.prompt().contains("Checkout")
                            || clue.prompt().contains("2.4.0")
                            || clue.prompt().toLowerCase().contains("checkout")
                            || clue.prompt().toLowerCase().contains("story"),
                    clue.prompt()
            );
        }
    }

    @Test
    void pickFinalPrefersLeftoverCrossProjectStory() {
        JiraIssueFact leftoverStory = new JiraIssueFact(
                "SHOP-2", "SHOP", "Shop", "Cross-team checkout contract",
                "Shared checkout contract for the release.",
                "Given a cart, when checkout starts, then both SHOP and PAY honor the same flag.",
                "Story", "Checkout", "api", List.of(), "2.4.0", "Done"
        );
        JiraIssueFact ordinary = new JiraIssueFact(
                "PROJ-1", "PROJ", "Storefront", "Add guest checkout flag",
                "Expose a checkout flag.",
                "",
                "Task", "Checkout", "web", List.of(), "2.4.0", "Done"
        );
        JiraReleaseQuestionStrategy.FinalSelection picked = strategy.pickFinal(List.of(
                new JiraCategoryBucket("Checkout", "epic", List.of(ordinary)),
                new JiraCategoryBucket("Also in this release.", "leftover", List.of(leftoverStory))
        ), new AtomicInteger(20));

        assertTrue(picked.clue().finalJeopardy());
        assertEquals("What is SHOP-2?", picked.clue().response());
        assertEquals("Checkout", picked.categoryTitle());
        assertEquals("SHOP-2", picked.issueKey());
    }
}
