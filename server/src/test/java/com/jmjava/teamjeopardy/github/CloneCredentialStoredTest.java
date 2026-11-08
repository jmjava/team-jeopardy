package com.jmjava.teamjeopardy.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloneCredentialStoredTest {

    @Test
    void aSuccessfulCloneDoesNotKeepTheCredentialInGitConfig(@TempDir Path repo) throws Exception {
        String credential = "ghp_testtokenvalue";
        Path config = repo.resolve(".git").resolve("config");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                [remote "origin"]
                \turl = https://x-access-token:%s@github.com/acme/demo.git
                """.formatted(credential));

        GitHubRepoFetcher.forgetCloneCredential(repo, "acme", "demo", credential);

        String stored = Files.readString(config);
        assertFalse(stored.contains(credential));
        assertFalse(stored.contains("x-access-token:"));
        assertTrue(stored.contains("https://github.com/acme/demo.git"));
    }
}
