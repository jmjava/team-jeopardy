package com.jmjava.teamjeopardy.jira;

import java.util.List;

/**
 * One Jeopardy column produced by {@link JiraReleaseCategorizer}.
 *
 * @param title     human bucket name (epic, component, project, leftover, …)
 * @param dimension how the bucket was chosen (epic, component, issueType, project, leftover, shared)
 * @param issues    issues assigned to this column
 */
public record JiraCategoryBucket(
        String title,
        String dimension,
        List<JiraIssueFact> issues
) {
    public JiraCategoryBucket {
        title = title == null ? "" : title.trim();
        dimension = dimension == null ? "" : dimension.trim();
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
