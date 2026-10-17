package com.jmjava.teamjeopardy.quiz.strategy;

import com.jmjava.teamjeopardy.github.PullRequestFact;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import com.jmjava.teamjeopardy.quiz.JeopardyStyle;
import com.jmjava.teamjeopardy.quiz.QuestionPersona;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Builds coder/QA Jeopardy categories from recent GitHub pull requests.
 */
@Component
public class PrQuestionStrategy implements QuestionStrategy {

    @Override
    public String id() {
        return "pull-requests";
    }

    @Override
    public QuestionPersona persona() {
        return QuestionPersona.CODER;
    }

    @Override
    public int priority() {
        return 95;
    }

    @Override
    public List<Category> build(CodeGraph graph, AtomicInteger clueSeq) {
        List<PullRequestFact> prs = graph.getPullRequests();
        if (prs == null || prs.isEmpty()) {
            return List.of();
        }
        List<Category> categories = new ArrayList<>();
        categories.add(buildTitleMatch(prs, clueSeq));
        categories.add(buildAuthors(prs, clueSeq));
        categories.add(buildFiles(prs, clueSeq));
        categories.add(buildQaRisk(prs, clueSeq));
        categories.add(buildMergeFacts(prs, clueSeq));
        return categories.stream()
                .filter(c -> c.clues() != null && !c.clues().isEmpty())
                .toList();
    }

    private Category buildTitleMatch(List<PullRequestFact> prs, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        for (PullRequestFact pr : prs.stream().limit(8).toList()) {
            clues.add(clue(seq,
                    "This recent PR is titled:\n\"" + pr.title() + "\"",
                    JeopardyStyle.whatIs("PR #" + pr.number()),
                    "Author " + pr.author() + (pr.merged() ? " · merged" : " · " + pr.state()),
                    pr.htmlUrl()));
        }
        return new Category("cat-pr-titles", "PR: NAME THAT PULL", clues);
    }

    private Category buildAuthors(List<PullRequestFact> prs, AtomicInteger seq) {
        Map<String, Long> byAuthor = prs.stream()
                .collect(Collectors.groupingBy(PullRequestFact::author, LinkedHashMap::new, Collectors.counting()));
        List<Clue> clues = new ArrayList<>();
        byAuthor.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> clues.add(clue(seq,
                        "This contributor authored " + e.getValue()
                                + " of the recent PRs in this board.",
                        JeopardyStyle.whoIs(e.getKey()),
                        "Counted from pull request authors.",
                        null)));
        prs.stream().limit(4).forEach(pr -> clues.add(clue(seq,
                "This contributor opened PR #" + pr.number() + " — \"" + shortTitle(pr.title()) + "\".",
                JeopardyStyle.whoIs(pr.author()),
                pr.merged() ? "Merged into " + nullTo(pr.baseRef(), "main") : "State: " + pr.state(),
                pr.htmlUrl())));
        return new Category("cat-pr-authors", "PR: WHO SHIPPED IT", clues);
    }

    private Category buildFiles(List<PullRequestFact> prs, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        Map<String, Long> fileHits = new LinkedHashMap<>();
        for (PullRequestFact pr : prs) {
            for (String file : pr.files()) {
                fileHits.merge(file, 1L, Long::sum);
            }
        }
        fileHits.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(4)
                .forEach(e -> clues.add(clue(seq,
                        "This path shows up in " + e.getValue() + " recent PR(s).",
                        JeopardyStyle.whatIs(e.getKey()),
                        "Aggregated from PR file lists.",
                        null)));

        prs.stream()
                .filter(pr -> pr.files() != null && !pr.files().isEmpty())
                .limit(5)
                .forEach(pr -> {
                    String sample = pr.files().stream().limit(3).collect(Collectors.joining(", "));
                    clues.add(clue(seq,
                            "These paths changed together: " + sample
                                    + (pr.files().size() > 3 ? ", …" : "") + ".",
                            JeopardyStyle.whatIs("PR #" + pr.number()),
                            pr.title(),
                            pr.htmlUrl()));
                });
        return new Category("cat-pr-files", "PR: FILES TOUCHED", clues);
    }

    private Category buildQaRisk(List<PullRequestFact> prs, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        prs.stream()
                .sorted(Comparator.comparingInt(PullRequestFact::churn).reversed())
                .limit(4)
                .filter(pr -> pr.churn() > 0 || pr.changedFiles() > 0)
                .forEach(pr -> clues.add(clue(seq,
                        "QA risk hotspot: +" + pr.additions() + " / −" + pr.deletions()
                                + " across " + Math.max(pr.changedFiles(), pr.files().size())
                                + " files, titled \"" + shortTitle(pr.title()) + "\".",
                        JeopardyStyle.whatIs("PR #" + pr.number()),
                        pr.title() + " by " + pr.author(),
                        pr.htmlUrl())));

        prs.stream()
                .filter(pr -> pr.labels() != null && !pr.labels().isEmpty())
                .limit(4)
                .forEach(pr -> clues.add(clue(seq,
                        "GitHub labels on this change include: "
                                + String.join(", ", pr.labels()) + ".",
                        JeopardyStyle.whatIs("PR #" + pr.number()),
                        "Useful for triage / test focus. " + pr.title(),
                        pr.htmlUrl())));

        long open = prs.stream().filter(pr -> "open".equalsIgnoreCase(pr.state()) && !pr.merged()).count();
        if (!prs.isEmpty()) {
            clues.add(0, clue(seq,
                    "Among the recent PRs ingested, this many are still open (not merged).",
                    JeopardyStyle.whatIs(String.valueOf(open)),
                    "Open && !merged count.",
                    null));
        }
        return new Category("cat-pr-qa", "QA: PR RISK", clues);
    }

    private Category buildMergeFacts(List<PullRequestFact> prs, AtomicInteger seq) {
        List<Clue> clues = new ArrayList<>();
        long merged = prs.stream().filter(PullRequestFact::merged).count();
        clues.add(clue(seq,
                "This many of the recent PRs in this board were merged.",
                JeopardyStyle.whatIs(String.valueOf(merged)),
                "merged_at present on GitHub payload.",
                null));
        clues.add(clue(seq,
                "This many recent PRs were pulled into this Jeopardy board.",
                JeopardyStyle.whatIs(String.valueOf(prs.size())),
                "GitHub pulls ingest limit.",
                null));

        Map<String, Long> bases = prs.stream()
                .filter(pr -> pr.baseRef() != null && !pr.baseRef().isBlank())
                .collect(Collectors.groupingBy(PullRequestFact::baseRef, Collectors.counting()));
        bases.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(e -> clues.add(clue(seq,
                        "Most of these PRs target this base branch.",
                        JeopardyStyle.whatIs(e.getKey()),
                        e.getValue() + " PR(s).",
                        null)));

        prs.stream()
                .filter(pr -> pr.body() != null && pr.body().length() > 40)
                .limit(3)
                .forEach(pr -> clues.add(clue(seq,
                        "PR description excerpt:\n" + excerpt(pr.body(), 160),
                        JeopardyStyle.whatIs("PR #" + pr.number()),
                        pr.title(),
                        pr.htmlUrl())));
        return new Category("cat-pr-merge", "PR: MERGE MATH", clues);
    }

    private static Clue clue(AtomicInteger seq, String prompt, String response, String explanation, String source) {
        return new Clue("c" + seq.getAndIncrement(), 0, prompt, response, explanation, source, false);
    }

    private static String shortTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.length() <= 72 ? title : title.substring(0, 72).trim() + "…";
    }

    private static String excerpt(String body, int max) {
        String oneLine = body.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    private static String nullTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
