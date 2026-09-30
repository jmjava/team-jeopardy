package com.jmjava.teamjeopardy.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JiraReleaseClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JiraReleaseClient client = new JiraReleaseClient(
            mapper, "", "", "", 10, 50,
            "../samples/jira-release-multi-project.json",
            "../samples/jira-release-one-project.json",
            "", ""
    );

    @Test
    void unconfiguredWithoutEnv() {
        assertFalse(client.isConfigured());
    }

    @Test
    void buildJqlIsReadOnlySearch() {
        String jql = JiraReleaseClient.buildJql(List.of("PROJ", "SHOP"), "2.4.0", "status = Done");
        assertTrue(jql.contains("project in (PROJ, SHOP)"));
        assertTrue(jql.contains("fixVersion = \"2.4.0\""));
        assertTrue(jql.contains("status = Done"));
        assertFalse(jql.toLowerCase().contains("update"));
    }

    @Test
    void parsesSanitizedFixtureWithoutNetwork() throws Exception {
        Path fixture = client.fixturePath("one-project");
        List<JiraIssueFact> issues = client.loadFixture(fixture);
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(i -> "PROJ-1".equals(i.key())));
        assertTrue(issues.stream().noneMatch(i -> i.key().contains("atlassian")));
        assertTrue(issues.stream().allMatch(i -> i.summary() != null && !i.summary().isBlank()));
    }

    @Test
    void parsesCloudSearchShapeAndFlattensAdf() throws Exception {
        String json = """
                {
                  "issues": [
                    {
                      "key": "PROJ-1",
                      "fields": {
                        "summary": "Add guest checkout flag",
                        "description": {
                          "type": "doc",
                          "content": [
                            {"type": "paragraph", "content": [{"type": "text", "text": "Expose a checkout flag."}]}
                          ]
                        },
                        "issuetype": {"name": "Story"},
                        "status": {"name": "Done"},
                        "labels": ["checkout"],
                        "components": [{"name": "web"}],
                        "fixVersions": [{"name": "2.4.0"}],
                        "project": {"key": "PROJ", "name": "Storefront"},
                        "parent": {
                          "fields": {
                            "issuetype": {"name": "Epic"},
                            "summary": "Checkout"
                          }
                        },
                        "customfield_10015": "Given a guest cart, when they checkout, then the flag is stored."
                      }
                    }
                  ]
                }
                """;
        List<JiraIssueFact> issues = client.parseIssues(mapper.readTree(json));
        assertEquals(1, issues.size());
        JiraIssueFact fact = issues.getFirst();
        assertEquals("PROJ-1", fact.key());
        assertEquals("PROJ", fact.projectKey());
        assertEquals("Checkout", fact.epic());
        assertEquals("web", fact.component());
        assertEquals("Expose a checkout flag.", fact.description());
        assertTrue(fact.acceptance().contains("guest cart"));
        assertFalse(fact.toString().toLowerCase().contains("token"));
    }
}
