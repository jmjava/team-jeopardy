package com.jmjava.teamjeopardy.jira;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JiraReleaseCategorizerTest {

    private final JiraReleaseCategorizer categorizer = new JiraReleaseCategorizer();

    @Test
    void oneFatProjectSplitsByEpicThenLeftovers() {
        List<JiraIssueFact> issues = new ArrayList<>();
        issues.addAll(epicIssues("PROJ", "Storefront", "Checkout", 5));
        issues.addAll(epicIssues("PROJ", "Storefront", "Payments", 4));
        issues.addAll(epicIssues("PROJ", "Storefront", "Catalog", 4));
        issues.addAll(epicIssues("PROJ", "Storefront", "Notifications", 3));
        issues.addAll(epicIssues("PROJ", "Storefront", "Admin Console", 3));
        issues.addAll(List.of(
                fact("PROJ-90", "PROJ", "Storefront", "Orphan bug", "Bug", "", "web"),
                fact("PROJ-91", "PROJ", "Storefront", "Other bug", "Bug", "", "api")
        ));

        List<JiraCategoryBucket> buckets = categorizer.categorize(issues);
        assertTrue(buckets.size() >= 5 && buckets.size() <= 6, "size=" + buckets.size());
        assertTrue(buckets.stream().anyMatch(b -> b.title().equals("Checkout")));
        assertTrue(buckets.stream().anyMatch(b -> b.title().equals(JiraReleaseCategorizer.LEFTOVER_TITLE)));
        assertTrue(buckets.stream().allMatch(b -> "epic".equals(b.dimension()) || "leftover".equals(b.dimension())));
    }

    @Test
    void similarProjectsBecomeOneCategoryEach() {
        List<JiraIssueFact> issues = new ArrayList<>();
        issues.addAll(projectIssues("SHOP", "Shop", 5));
        issues.addAll(projectIssues("PAY", "Payments Service", 5));
        issues.addAll(projectIssues("INV", "Inventory", 5));
        issues.addAll(projectIssues("NOTE", "Notifications", 5));
        issues.addAll(projectIssues("ADMIN", "Admin", 5));

        List<JiraCategoryBucket> buckets = categorizer.categorize(issues);
        assertEquals(5, buckets.size());
        assertTrue(buckets.stream().allMatch(b -> "project".equals(b.dimension())));
        assertTrue(buckets.stream().anyMatch(b -> b.title().equals("Shop")));
        assertTrue(buckets.stream().anyMatch(b -> b.title().equals("Payments Service")));
    }

    @Test
    void hugeProjectSplitsWhileSatellitesStayWhole() {
        List<JiraIssueFact> issues = new ArrayList<>();
        issues.addAll(epicIssues("PROJ", "Storefront", "Checkout", 6));
        issues.addAll(epicIssues("PROJ", "Storefront", "Payments", 6));
        issues.addAll(epicIssues("PROJ", "Storefront", "Catalog", 6));
        issues.addAll(projectIssues("SHOP", "Shop", 3));
        issues.addAll(projectIssues("NOTE", "Notifications", 2));

        List<JiraCategoryBucket> buckets = categorizer.categorize(issues);
        assertTrue(buckets.size() >= 4 && buckets.size() <= 6, "size=" + buckets.size());
        assertTrue(buckets.stream().anyMatch(b -> "Checkout".equals(b.title())));
        assertTrue(buckets.stream().anyMatch(b -> "Shop".equals(b.title()) && "project".equals(b.dimension())));
        assertTrue(buckets.stream().noneMatch(b -> b.issues().size() == 1 && "NOTE".equals(b.issues().getFirst().projectKey())
                && buckets.size() > 6));
    }

    @Test
    void manyThinProjectsFoldIntoSharedOrOther() {
        List<JiraIssueFact> issues = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String key = "T" + i;
            issues.add(fact(key + "-1", key, "Team " + i, "Thin one", "Story", "E", "c"));
            issues.add(fact(key + "-2", key, "Team " + i, "Thin two", "Bug", "", "c"));
        }
        issues.add(fact("PLAT-1", "PLAT", "Platform", "Shared cache", "Story", "Core", "api"));
        issues.add(fact("PLAT-2", "PLAT", "Platform", "Shared auth", "Story", "Core", "api"));

        List<JiraCategoryBucket> buckets = categorizer.categorize(issues);
        assertTrue(buckets.size() <= 6, "size=" + buckets.size());
        assertTrue(buckets.stream().anyMatch(b ->
                JiraReleaseCategorizer.SHARED_TITLE.equals(b.title())
                        || JiraReleaseCategorizer.OTHER_TEAMS_TITLE.equals(b.title())));
        assertTrue(buckets.stream().noneMatch(b -> b.issues().size() == 1 && buckets.size() > 6));
    }

    private static List<JiraIssueFact> epicIssues(String project, String name, String epic, int count) {
        List<JiraIssueFact> issues = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            issues.add(fact(project + "-" + epic.charAt(0) + i, project, name, epic + " work " + i, "Story", epic, "web"));
        }
        return issues;
    }

    private static List<JiraIssueFact> projectIssues(String project, String name, int count) {
        List<JiraIssueFact> issues = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            issues.add(fact(project + "-" + i, project, name, name + " item " + i, "Story", "Epic-" + i, "api"));
        }
        return issues;
    }

    private static JiraIssueFact fact(
            String key,
            String project,
            String projectName,
            String summary,
            String type,
            String epic,
            String component
    ) {
        return new JiraIssueFact(
                key, project, projectName, summary, "desc", "Given when then",
                type, epic, component, List.of(), "2.4.0", "Done"
        );
    }
}
