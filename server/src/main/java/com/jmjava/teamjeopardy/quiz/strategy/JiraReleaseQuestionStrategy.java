package com.jmjava.teamjeopardy.quiz.strategy;

import com.jmjava.teamjeopardy.jira.JiraCategoryBucket;
import com.jmjava.teamjeopardy.jira.JiraIssueFact;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds SPEC/REL categories from JIRA release buckets.
 * Not a {@link QuestionStrategy}: JIRA is not a code graph and must not go through
 * {@code QuestionGenerator}'s coder/QA balance.
 */
@Component
public class JiraReleaseQuestionStrategy {

    public static final String SPEC = "SPEC";
    public static final String REL = "REL";

    public List<Category> build(List<JiraCategoryBucket> buckets, AtomicInteger clueSeq) {
        if (buckets == null || buckets.isEmpty()) {
            return List.of();
        }
        AtomicInteger seq = clueSeq == null ? new AtomicInteger(1) : clueSeq;
        List<Category> categories = new ArrayList<>();
        for (JiraCategoryBucket bucket : buckets) {
            if (bucket.issues().isEmpty()) {
                continue;
            }
            String prefix = prefixFor(bucket);
            String title = prefix + ": " + displayTitle(bucket.title());
            categories.add(new Category(categoryId(title), title, cluesFor(bucket, seq)));
        }
        return List.copyOf(categories);
    }

    private List<Clue> cluesFor(JiraCategoryBucket bucket, AtomicInteger seq) {
        List<JiraIssueFact> issues = bucket.issues();
        List<Clue> clues = new ArrayList<>();
        int needed = 5;
        int issueCount = issues.size();
        for (int i = 0; i < needed; i++) {
            JiraIssueFact issue = issues.get(i % issueCount);
            clues.add(clueFor(issue, i, seq));
        }
        return clues;
    }

    private Clue clueFor(JiraIssueFact issue, int slot, AtomicInteger seq) {
        return switch (slot % 5) {
            case 0 -> summaryClue(issue, seq);
            case 1 -> acceptanceOrDescriptionClue(issue, seq);
            case 2 -> descriptionOrStatusClue(issue, seq);
            case 3 -> fieldPhraseClue(issue, seq);
            default -> releaseStatusClue(issue, seq);
        };
    }

    private Clue summaryClue(JiraIssueFact issue, AtomicInteger seq) {
        String type = issue.issueType().isBlank() ? "issue" : issue.issueType();
        return clue(seq,
                "This " + type + " is titled:\n\"" + issue.summary() + "\"",
                "What is " + issue.key() + "?",
                grounded(issue, "summary"),
                issue.key());
    }

    private Clue acceptanceOrDescriptionClue(JiraIssueFact issue, AtomicInteger seq) {
        if (issue.hasAcceptance()) {
            return clue(seq,
                    "Acceptance / spec excerpt:\n" + excerpt(issue.acceptance(), 180),
                    "What is " + issue.key() + "?",
                    grounded(issue, "acceptance"),
                    issue.key());
        }
        if (!issue.description().isBlank()) {
            return clue(seq,
                    "Spec / description excerpt:\n" + excerpt(issue.description(), 180),
                    "What is " + issue.key() + "?",
                    grounded(issue, "description"),
                    issue.key());
        }
        return summaryClue(issue, seq);
    }

    private Clue descriptionOrStatusClue(JiraIssueFact issue, AtomicInteger seq) {
        if (!issue.description().isBlank() && issue.hasAcceptance()) {
            return clue(seq,
                    "Description excerpt for a " + nullTo(issue.issueType(), "ticket") + ":\n"
                            + excerpt(issue.description(), 160),
                    "What is " + issue.key() + "?",
                    grounded(issue, "description"),
                    issue.key());
        }
        return releaseStatusClue(issue, seq);
    }

    private Clue fieldPhraseClue(JiraIssueFact issue, AtomicInteger seq) {
        if (!issue.epic().isBlank()) {
            return clue(seq,
                    "Which epic contains " + issue.key() + " — \"" + shortSummary(issue.summary()) + "\"?",
                    "What is " + issue.epic() + "?",
                    grounded(issue, "epic"),
                    issue.key());
        }
        if (!issue.component().isBlank()) {
            return clue(seq,
                    "Which component owns " + issue.key() + " — \"" + shortSummary(issue.summary()) + "\"?",
                    "What is " + issue.component() + "?",
                    grounded(issue, "component"),
                    issue.key());
        }
        if (!issue.issueType().isBlank()) {
            return clue(seq,
                    issue.key() + " is this JIRA issue type.",
                    "What is " + issue.issueType() + "?",
                    grounded(issue, "issueType"),
                    issue.key());
        }
        return summaryClue(issue, seq);
    }

    private Clue releaseStatusClue(JiraIssueFact issue, AtomicInteger seq) {
        String version = issue.fixVersion().isBlank() ? "this release" : issue.fixVersion();
        String status = issue.status().isBlank() ? "in progress" : issue.status();
        String project = issue.projectLabel().isBlank() ? issue.projectKey() : issue.projectLabel();
        return clue(seq,
                "This " + nullTo(issue.issueType(), "issue") + " in " + project
                        + " is currently " + status + " on " + version + ".",
                "What is " + issue.key() + "?",
                grounded(issue, "status/fixVersion"),
                issue.key());
    }

    static String prefixFor(JiraCategoryBucket bucket) {
        if ("leftover".equals(bucket.dimension()) || "shared".equals(bucket.dimension())) {
            return REL;
        }
        if ("issueType".equals(bucket.dimension())) {
            String title = bucket.title().toLowerCase(Locale.ROOT);
            if (title.contains("bug") || title.contains("defect") || title.contains("incident")) {
                return REL;
            }
        }
        long spec = bucket.issues().stream().filter(JiraIssueFact::looksLikeSpec).count();
        return spec * 2 >= bucket.issues().size() ? SPEC : REL;
    }

    private static String displayTitle(String title) {
        if (title == null || title.isBlank()) {
            return "Release";
        }
        return title.endsWith(".") ? title.substring(0, title.length() - 1) : title;
    }

    private static String categoryId(String title) {
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        return "cat-jira-" + (slug.isBlank() ? "release" : slug);
    }

    private static Clue clue(AtomicInteger seq, String prompt, String response, String explanation, String source) {
        return new Clue("c" + seq.getAndIncrement(), 0, prompt, response, explanation, source, false);
    }

    private static String grounded(JiraIssueFact issue, String field) {
        return "Grounded in JIRA " + field + " for " + issue.key() + ".";
    }

    private static String excerpt(String text, int max) {
        String oneLine = text.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max).trim() + "…";
    }

    private static String shortSummary(String summary) {
        if (summary == null) {
            return "";
        }
        return summary.length() <= 72 ? summary : summary.substring(0, 72).trim() + "…";
    }

    private static String nullTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
