package com.jmjava.teamjeopardy.github;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloneFailureRedactionTest {

    @Test
    void aFailedCloneReportDoesNotIncludeTheCredential() {
        String credential = "ghp_testtokenvalue";
        List<String> command = List.of(
                "git", "clone",
                "--depth", "1",
                "https://x-access-token:" + credential + "@github.com/acme/demo.git",
                "/tmp/demo"
        );
        String report = GitHubRepoFetcher.failureText(
                "Command failed (128): ",
                command,
                "fatal: authentication failed for " + credential,
                credential
        );

        assertFalse(report.contains(credential));
        assertTrue(report.contains("[redacted]"));
        assertTrue(report.contains("acme/demo"));
    }
}
