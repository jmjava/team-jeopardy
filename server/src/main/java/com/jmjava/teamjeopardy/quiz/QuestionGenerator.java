package com.jmjava.teamjeopardy.quiz;

import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeNode;
import com.jmjava.teamjeopardy.graph.ProjectKind;
import com.jmjava.teamjeopardy.quiz.enrich.PassthroughQuestionEnricher;
import com.jmjava.teamjeopardy.quiz.enrich.QuestionEnricher;
import com.jmjava.teamjeopardy.quiz.strategy.CoderQuestionStrategy;
import com.jmjava.teamjeopardy.quiz.strategy.PrQuestionStrategy;
import com.jmjava.teamjeopardy.quiz.strategy.ProjectStructureQuestionStrategy;
import com.jmjava.teamjeopardy.quiz.strategy.QaQuestionStrategy;
import com.jmjava.teamjeopardy.quiz.strategy.QuestionStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds boards by stacking persona strategies (coder + QA) plus project-structure
 * categories, then optionally polishing clue wording via {@link QuestionEnricher}.
 *
 * <p>Heuristic clues are always available offline. Set
 * {@code team-jeopardy.openai.enabled=true} and {@code OPENAI_API_KEY} to enrich prompts.
 */
@Service
public class QuestionGenerator {

    private static final int[] VALUES = {200, 400, 600, 800, 1000};

    private record Ranked(QuestionStrategy strategy, Category category) {
    }

    private final List<QuestionStrategy> strategies;
    private final QuestionEnricher enricher;
    private final Set<QuestionPersona> enabledPersonas;
    private final int maxCategories;

    public QuestionGenerator(
            List<QuestionStrategy> strategies,
            QuestionEnricher enricher,
            @Value("${team-jeopardy.questions.personas:coder,qa}") String personas,
            @Value("${team-jeopardy.questions.max-categories:6}") int maxCategories
    ) {
        this.strategies = strategies == null ? List.of() : List.copyOf(strategies);
        this.enricher = enricher == null ? new PassthroughQuestionEnricher() : enricher;
        this.enabledPersonas = parsePersonas(personas);
        this.maxCategories = Math.max(3, maxCategories);
    }

    /** Test helper with default strategies and no LLM enrichment. */
    public QuestionGenerator() {
        this(
                List.of(
                        new CoderQuestionStrategy(),
                        new QaQuestionStrategy(),
                        new ProjectStructureQuestionStrategy(),
                        new PrQuestionStrategy()
                ),
                new PassthroughQuestionEnricher(),
                "coder,qa",
                6
        );
    }

    public Board generate(CodeGraph graph, String boardTitle) {
        return generate(graph, boardTitle, QuestionHints.of(graph.getQuestionHints()));
    }

    public Board generate(CodeGraph graph, String boardTitle, QuestionHints hints) {
        QuestionHints effective = hints == null ? QuestionHints.empty() : hints;
        if (!effective.isBlank()) {
            graph.setQuestionHints(effective.combined());
        }
        AtomicInteger clueSeq = new AtomicInteger(1);
        ProjectKind kind = graph.getProjectKind() == null ? ProjectKind.GENERIC : graph.getProjectKind();

        List<Ranked> ranked = new ArrayList<>();
        for (QuestionStrategy strategy : strategies) {
            boolean structure = strategy instanceof ProjectStructureQuestionStrategy;
            if (!structure && !enabledPersonas.contains(strategy.persona())) {
                continue;
            }
            // Moderator hints can soft-skip mismatched specialty strategies.
            if (!structure && !strategyMatchesHints(strategy, effective)) {
                continue;
            }
            for (Category category : strategy.build(graph, clueSeq)) {
                if (category.clues() != null && !category.clues().isEmpty()) {
                    ranked.add(new Ranked(strategy, category));
                }
            }
        }

        // Balanced board, biased by moderator hints when present.
        List<Category> selected = selectBalanced(ranked, maxCategories, effective);
        List<Category> categories = selected.stream().map(this::normalizeCategory).toList();
        categories = enricher.enrich(graph, categories, effective.combined());

        var stats = graph.stats();
        long files = stats.nodesByKind().getOrDefault(CodeNode.NodeKind.FILE, 0L);
        long types = stats.nodesByKind().getOrDefault(CodeNode.NodeKind.CLASS, 0L)
                + stats.nodesByKind().getOrDefault(CodeNode.NodeKind.INTERFACE, 0L);
        long functions = stats.nodesByKind().getOrDefault(CodeNode.NodeKind.METHOD, 0L)
                + stats.nodesByKind().getOrDefault(CodeNode.NodeKind.FUNCTION, 0L);

        String title = boardTitle != null && !boardTitle.isBlank()
                ? boardTitle
                : kind.name() + " Jeopardy: " + (
                graph.getProjectName() != null
                        ? graph.getProjectName()
                        : Path.of(graph.getRootPath()).getFileName()
        );

        return new Board(
                title,
                graph.getRootPath(),
                categories,
                new Board.GraphDigest(stats.nodeCount(), stats.edgeCount(), (int) files, (int) types, (int) functions)
        );
    }

    private List<Category> selectBalanced(List<Ranked> ranked, int max, QuestionHints hints) {
        Comparator<Ranked> byPriority = Comparator
                .comparingInt((Ranked r) -> hints.scoreCategory(r.category())).reversed()
                .thenComparing(Comparator.comparingInt((Ranked r) -> r.strategy().priority()).reversed())
                .thenComparing(r -> r.category().title());

        // When hints are strong, prefer a hint-first fill, then top up for balance.
        if (!hints.isBlank()) {
            List<Category> hinted = new ArrayList<>();
            take(hinted, ranked.stream().sorted(byPriority).toList(), max, max);
            if (hinted.size() >= Math.min(3, max)) {
                return hinted;
            }
        }

        List<Ranked> qa = ranked.stream()
                .filter(r -> r.strategy().persona() == QuestionPersona.QA)
                .sorted(byPriority)
                .toList();
        List<Ranked> coder = ranked.stream()
                .filter(r -> r.strategy().persona() == QuestionPersona.CODER
                        && !(r.strategy() instanceof ProjectStructureQuestionStrategy))
                .sorted(byPriority)
                .toList();
        List<Ranked> structure = ranked.stream()
                .filter(r -> r.strategy() instanceof ProjectStructureQuestionStrategy)
                .sorted(byPriority)
                .toList();

        List<Category> out = new ArrayList<>();
        take(out, qa, 2, max);
        take(out, coder, 2, max);
        take(out, structure, 2, max);
        take(out, ranked.stream().sorted(byPriority).toList(), max, max);
        return out;
    }

    private static boolean strategyMatchesHints(QuestionStrategy strategy, QuestionHints hints) {
        if (hints == null || hints.isBlank()) {
            return true;
        }
        List<String> focuses = hints.focuses();
        if (focuses == null || focuses.isEmpty()) {
            return true;
        }
        // Soft filter: keep strategy if any focus maps to it, otherwise keep when mixed.
        boolean wantsPr = focuses.contains("pull-requests");
        boolean wantsQa = focuses.contains("qa");
        boolean wantsPatterns = focuses.contains("patterns");
        boolean wantsApi = focuses.contains("apis");
        boolean wantsArch = focuses.contains("architecture") || focuses.contains("components");

        String id = strategy.id() == null ? "" : strategy.id();
        boolean onlyPr = wantsPr && !wantsQa && !wantsPatterns && !wantsApi && !wantsArch;
        if (onlyPr) {
            return strategy instanceof PrQuestionStrategy || id.contains("pull");
        }
        if (strategy instanceof PrQuestionStrategy || id.contains("pull")) {
            return wantsPr || (!wantsQa && !wantsPatterns && !wantsApi && !wantsArch);
        }
        if (strategy.persona() == QuestionPersona.QA) {
            return wantsQa || !wantsPr;
        }
        return true;
    }

    private static void take(List<Category> out, List<Ranked> candidates, int maxFromBucket, int boardMax) {
        int added = 0;
        for (Ranked ranked : candidates) {
            if (out.size() >= boardMax || added >= maxFromBucket) {
                return;
            }
            Category category = ranked.category();
            boolean exists = out.stream().anyMatch(c -> c.id().equals(category.id()));
            if (!exists) {
                out.add(category);
                added++;
            }
        }
    }

    private Category normalizeCategory(Category category) {
        List<Clue> clues = new ArrayList<>();
        List<Clue> source = category.clues();
        for (int i = 0; i < Math.min(VALUES.length, source.size()); i++) {
            Clue c = source.get(i);
            clues.add(new Clue(
                    c.id(),
                    VALUES[i],
                    c.prompt(),
                    JeopardyStyle.whatIs(c.response()),
                    c.explanation(),
                    c.sourcePath(),
                    i == 3 && source.size() >= 4
            ));
        }
        return new Category(category.id(), category.title(), clues);
    }

    private static Set<QuestionPersona> parsePersonas(String raw) {
        EnumSet<QuestionPersona> set = EnumSet.noneOf(QuestionPersona.class);
        if (raw == null || raw.isBlank()) {
            set.add(QuestionPersona.CODER);
            set.add(QuestionPersona.QA);
            return set;
        }
        for (String part : raw.split(",")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) {
                continue;
            }
            switch (p) {
                case "coder", "dev", "engineer", "developer" -> set.add(QuestionPersona.CODER);
                case "qa", "test", "tester", "sdet" -> set.add(QuestionPersona.QA);
                case "both", "all" -> {
                    set.add(QuestionPersona.CODER);
                    set.add(QuestionPersona.QA);
                }
                default -> {
                }
            }
        }
        if (set.isEmpty()) {
            set.add(QuestionPersona.CODER);
            set.add(QuestionPersona.QA);
        }
        return set;
    }
}
