package com.jmjava.teamjeopardy.jira;

import java.util.List;

/**
 * Sanitized JIRA issue used for SPEC/REL clue generation.
 * Never carries tokens, credentials, or host-specific secrets.
 */
public record JiraIssueFact(
        String key,
        String projectKey,
        String projectName,
        String summary,
        String description,
        String acceptance,
        String issueType,
        String epic,
        String component,
        List<String> labels,
        String fixVersion,
        String status
) {
    public JiraIssueFact {
        key = nullTo(key);
        projectKey = nullTo(projectKey);
        projectName = nullTo(projectName);
        summary = nullTo(summary);
        description = nullTo(description);
        acceptance = nullTo(acceptance);
        issueType = nullTo(issueType);
        epic = nullTo(epic);
        component = nullTo(component);
        labels = labels == null ? List.of() : List.copyOf(labels);
        fixVersion = nullTo(fixVersion);
        status = nullTo(status);
    }

    public String projectLabel() {
        return !projectName.isBlank() ? projectName : projectKey;
    }

    public boolean hasAcceptance() {
        return !acceptance.isBlank();
    }

    public boolean looksLikeSpec() {
        if (hasAcceptance()) {
            return true;
        }
        String type = issueType.toLowerCase();
        return type.contains("story")
                || type.contains("task")
                || type.contains("epic")
                || type.contains("feature")
                || type.contains("requirement")
                || type.contains("spec");
    }

    private static String nullTo(String value) {
        return value == null ? "" : value.trim();
    }
}
