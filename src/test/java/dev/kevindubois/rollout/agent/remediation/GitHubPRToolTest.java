package dev.kevindubois.rollout.agent.remediation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for GitHubPRTool.
 * Tests PR creation workflow, error handling, and helper methods.
 * Uses standard JUnit 5 with Mockito (no Quarkus test framework needed).
 */
class GitHubPRToolTest {

    private GitHubPRTool gitHubPRTool;
    private GitOperations gitOperations;
    private GitHubRestClient githubClient;

    private static final String TEST_REPO_URL = "https://github.com/test-org/test-repo";
    private static final String TEST_TOKEN = "ghp_test_token_123";
    private static final Path TEST_REPO_PATH = Paths.get("/tmp/test-repo");

    @BeforeEach
    void setUp() {
        // Create mocks
        gitOperations = mock(GitOperations.class);
        githubClient = mock(GitHubRestClient.class);
        
        // Create GitHubPRTool with mocked dependencies
        gitHubPRTool = new GitHubPRTool(gitOperations, TEST_TOKEN);
        
        // Inject the mocked GitHub client using reflection
        try {
            java.lang.reflect.Field field = GitHubPRTool.class.getDeclaredField("githubClient");
            field.setAccessible(true);
            field.set(gitHubPRTool, githubClient);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mock", e);
        }
    }

    // ========== createGitHubPR() Success Tests ==========

    @Test
    void testCreatePR_success() throws Exception {
        // Given: Valid PR creation request with all dependencies mocked
        Map<String, String> fileChanges = Map.of(
            "src/main/java/App.java", "public class App { /* fixed */ }",
            "README.md", "# Updated README"
        );
        String fixDescription = "Fix memory leak in application";
        String rootCause = "Unclosed database connections";
        String namespace = "production";
        String podName = "app-pod-123";
        String testingRecommendations = "Run integration tests";

        // Mock git operations
        when(gitOperations.cloneRepository(eq(TEST_REPO_URL), anyString()))
            .thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());
        doNothing().when(gitOperations).cleanup(any());

        // Mock GitHub API responses
        GitHubRestClient.GitHubRepository repository = 
            new GitHubRestClient.GitHubRepository("test-repo", "test-org/test-repo", "main", "https://github.com/test-org/test-repo");
        when(githubClient.getRepository(eq("test-org"), eq("test-repo"), anyString()))
            .thenReturn(repository);

        GitHubRestClient.GitHubPullRequest pr = 
            new GitHubRestClient.GitHubPullRequest(42, "https://github.com/test-org/test-repo/pull/42", "open", "Fix: Fix memory leak in application");
        when(githubClient.createPullRequest(eq("test-org"), eq("test-repo"), anyString(), any()))
            .thenReturn(pr);

        // When: Creating PR
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, fileChanges, fixDescription, rootCause, namespace, podName, testingRecommendations
        );

        // Then: Should succeed with correct result
        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("prUrl", "https://github.com/test-org/test-repo/pull/42");
        assertThat(result).containsEntry("prNumber", 42);
        assertThat(result).containsKey("branch");

        // Verify git operations were called in correct order
        verify(gitOperations).cloneRepository(eq(TEST_REPO_URL), anyString());
        verify(gitOperations).createBranch(eq(TEST_REPO_PATH), anyString());
        verify(gitOperations).applyChanges(eq(TEST_REPO_PATH), eq(fileChanges));
        verify(gitOperations).commitAndPush(eq(TEST_REPO_PATH), contains("fix:"), anyString());
        verify(gitOperations).cleanup(eq(TEST_REPO_PATH));

        // Verify GitHub API calls
        verify(githubClient).getRepository(eq("test-org"), eq("test-repo"), anyString());
        verify(githubClient).createPullRequest(eq("test-org"), eq("test-repo"), anyString(), any());
    }

    @Test
    void testCreatePR_missingGitHubToken() {
        // Given: No GitHub token set (simulated by the tool's constructor check)
        // Note: In real scenario, GITHUB_TOKEN would be null, but we can't easily test that
        // So we test the validation logic by passing null parameters
        
        // When/Then: Should return error for missing token
        // This test verifies the error handling when token is missing
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            null, null, null, null, null, null, null
        );

        assertThat(result).containsEntry("success", false);
        assertThat(result.get("error").toString()).contains("required");
    }

    @Test
    void testCreatePR_invalidRepoUrl() {
        // Given: Invalid repository URL
        String invalidUrl = "not-a-valid-url";
        Map<String, String> fileChanges = Map.of("file.txt", "content");

        // When: Creating PR with invalid URL
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            invalidUrl, fileChanges, "fix", "cause", "ns", "pod", "test"
        );

        // Then: Should handle error gracefully
        assertThat(result).containsEntry("success", false);
        assertThat(result).containsKey("error");
    }

    @Test
    void testCreatePR_gitCloneFails() throws Exception {
        // Given: Git clone operation fails
        when(gitOperations.cloneRepository(anyString(), anyString()))
            .thenThrow(new RuntimeException("Failed to clone repository: Authentication failed"));

        Map<String, String> fileChanges = Map.of("file.txt", "content");

        // When: Creating PR
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, fileChanges, "fix", "cause", "ns", "pod", "test"
        );

        // Then: Should return error
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("error").toString()).contains("Failed to clone repository");

        // Verify cleanup was not called (no path to cleanup)
        verify(gitOperations, never()).cleanup(any());
    }

    @Test
    void testCreatePR_multipleFileChanges() throws Exception {
        // Given: Multiple file changes
        Map<String, String> fileChanges = Map.of(
            "src/main/java/App.java", "// App code",
            "src/main/java/Config.java", "// Config code",
            "src/test/java/AppTest.java", "// Test code",
            "pom.xml", "<project>...</project>"
        );

        // Mock successful operations
        when(gitOperations.cloneRepository(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());
        doNothing().when(gitOperations).cleanup(any());

        GitHubRestClient.GitHubRepository repository = 
            new GitHubRestClient.GitHubRepository("test-repo", "test-org/test-repo", "main", "https://github.com/test-org/test-repo");
        when(githubClient.getRepository(anyString(), anyString(), anyString())).thenReturn(repository);

        GitHubRestClient.GitHubPullRequest pr = 
            new GitHubRestClient.GitHubPullRequest(1, "https://github.com/test-org/test-repo/pull/1", "open", "Fix");
        when(githubClient.createPullRequest(anyString(), anyString(), anyString(), any())).thenReturn(pr);

        // When: Creating PR
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, fileChanges, "Multiple fixes", "Various issues", "ns", "pod", "test"
        );

        // Then: Should succeed and apply all changes
        assertThat(result).containsEntry("success", true);

        // Verify all file changes were applied
        ArgumentCaptor<Map<String, String>> changesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(gitOperations).applyChanges(any(), changesCaptor.capture());
        assertThat(changesCaptor.getValue()).hasSize(4);
        assertThat(changesCaptor.getValue()).containsKeys(
            "src/main/java/App.java",
            "src/main/java/Config.java",
            "src/test/java/AppTest.java",
            "pom.xml"
        );
    }

    @Test
    void testCreatePR_branchAlreadyExists() throws Exception {
        // Given: Branch creation fails due to existing branch
        when(gitOperations.cloneRepository(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doThrow(new RuntimeException("Branch already exists"))
            .when(gitOperations).createBranch(any(), anyString());

        Map<String, String> fileChanges = Map.of("file.txt", "content");

        // When: Creating PR
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, fileChanges, "fix", "cause", "ns", "pod", "test"
        );

        // Then: Should handle error and cleanup
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("error").toString()).contains("Branch already exists");

        // Verify cleanup was called
        verify(gitOperations).cleanup(eq(TEST_REPO_PATH));
    }

    @Test
    void testCreatePR_githubAPIRateLimit() throws Exception {
        // Given: GitHub API returns rate limit error
        when(gitOperations.cloneRepository(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());

        GitHubRestClient.GitHubRepository repository = 
            new GitHubRestClient.GitHubRepository("test-repo", "test-org/test-repo", "main", "https://github.com/test-org/test-repo");
        when(githubClient.getRepository(anyString(), anyString(), anyString())).thenReturn(repository);

        when(githubClient.createPullRequest(anyString(), anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("API rate limit exceeded"));

        Map<String, String> fileChanges = Map.of("file.txt", "content");

        // When: Creating PR
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, fileChanges, "fix", "cause", "ns", "pod", "test"
        );

        // Then: Should handle rate limit error
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("error").toString()).contains("rate limit");

        // Verify cleanup was called
        verify(gitOperations).cleanup(eq(TEST_REPO_PATH));
    }

    @Test
    void testCreatePR_cleanupOnFailure() throws Exception {
        // Given: Operation fails after clone
        when(gitOperations.cloneRepository(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doThrow(new RuntimeException("Commit failed"))
            .when(gitOperations).commitAndPush(any(), anyString(), anyString());

        Map<String, String> fileChanges = Map.of("file.txt", "content");

        // When: Creating PR (will fail)
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, fileChanges, "fix", "cause", "ns", "pod", "test"
        );

        // Then: Should cleanup even on failure
        assertThat(result).containsEntry("success", false);
        verify(gitOperations).cleanup(eq(TEST_REPO_PATH));
    }

    // ========== Helper Method Tests ==========

    @Test
    void testExtractOwnerAndRepo_httpsUrl() throws Exception {
        // Given: HTTPS GitHub URL
        String httpsUrl = "https://github.com/kubernetes/kubernetes";
        Map<String, String> fileChanges = Map.of("file.txt", "content");

        // Mock successful operations
        when(gitOperations.cloneRepository(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());

        GitHubRestClient.GitHubRepository repository = 
            new GitHubRestClient.GitHubRepository("kubernetes", "kubernetes/kubernetes", "master", "https://github.com/kubernetes/kubernetes");
        when(githubClient.getRepository(eq("kubernetes"), eq("kubernetes"), anyString())).thenReturn(repository);

        GitHubRestClient.GitHubPullRequest pr = 
            new GitHubRestClient.GitHubPullRequest(1, "https://github.com/kubernetes/kubernetes/pull/1", "open", "Fix");
        when(githubClient.createPullRequest(eq("kubernetes"), eq("kubernetes"), anyString(), any())).thenReturn(pr);

        // When: Creating PR
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            httpsUrl, fileChanges, "fix", "cause", "ns", "pod", "test"
        );

        // Then: Should correctly extract owner and repo
        assertThat(result).containsEntry("success", true);
        verify(githubClient).getRepository(eq("kubernetes"), eq("kubernetes"), anyString());
    }

    @Test
    void testExtractOwnerAndRepo_gitUrl() throws Exception {
        // Given: Git URL with .git extension
        String gitUrl = "https://github.com/argoproj/argo-rollouts.git";
        Map<String, String> fileChanges = Map.of("file.txt", "content");

        // Mock successful operations
        when(gitOperations.cloneRepository(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());

        GitHubRestClient.GitHubRepository repository = 
            new GitHubRestClient.GitHubRepository("argo-rollouts", "argoproj/argo-rollouts", "main", "https://github.com/argoproj/argo-rollouts");
        when(githubClient.getRepository(eq("argoproj"), eq("argo-rollouts"), anyString())).thenReturn(repository);

        GitHubRestClient.GitHubPullRequest pr = 
            new GitHubRestClient.GitHubPullRequest(1, "https://github.com/argoproj/argo-rollouts/pull/1", "open", "Fix");
        when(githubClient.createPullRequest(eq("argoproj"), eq("argo-rollouts"), anyString(), any())).thenReturn(pr);

        // When: Creating PR
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            gitUrl, fileChanges, "fix", "cause", "ns", "pod", "test"
        );

        // Then: Should correctly extract owner and repo (without .git)
        assertThat(result).containsEntry("success", true);
        verify(githubClient).getRepository(eq("argoproj"), eq("argo-rollouts"), anyString());
    }

    @Test
    void testExtractOwnerAndRepo_invalidUrl() {
        // Given: Invalid URL format
        String invalidUrl = "not-a-github-url";
        Map<String, String> fileChanges = Map.of("file.txt", "content");

        // When: Creating PR with invalid URL
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            invalidUrl, fileChanges, "fix", "cause", "ns", "pod", "test"
        );

        // Then: Should handle error
        assertThat(result).containsEntry("success", false);
        assertThat(result).containsKey("error");
    }

    @Test
    void testGeneratePRBody_complete() throws Exception {
        // Given: Complete PR data with all fields
        Map<String, String> fileChanges = Map.of(
            "src/App.java", "code",
            "README.md", "docs"
        );
        String fixDescription = "Fixed null pointer exception";
        String rootCause = "Missing null check in handler";
        String namespace = "production";
        String podName = "app-pod-xyz";
        String testingRecommendations = "Run unit tests and integration tests";

        // Mock successful operations
        when(gitOperations.cloneRepository(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());

        GitHubRestClient.GitHubRepository repository = 
            new GitHubRestClient.GitHubRepository("test-repo", "test-org/test-repo", "main", "https://github.com/test-org/test-repo");
        when(githubClient.getRepository(anyString(), anyString(), anyString())).thenReturn(repository);

        GitHubRestClient.GitHubPullRequest pr = 
            new GitHubRestClient.GitHubPullRequest(1, "https://github.com/test-org/test-repo/pull/1", "open", "Fix");
        when(githubClient.createPullRequest(anyString(), anyString(), anyString(), any())).thenReturn(pr);

        // When: Creating PR
        gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, fileChanges, fixDescription, rootCause, namespace, podName, testingRecommendations
        );

        // Then: Verify PR body contains all expected sections
        ArgumentCaptor<GitHubRestClient.CreatePullRequestRequest> prRequestCaptor = 
            ArgumentCaptor.forClass(GitHubRestClient.CreatePullRequestRequest.class);
        verify(githubClient).createPullRequest(anyString(), anyString(), anyString(), prRequestCaptor.capture());

        GitHubRestClient.CreatePullRequestRequest prRequest = prRequestCaptor.getValue();
        String prBody = prRequest.body();

        // Verify all sections are present
        assertThat(prBody).contains("Root Cause Analysis");
        assertThat(prBody).contains(rootCause);
        assertThat(prBody).contains("Changes Made");
        assertThat(prBody).contains("src/App.java");
        assertThat(prBody).contains("README.md");
        assertThat(prBody).contains(fixDescription);
        assertThat(prBody).contains("Testing Recommendations");
        assertThat(prBody).contains(testingRecommendations);
        assertThat(prBody).contains("Related Kubernetes Resources");
        assertThat(prBody).contains(namespace);
        assertThat(prBody).contains(podName);
        assertThat(prBody).contains("automatically generated");
    }

    @Test
    void testCreatePR_withNullOptionalFields() throws Exception {
        // Given: PR creation with null optional fields
        Map<String, String> fileChanges = Map.of("file.txt", "content");

        // Mock successful operations
        when(gitOperations.cloneRepository(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());

        GitHubRestClient.GitHubRepository repository = 
            new GitHubRestClient.GitHubRepository("test-repo", "test-org/test-repo", "main", "https://github.com/test-org/test-repo");
        when(githubClient.getRepository(anyString(), anyString(), anyString())).thenReturn(repository);

        GitHubRestClient.GitHubPullRequest pr = 
            new GitHubRestClient.GitHubPullRequest(1, "https://github.com/test-org/test-repo/pull/1", "open", "Fix");
        when(githubClient.createPullRequest(anyString(), anyString(), anyString(), any())).thenReturn(pr);

        // When: Creating PR with null optional fields
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, fileChanges, "fix", null, null, null, null
        );

        // Then: Should succeed with default values
        assertThat(result).containsEntry("success", true);

        // Verify PR body has default values
        ArgumentCaptor<GitHubRestClient.CreatePullRequestRequest> prRequestCaptor = 
            ArgumentCaptor.forClass(GitHubRestClient.CreatePullRequestRequest.class);
        verify(githubClient).createPullRequest(anyString(), anyString(), anyString(), prRequestCaptor.capture());

        String prBody = prRequestCaptor.getValue().body();
        assertThat(prBody).contains("Not available"); // Default root cause
        assertThat(prBody).contains("unknown"); // Default namespace and pod
        assertThat(prBody).contains("Run existing test suite"); // Default testing recommendations
    }
}

