package com.jmjava.teamjeopardy.jira;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Deterministic SPEC/REL board buckets from JIRA fields only.
 *
 * <ul>
 *   <li>One fat project: epic → component → issue type (first 3–6 buckets of ≥3).</li>
 *   <li>Several similar projects: one category per project.</li>
 *   <li>One huge + satellites: huge splits; small projects stay one column.</li>
 *   <li>Too many thin projects: fold satellites into Shared / platform or Other teams.</li>
 * </ul>
 */
@Component
public class JiraReleaseCategorizer {

    public static final int MIN_BUCKET = 3;
    public static final int MIN_CATEGORIES = 3;
    public static final int MAX_CATEGORIES = 6;
    public static final String LEFTOVER_TITLE = "Also in this release.";
    public static final String SHARED_TITLE = "Shared / platform";
    public static final String OTHER_TEAMS_TITLE = "Other teams";

    public List<JiraCategoryBucket> categorize(List<JiraIssueFact> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        Map<String, List<JiraIssueFact>> byProject = groupBy(issues, JiraReleaseCategorizer::projectGroupKey);
        if (byProject.size() <= 1) {
            return splitFatProject(issues);
        }
        List<Map.Entry<String, List<JiraIssueFact>>> ranked = rankProjects(byProject);
        if (isHugePlusSatellites(ranked)) {
            return hugePlusSatellites(ranked);
        }
        if (isTooManyThin(ranked)) {
            return combineThinProjects(ranked);
        }
        return onePerProject(ranked);
    }

    private List<JiraCategoryBucket> splitFatProject(List<JiraIssueFact> issues) {
        for (Dimension dimension : List.of(Dimension.EPIC, Dimension.COMPONENT, Dimension.ISSUE_TYPE)) {
            List<JiraCategoryBucket> split = trySplit(issues, dimension, true);
            if (qualifyingCount(split) >= MIN_CATEGORIES) {
                return cap(split);
            }
        }
        String title = issues.getFirst().projectLabel();
        if (title.isBlank()) {
            title = LEFTOVER_TITLE;
        }
        return List.of(new JiraCategoryBucket(title, "project", issues));
    }

    private List<JiraCategoryBucket> trySplit(
            List<JiraIssueFact> issues,
            Dimension dimension,
            boolean includeLeftovers
    ) {
        Map<String, List<JiraIssueFact>> groups = new LinkedHashMap<>();
        List<JiraIssueFact> leftovers = new ArrayList<>();
        for (JiraIssueFact issue : issues) {
            String key = dimension.extract(issue);
            if (key == null || key.isBlank()) {
                leftovers.add(issue);
            } else {
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(issue);
            }
        }
        List<Map.Entry<String, List<JiraIssueFact>>> qualifying = groups.entrySet().stream()
                .filter(e -> e.getValue().size() >= MIN_BUCKET)
                .sorted(bySizeThenName())
                .toList();
        if (qualifying.size() < MIN_CATEGORIES) {
            return List.of();
        }

        List<JiraIssueFact> overflow = new ArrayList<>(leftovers);
        groups.entrySet().stream()
                .filter(e -> e.getValue().size() < MIN_BUCKET)
                .forEach(e -> overflow.addAll(e.getValue()));

        List<JiraCategoryBucket> buckets = new ArrayList<>();
        int take = qualifying.size();
        if (includeLeftovers && !overflow.isEmpty() && take >= MAX_CATEGORIES) {
            take = MAX_CATEGORIES - 1;
            for (int i = take; i < qualifying.size(); i++) {
                overflow.addAll(qualifying.get(i).getValue());
            }
        }
        for (int i = 0; i < take; i++) {
            Map.Entry<String, List<JiraIssueFact>> entry = qualifying.get(i);
            buckets.add(new JiraCategoryBucket(entry.getKey(), dimension.id, entry.getValue()));
        }
        if (includeLeftovers && !overflow.isEmpty()) {
            buckets.add(new JiraCategoryBucket(LEFTOVER_TITLE, "leftover", overflow));
        }
        return buckets;
    }

    private List<JiraCategoryBucket> hugePlusSatellites(List<Map.Entry<String, List<JiraIssueFact>>> ranked) {
        Map.Entry<String, List<JiraIssueFact>> huge = ranked.getFirst();
        List<JiraCategoryBucket> buckets = new ArrayList<>(splitFatProject(huge.getValue()));
        List<JiraCategoryBucket> satellites = new ArrayList<>();
        for (int i = 1; i < ranked.size(); i++) {
            Map.Entry<String, List<JiraIssueFact>> project = ranked.get(i);
            satellites.add(new JiraCategoryBucket(projectLabel(project.getValue()), "project", project.getValue()));
        }
        return mergeToCap(buckets, satellites);
    }

    private List<JiraCategoryBucket> onePerProject(List<Map.Entry<String, List<JiraIssueFact>>> ranked) {
        List<JiraCategoryBucket> buckets = new ArrayList<>();
        for (Map.Entry<String, List<JiraIssueFact>> project : ranked) {
            buckets.add(new JiraCategoryBucket(projectLabel(project.getValue()), "project", project.getValue()));
        }
        return cap(buckets);
    }

    private List<JiraCategoryBucket> combineThinProjects(List<Map.Entry<String, List<JiraIssueFact>>> ranked) {
        List<JiraCategoryBucket> keep = new ArrayList<>();
        List<JiraIssueFact> folded = new ArrayList<>();
        int keepBudget = Math.max(2, MAX_CATEGORIES - 1);
        for (Map.Entry<String, List<JiraIssueFact>> project : ranked) {
            if (project.getValue().size() >= MIN_BUCKET && keep.size() < keepBudget) {
                keep.add(new JiraCategoryBucket(projectLabel(project.getValue()), "project", project.getValue()));
            } else {
                folded.addAll(project.getValue());
            }
        }
        if (!folded.isEmpty()) {
            keep.add(new JiraCategoryBucket(sharedOrOther(folded), "shared", folded));
        }
        if (keep.size() < MIN_CATEGORIES && !folded.isEmpty()) {
            List<JiraCategoryBucket> splitFolded = trySplit(folded, Dimension.ISSUE_TYPE, false);
            if (qualifyingCount(splitFolded) >= MIN_CATEGORIES) {
                List<JiraCategoryBucket> rebuilt = new ArrayList<>(
                        keep.stream().filter(b -> !"shared".equals(b.dimension())).toList()
                );
                rebuilt.addAll(splitFolded);
                return cap(rebuilt);
            }
        }
        return cap(keep);
    }

    private List<JiraCategoryBucket> mergeToCap(
            List<JiraCategoryBucket> primary,
            List<JiraCategoryBucket> extra
    ) {
        List<JiraCategoryBucket> out = new ArrayList<>(primary);
        int room = MAX_CATEGORIES - out.size();
        if (room <= 0) {
            return cap(out);
        }
        if (extra.size() <= room) {
            out.addAll(extra);
            return cap(out);
        }
        if (room == 1) {
            List<JiraIssueFact> folded = extra.stream().flatMap(b -> b.issues().stream()).toList();
            out.add(new JiraCategoryBucket(sharedOrOther(folded), "shared", folded));
            return cap(out);
        }
        out.addAll(extra.subList(0, room - 1));
        List<JiraIssueFact> folded = extra.subList(room - 1, extra.size()).stream()
                .flatMap(b -> b.issues().stream())
                .toList();
        out.add(new JiraCategoryBucket(sharedOrOther(folded), "shared", folded));
        return cap(out);
    }

    private static boolean isHugePlusSatellites(List<Map.Entry<String, List<JiraIssueFact>>> ranked) {
        if (ranked.size() < 2) {
            return false;
        }
        int total = ranked.stream().mapToInt(e -> e.getValue().size()).sum();
        int largest = ranked.getFirst().getValue().size();
        int second = ranked.get(1).getValue().size();
        return largest >= MIN_BUCKET * MIN_CATEGORIES
                && largest * 2 >= total
                && largest >= second * 2;
    }

    private static boolean isTooManyThin(List<Map.Entry<String, List<JiraIssueFact>>> ranked) {
        if (ranked.size() > MAX_CATEGORIES) {
            return true;
        }
        long thin = ranked.stream().filter(e -> e.getValue().size() < MIN_BUCKET).count();
        return ranked.size() >= 5 && thin >= 3;
    }

    private static List<Map.Entry<String, List<JiraIssueFact>>> rankProjects(
            Map<String, List<JiraIssueFact>> byProject
    ) {
        return byProject.entrySet().stream()
                .sorted(bySizeThenName())
                .toList();
    }

    private static Comparator<Map.Entry<String, List<JiraIssueFact>>> bySizeThenName() {
        return Comparator
                .<Map.Entry<String, List<JiraIssueFact>>>comparingInt(e -> e.getValue().size())
                .reversed()
                .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER);
    }

    private static int qualifyingCount(List<JiraCategoryBucket> buckets) {
        return (int) buckets.stream()
                .filter(b -> !"leftover".equals(b.dimension()) && b.issues().size() >= MIN_BUCKET)
                .count();
    }

    private static List<JiraCategoryBucket> cap(List<JiraCategoryBucket> buckets) {
        if (buckets.size() <= MAX_CATEGORIES) {
            return List.copyOf(buckets);
        }
        List<JiraCategoryBucket> head = new ArrayList<>(buckets.subList(0, MAX_CATEGORIES - 1));
        List<JiraIssueFact> tail = buckets.subList(MAX_CATEGORIES - 1, buckets.size()).stream()
                .flatMap(b -> b.issues().stream())
                .toList();
        head.add(new JiraCategoryBucket(sharedOrOther(tail), "shared", tail));
        return List.copyOf(head);
    }

    private static String sharedOrOther(List<JiraIssueFact> issues) {
        for (JiraIssueFact issue : issues) {
            String blob = (issue.projectKey() + " " + issue.projectName() + " " + String.join(" ", issue.labels()))
                    .toLowerCase(Locale.ROOT);
            if (blob.contains("plat") || blob.contains("shared") || blob.contains("platform") || blob.contains("core")) {
                return SHARED_TITLE;
            }
        }
        return OTHER_TEAMS_TITLE;
    }

    private static String projectGroupKey(JiraIssueFact issue) {
        if (!issue.projectKey().isBlank()) {
            return issue.projectKey().toUpperCase(Locale.ROOT);
        }
        return issue.projectName().isBlank() ? "UNKNOWN" : issue.projectName();
    }

    private static String projectLabel(List<JiraIssueFact> issues) {
        if (issues.isEmpty()) {
            return "Project";
        }
        JiraIssueFact first = issues.getFirst();
        return first.projectLabel().isBlank() ? projectGroupKey(first) : first.projectLabel();
    }

    private static Map<String, List<JiraIssueFact>> groupBy(
            List<JiraIssueFact> issues,
            Function<JiraIssueFact, String> keyFn
    ) {
        Map<String, List<JiraIssueFact>> groups = new LinkedHashMap<>();
        for (JiraIssueFact issue : issues) {
            groups.computeIfAbsent(keyFn.apply(issue), ignored -> new ArrayList<>()).add(issue);
        }
        return groups;
    }

    private enum Dimension {
        EPIC("epic", JiraIssueFact::epic),
        COMPONENT("component", JiraIssueFact::component),
        ISSUE_TYPE("issueType", JiraIssueFact::issueType);

        private final String id;
        private final Function<JiraIssueFact, String> extractor;

        Dimension(String id, Function<JiraIssueFact, String> extractor) {
            this.id = id;
            this.extractor = extractor;
        }

        String extract(JiraIssueFact issue) {
            return extractor.apply(issue);
        }
    }
}
