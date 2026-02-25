package dev.kevindubois.rollout.agent.remediation;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.MockitoConfig;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for GitHubIssueTool.
 * Tests issue creation workflow, error handling, and helper methods.
 * Uses Quarkus test framework with @InjectMock for REST client mocking.
 * Uses @MockitoConfig(convertScopes = true) to convert @Singleton REST client to @ApplicationScoped for mocking.
 */
@QuarkusTest
class GitHubIssueToolTest {

    @Inject
    GitHubIssueTool gitHubIssueTool;

    @InjectMock
    @RestClient
    @MockitoConfig(convertScopes = true)
    GitHubRestClient githubClient;

    private static final String TEST_REPO_URL = "https://github.com/test-org/test-repo";
    private static final String TEST_TITLE = "Pod CrashLoopBackOff in production";
    private static final String TEST_DESCRIPTION = "Application pod is failing to start";
    private static final String TEST_ROOT_CAUSE = "Missing environment variable DATABASE_URL";
    private static final String TEST_NAMESPACE = "production";
    private static final String TEST_POD_NAME = "app-pod-123";

    @BeforeEach
    void setUp() {
        // Reset mocks before each test
        reset(githubClient);
    }

    // ========== createGitHubIssue() Success Tests ==========

    @Test
    void testCreateIssue_success() {
        // Given: Valid issue creation request with all parameters
        GitHubRestClient.GitHubIssue mockIssue = new GitHubRestClient.GitHubIssue(
            42,
            "https://github.com/test-org/test-repo/issues/42",
            "open",
            TEST_TITLE
        );
        when(githubClient.createIssue(eq("test-org"), eq("test-repo"), anyString(), any()))
            .thenReturn(mockIssue);

        // When: Creating issue
        Map<String, Object> result = gitHubIssueTool.createGitHubIssue(
            TEST_REPO_URL,
            TEST_TITLE,
            TEST_DESCRIPTION,
            TEST_ROOT_CAUSE,
            TEST_NAMESPACE,
            TEST_POD_NAME,
            "bug,kubernetes",
            "devops-team"
        );

        // Then: Should succeed with correct result
        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("issueUrl", "https://github.com/test-org/test-repo/issues/42");
        assertThat(result).containsEntry("issueNumber", 42);

        // Verify GitHub API was called with correct parameters
        ArgumentCaptor<GitHubRestClient.CreateIssueRequest> requestCaptor = 
            ArgumentCaptor.forClass(GitHubRestClient.CreateIssueRequest.class);
        verify(githubClient).createIssue(
            eq("test-org"),
            eq("test-repo"),
            contains("Bearer"),
            requestCaptor.capture()
        );

        // Verify request structure
        GitHubRestClient.CreateIssueRequest request = requestCaptor.getValue();
        assertThat(request.title()).isEqualTo(TEST_TITLE);
        assertThat(request.body()).contains(TEST_DESCRIPTION);
        assertThat(request.body()).contains(TEST_ROOT_CAUSE);
        assertThat(request.body()).contains(TEST_NAMESPACE);
        assertThat(request.body()).contains(TEST_POD_NAME);
        assertThat(request.labels()).containsExactly("bug", "kubernetes");
        assertThat(request.assignees()).containsExactly("devops-team");
    }

    // Note: testCreateIssue_missingGitHubToken is not included because GITHUB_TOKEN
    // is set as an environment variable during tests, making it difficult to test
    // the missing token scenario in a Quarkus test context.

    @Test
    void testCreateIssue_invalidRepoUrl() {
        // Given: Invalid repository URL
        String invalidUrl = "not-a-valid-github-url";

        // When: Creating issue with invalid URL
        Map<String, Object> result = gitHubIssueTool.createGitHubIssue(
            invalidUrl,
            TEST_TITLE,
            TEST_DESCRIPTION,
            TEST_ROOT_CAUSE,
            TEST_NAMESPACE,
            TEST_POD_NAME,
            null,
            null
        );

        // Then: Should handle error gracefully
        assertThat(result).containsEntry("success", false);
        assertThat(result).containsKey("error");

        // Verify GitHub API was never called
        verify(githubClient, never()).createIssue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void testCreateIssue_withLabelsAndAssignees() {
        // Given: Issue creation with multiple labels and assignees
        String labels = "bug, critical, kubernetes, production";
        String assignees = "alice, bob, charlie";

        GitHubRestClient.GitHubIssue mockIssue = new GitHubRestClient.GitHubIssue(
            100,
            "https://github.com/test-org/test-repo/issues/100",
            "open",
            TEST_TITLE
        );
        when(githubClient.createIssue(anyString(), anyString(), anyString(), any()))
            .thenReturn(mockIssue);

        // When: Creating issue
        Map<String, Object> result = gitHubIssueTool.createGitHubIssue(
            TEST_REPO_URL,
            TEST_TITLE,
            TEST_DESCRIPTION,
            TEST_ROOT_CAUSE,
            TEST_NAMESPACE,
            TEST_POD_NAME,
            labels,
            assignees
        );

        // Then: Should succeed
        assertThat(result).containsEntry("success", true);

        // Verify labels and assignees were parsed correctly (trimmed)
        ArgumentCaptor<GitHubRestClient.CreateIssueRequest> requestCaptor = 
            ArgumentCaptor.forClass(GitHubRestClient.CreateIssueRequest.class);
        verify(githubClient).createIssue(anyString(), anyString(), anyString(), requestCaptor.capture());

        GitHubRestClient.CreateIssueRequest request = requestCaptor.getValue();
        assertThat(request.labels()).containsExactly("bug", "critical", "kubernetes", "production");
        assertThat(request.assignees()).containsExactly("alice", "bob", "charlie");
    }

    @Test
    void testCreateIssue_githubAPIRateLimit() {
        // Given: GitHub API returns rate limit error
        when(githubClient.createIssue(anyString(), anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("API rate limit exceeded. Please try again later."));

        // When: Creating issue
        Map<String, Object> result = gitHubIssueTool.createGitHubIssue(
            TEST_REPO_URL,
            TEST_TITLE,
            TEST_DESCRIPTION,
            TEST_ROOT_CAUSE,
            TEST_NAMESPACE,
            TEST_POD_NAME,
            null,
            null
        );

        // Then: Should handle rate limit error
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("error").toString()).contains("rate limit");

        // Verify API was called once (no retry in current implementation)
        verify(githubClient, times(1)).createIssue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void testCreateIssue_githubAPIError() {
        // Given: GitHub API returns generic error
        when(githubClient.createIssue(anyString(), anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("Repository not found or access denied"));

        // When: Creating issue
        Map<String, Object> result = gitHubIssueTool.createGitHubIssue(
            TEST_REPO_URL,
            TEST_TITLE,
            TEST_DESCRIPTION,
            TEST_ROOT_CAUSE,
            TEST_NAMESPACE,
            TEST_POD_NAME,
            null,
            null
        );

        // Then: Should handle error gracefully
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("error").toString()).contains("Repository not found");

        // Verify API was called
        verify(githubClient, times(1)).createIssue(anyString(), anyString(), anyString(), any());
    }

    // ========== Helper Method Tests ==========

    @Test
    void testExtractOwnerAndRepo_httpsUrl() {
        // Given: Standard HTTPS GitHub URL
        String httpsUrl = "https://github.com/kubernetes/kubernetes";

        GitHubRestClient.GitHubIssue mockIssue = new GitHubRestClient.GitHubIssue(
            1,
            "https://github.com/kubernetes/kubernetes/issues/1",
            "open",
            "Test"
        );
        when(githubClient.createIssue(eq("kubernetes"), eq("kubernetes"), anyString(), any()))
            .thenReturn(mockIssue);

        // When: Creating issue with HTTPS URL
        Map<String, Object> result = gitHubIssueTool.createGitHubIssue(
            httpsUrl,
            "Test Issue",
            "Description",
            "Root cause",
            "default",
            "pod-1",
            null,
            null
        );

        // Then: Should correctly extract owner and repo
        assertThat(result).containsEntry("success", true);
        verify(githubClient).createIssue(eq("kubernetes"), eq("kubernetes"), anyString(), any());
    }

    @Test
    void testExtractOwnerAndRepo_gitUrl() {
        // Given: Git URL with .git extension
        String gitUrl = "https://github.com/argoproj/argo-rollouts.git";

        GitHubRestClient.GitHubIssue mockIssue = new GitHubRestClient.GitHubIssue(
            1,
            "https://github.com/argoproj/argo-rollouts/issues/1",
            "open",
            "Test"
        );
        when(githubClient.createIssue(eq("argoproj"), eq("argo-rollouts"), anyString(), any()))
            .thenReturn(mockIssue);

        // When: Creating issue with .git URL
        Map<String, Object> result = gitHubIssueTool.createGitHubIssue(
            gitUrl,
            "Test Issue",
            "Description",
            "Root cause",
            "default",
            "pod-1",
            null,
            null
        );

        // Then: Should correctly extract owner and repo (without .git)
        assertThat(result).containsEntry("success", true);
        verify(githubClient).createIssue(eq("argoproj"), eq("argo-rollouts"), anyString(), any());
    }

    @Test
    void testExtractOwnerAndRepo_invalidUrl() {
        // Given: Invalid URL format
        String invalidUrl = "not-a-github-url";

        // When: Creating issue with invalid URL
        Map<String, Object> result = gitHubIssueTool.createGitHubIssue(
            invalidUrl,
            "Test Issue",
            "Description",
            "Root cause",
            "default",
            "pod-1",
            null,
            null
        );

        // Then: Should handle error
        assertThat(result).containsEntry("success", false);
        assertThat(result).containsKey("error");

        // Verify GitHub API was never called
        verify(githubClient, never()).createIssue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void testGenerateIssueBody_complete() {
        // Given: Complete issue data with all fields
        String description = "Application is experiencing high memory usage";
        String rootCause = "Memory leak in cache implementation";
        String namespace = "production";
        String podName = "app-deployment-abc123";

        GitHubRestClient.GitHubIssue mockIssue = new GitHubRestClient.GitHubIssue(
            1,
            "https://github.com/test-org/test-repo/issues/1",
            "open",
            "Test"
        );
        when(githubClient.createIssue(anyString(), anyString(), anyString(), any()))
            .thenReturn(mockIssue);

        // When: Creating issue
        gitHubIssueTool.createGitHubIssue(
            TEST_REPO_URL,
            "Memory Issue",
            description,
            rootCause,
            namespace,
            podName,
            null,
            null
        );

        // Then: Verify issue body contains all expected sections
        ArgumentCaptor<GitHubRestClient.CreateIssueRequest> requestCaptor = 
            ArgumentCaptor.forClass(GitHubRestClient.CreateIssueRequest.class);
        verify(githubClient).createIssue(anyString(), anyString(), anyString(), requestCaptor.capture());

        String issueBody = requestCaptor.getValue().body();

        // Verify all sections are present
        assertThat(issueBody).contains("## Problem Description");
        assertThat(issueBody).contains(description);
        assertThat(issueBody).contains("## Root Cause Analysis");
        assertThat(issueBody).contains(rootCause);
        assertThat(issueBody).contains("## Related Kubernetes Resources");
        assertThat(issueBody).contains("**Namespace**: `" + namespace + "`");
        assertThat(issueBody).contains("**Pod**: `" + podName + "`");
        assertThat(issueBody).contains("automatically created by Kubernetes AI Agent");
    }

    @Test
    void testCreateIssue_withNullOptionalFields() {
        // Given: Issue creation with null optional fields
        GitHubRestClient.GitHubIssue mockIssue = new GitHubRestClient.GitHubIssue(
            1,
            "https://github.com/test-org/test-repo/issues/1",
            "open",
            TEST_TITLE
        );
        when(githubClient.createIssue(anyString(), anyString(), anyString(), any()))
            .thenReturn(mockIssue);

        // When: Creating issue with null optional fields
        Map<String, Object> result = gitHubIssueTool.createGitHubIssue(
            TEST_REPO_URL,
            TEST_TITLE,
            TEST_DESCRIPTION,
            null,  // null root cause
            null,  // null namespace
            null,  // null pod name
            null,  // null labels
            null   // null assignees
        );

        // Then: Should succeed with default values
        assertThat(result).containsEntry("success", true);

        // Verify issue body has default values
        ArgumentCaptor<GitHubRestClient.CreateIssueRequest> requestCaptor = 
            ArgumentCaptor.forClass(GitHubRestClient.CreateIssueRequest.class);
        verify(githubClient).createIssue(anyString(), anyString(), anyString(), requestCaptor.capture());

        GitHubRestClient.CreateIssueRequest request = requestCaptor.getValue();
        String issueBody = request.body();
        
        assertThat(issueBody).contains("Not available"); // Default root cause
        assertThat(issueBody).contains("unknown"); // Default namespace and pod
        assertThat(request.labels()).isEmpty();
        assertThat(request.assignees()).isEmpty();
    }

    @Test
    void testCreateIssue_missingRequiredParameters() {
        // Given: Missing required parameters (null repoUrl)
        // When: Creating issue
        Map<String, Object> result = gitHubIssueTool.createGitHubIssue(
            null,  // null repoUrl
            TEST_TITLE,
            TEST_DESCRIPTION,
            TEST_ROOT_CAUSE,
            TEST_NAMESPACE,
            TEST_POD_NAME,
            null,
            null
        );

        // Then: Should return error
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("error").toString()).contains("required parameters");

        // Verify GitHub API was never called
        verify(githubClient, never()).createIssue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void testCreateIssue_emptyLabelsAndAssignees() {
        // Given: Empty strings for labels and assignees
        GitHubRestClient.GitHubIssue mockIssue = new GitHubRestClient.GitHubIssue(
            1,
            "https://github.com/test-org/test-repo/issues/1",
            "open",
            TEST_TITLE
        );
        when(githubClient.createIssue(anyString(), anyString(), anyString(), any()))
            .thenReturn(mockIssue);

        // When: Creating issue with empty strings
        Map<String, Object> result = gitHubIssueTool.createGitHubIssue(
            TEST_REPO_URL,
            TEST_TITLE,
            TEST_DESCRIPTION,
            TEST_ROOT_CAUSE,
            TEST_NAMESPACE,
            TEST_POD_NAME,
            "",  // empty labels
            ""   // empty assignees
        );

        // Then: Should succeed with empty arrays
        assertThat(result).containsEntry("success", true);

        // Verify empty arrays were passed
        ArgumentCaptor<GitHubRestClient.CreateIssueRequest> requestCaptor = 
            ArgumentCaptor.forClass(GitHubRestClient.CreateIssueRequest.class);
        verify(githubClient).createIssue(anyString(), anyString(), anyString(), requestCaptor.capture());

        GitHubRestClient.CreateIssueRequest request = requestCaptor.getValue();
        assertThat(request.labels()).isEmpty();
        assertThat(request.assignees()).isEmpty();
    }

    @Test
    void testCreateIssue_labelsWithWhitespace() {
        // Given: Labels with extra whitespace
        String labelsWithSpaces = "  bug  ,  critical  ,  kubernetes  ";

        GitHubRestClient.GitHubIssue mockIssue = new GitHubRestClient.GitHubIssue(
            1,
            "https://github.com/test-org/test-repo/issues/1",
            "open",
            TEST_TITLE
        );
        when(githubClient.createIssue(anyString(), anyString(), anyString(), any()))
            .thenReturn(mockIssue);

        // When: Creating issue
        Map<String, Object> result = gitHubIssueTool.createGitHubIssue(
            TEST_REPO_URL,
            TEST_TITLE,
            TEST_DESCRIPTION,
            TEST_ROOT_CAUSE,
            TEST_NAMESPACE,
            TEST_POD_NAME,
            labelsWithSpaces,
            null
        );

        // Then: Should succeed with trimmed labels
        assertThat(result).containsEntry("success", true);

        // Verify labels were trimmed
        ArgumentCaptor<GitHubRestClient.CreateIssueRequest> requestCaptor = 
            ArgumentCaptor.forClass(GitHubRestClient.CreateIssueRequest.class);
        verify(githubClient).createIssue(anyString(), anyString(), anyString(), requestCaptor.capture());

        GitHubRestClient.CreateIssueRequest request = requestCaptor.getValue();
        assertThat(request.labels()).containsExactly("bug", "critical", "kubernetes");
    }
}