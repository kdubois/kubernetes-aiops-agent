package dev.kevindubois.rollout.agent.fixtures;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Test fixtures for creating GitHub API responses and test data.
 * Provides factory methods to create common GitHub objects for testing.
 */
public class GitHubTestFixtures {

    /**
     * Creates a test GitHub issue response JSON
     */
    public static String createIssueResponseJson(int issueNumber, String title, String state) {
        return String.format("""
            {
              "id": %d,
              "number": %d,
              "title": "%s",
              "state": "%s",
              "html_url": "https://github.com/test-org/test-repo/issues/%d",
              "created_at": "%s",
              "updated_at": "%s",
              "body": "Test issue body",
              "user": {
                "login": "test-user",
                "id": 12345
              },
              "labels": [
                {
                  "name": "bug",
                  "color": "d73a4a"
                }
              ]
            }
            """, issueNumber, issueNumber, title, state, issueNumber, 
            Instant.now().toString(), Instant.now().toString());
    }

    /**
     * Creates a test GitHub issue response with custom labels
     */
    public static String createIssueResponseJson(int issueNumber, String title, String state, List<String> labels) {
        StringBuilder labelsJson = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) labelsJson.append(",");
            labelsJson.append(String.format("""
                {
                  "name": "%s",
                  "color": "d73a4a"
                }
                """, labels.get(i)));
        }

        return String.format("""
            {
              "id": %d,
              "number": %d,
              "title": "%s",
              "state": "%s",
              "html_url": "https://github.com/test-org/test-repo/issues/%d",
              "created_at": "%s",
              "updated_at": "%s",
              "body": "Test issue body",
              "user": {
                "login": "test-user",
                "id": 12345
              },
              "labels": [%s]
            }
            """, issueNumber, issueNumber, title, state, issueNumber,
            Instant.now().toString(), Instant.now().toString(), labelsJson.toString());
    }

    /**
     * Creates a test GitHub PR response JSON
     */
    public static String createPullRequestResponseJson(int prNumber, String title, String state) {
        return String.format("""
            {
              "id": %d,
              "number": %d,
              "title": "%s",
              "state": "%s",
              "html_url": "https://github.com/test-org/test-repo/pull/%d",
              "created_at": "%s",
              "updated_at": "%s",
              "body": "Test PR body",
              "user": {
                "login": "test-user",
                "id": 12345
              },
              "head": {
                "ref": "feature-branch",
                "sha": "abc123def456"
              },
              "base": {
                "ref": "main",
                "sha": "def456abc123"
              },
              "mergeable": true,
              "merged": false
            }
            """, prNumber, prNumber, title, state, prNumber,
            Instant.now().toString(), Instant.now().toString());
    }

    /**
     * Creates a test GitHub comment response JSON
     */
    public static String createCommentResponseJson(long commentId, String body) {
        return String.format("""
            {
              "id": %d,
              "body": "%s",
              "user": {
                "login": "test-user",
                "id": 12345
              },
              "created_at": "%s",
              "updated_at": "%s",
              "html_url": "https://github.com/test-org/test-repo/issues/1#issuecomment-%d"
            }
            """, commentId, body, Instant.now().toString(), Instant.now().toString(), commentId);
    }

    /**
     * Creates a test GitHub repository response JSON
     */
    public static String createRepositoryResponseJson(String owner, String repo) {
        return String.format("""
            {
              "id": 123456,
              "name": "%s",
              "full_name": "%s/%s",
              "owner": {
                "login": "%s",
                "id": 12345
              },
              "html_url": "https://github.com/%s/%s",
              "description": "Test repository",
              "private": false,
              "default_branch": "main"
            }
            """, repo, owner, repo, owner, owner, repo);
    }

    /**
     * Creates a test GitHub branch response JSON
     */
    public static String createBranchResponseJson(String branchName, String sha) {
        return String.format("""
            {
              "name": "%s",
              "commit": {
                "sha": "%s",
                "url": "https://api.github.com/repos/test-org/test-repo/commits/%s"
              },
              "protected": false
            }
            """, branchName, sha, sha);
    }

    /**
     * Creates a test GitHub commit response JSON
     */
    public static String createCommitResponseJson(String sha, String message) {
        return String.format("""
            {
              "sha": "%s",
              "commit": {
                "message": "%s",
                "author": {
                  "name": "Test User",
                  "email": "test@example.com",
                  "date": "%s"
                }
              },
              "html_url": "https://github.com/test-org/test-repo/commit/%s"
            }
            """, sha, message, Instant.now().toString(), sha);
    }

    /**
     * Creates a test GitHub error response JSON
     */
    public static String createErrorResponseJson(String message, int statusCode) {
        return String.format("""
            {
              "message": "%s",
              "documentation_url": "https://docs.github.com/rest"
            }
            """, message);
    }

    /**
     * Creates a test GitHub rate limit response JSON
     */
    public static String createRateLimitResponseJson(int remaining, long resetTime) {
        return String.format("""
            {
              "resources": {
                "core": {
                  "limit": 5000,
                  "remaining": %d,
                  "reset": %d,
                  "used": %d
                }
              }
            }
            """, remaining, resetTime, 5000 - remaining);
    }

    /**
     * Creates test issue data for creating an issue
     */
    public static Map<String, Object> createIssueData(String title, String body, List<String> labels) {
        return Map.of(
            "title", title,
            "body", body,
            "labels", labels
        );
    }

    /**
     * Creates test PR data for creating a pull request
     */
    public static Map<String, Object> createPullRequestData(
            String title, String body, String head, String base) {
        return Map.of(
            "title", title,
            "body", body,
            "head", head,
            "base", base
        );
    }

    /**
     * Creates a test GitHub webhook payload for issue events
     */
    public static String createIssueWebhookPayload(String action, int issueNumber, String title) {
        return String.format("""
            {
              "action": "%s",
              "issue": {
                "number": %d,
                "title": "%s",
                "state": "open",
                "html_url": "https://github.com/test-org/test-repo/issues/%d"
              },
              "repository": {
                "name": "test-repo",
                "full_name": "test-org/test-repo"
              },
              "sender": {
                "login": "test-user"
              }
            }
            """, action, issueNumber, title, issueNumber);
    }

    /**
     * Creates a test GitHub webhook payload for PR events
     */
    public static String createPullRequestWebhookPayload(String action, int prNumber, String title) {
        return String.format("""
            {
              "action": "%s",
              "pull_request": {
                "number": %d,
                "title": "%s",
                "state": "open",
                "html_url": "https://github.com/test-org/test-repo/pull/%d",
                "head": {
                  "ref": "feature-branch"
                },
                "base": {
                  "ref": "main"
                }
              },
              "repository": {
                "name": "test-repo",
                "full_name": "test-org/test-repo"
              },
              "sender": {
                "login": "test-user"
              }
            }
            """, action, prNumber, title, prNumber);
    }

    /**
     * Creates a test GitHub file content response
     */
    public static String createFileContentResponseJson(String path, String content, String sha) {
        String encodedContent = java.util.Base64.getEncoder().encodeToString(content.getBytes());
        return String.format("""
            {
              "name": "%s",
              "path": "%s",
              "sha": "%s",
              "size": %d,
              "content": "%s",
              "encoding": "base64"
            }
            """, path.substring(path.lastIndexOf('/') + 1), path, sha, content.length(), encodedContent);
    }

    /**
     * Creates test remediation issue body
     */
    public static String createRemediationIssueBody(String namespace, String podName, String error) {
        return String.format("""
            ## Kubernetes Issue Detected
            
            **Namespace:** %s
            **Pod:** %s
            
            ### Error Details
            ```
            %s
            ```
            
            ### Recommended Actions
            1. Check pod logs for more details
            2. Verify resource limits and requests
            3. Check for configuration issues
            
            ---
            *This issue was automatically created by the Kubernetes AI Agent*
            """, namespace, podName, error);
    }

    /**
     * Creates test remediation PR body
     */
    public static String createRemediationPRBody(String issue, String changes) {
        return String.format("""
            ## Automated Remediation
            
            This PR addresses: %s
            
            ### Changes Made
            %s
            
            ### Testing
            - [ ] Verify the fix resolves the issue
            - [ ] Check for any side effects
            - [ ] Review resource usage
            
            ---
            *This PR was automatically created by the Kubernetes AI Agent*
            """, issue, changes);
    }
}

