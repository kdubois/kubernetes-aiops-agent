package dev.kevindubois.rollout.agent.remediation;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.MockitoConfig;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
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
 * Unit tests for GitHubPRTool using the Quarkus test framework.
 * CDI wires the real bean; collaborators are replaced with Mockito mocks via @InjectMock.
 */
@QuarkusTest
class GitHubPRToolTest {

    @Inject
    GitHubPRTool gitHubPRTool;

    @InjectMock
    GitOperations gitOperations;

    @InjectMock
    RepoCloneCache repoCache;

    @InjectMock
    @RestClient
    @MockitoConfig(convertScopes = true)
    GitHubRestClient githubClient;

    private static final String TEST_REPO_URL = "https://github.com/test-org/test-repo";
    private static final Path TEST_REPO_PATH = Paths.get("/tmp/test-repo");

    @BeforeEach
    void setUp() {
        reset(gitOperations, repoCache, githubClient);
    }

    // ========== createGitHubPR() Success Tests ==========

    @Test
    void testCreatePR_success() throws Exception {
        Map<String, String> fileChanges = Map.of(
            "src/main/java/App.java", "public class App { /* fixed */ }",
            "README.md", "# Updated README"
        );

        when(repoCache.getOrClone(eq(TEST_REPO_URL), anyString()))
            .thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());

        GitHubRestClient.GitHubRepository repository =
            new GitHubRestClient.GitHubRepository("test-repo", "test-org/test-repo", "main", "https://github.com/test-org/test-repo");
        when(githubClient.getRepository(eq("test-org"), eq("test-repo"), anyString()))
            .thenReturn(repository);

        GitHubRestClient.GitHubPullRequest pr =
            new GitHubRestClient.GitHubPullRequest(42, "https://github.com/test-org/test-repo/pull/42", "open", "Fix: Fix memory leak");
        when(githubClient.createPullRequest(eq("test-org"), eq("test-repo"), anyString(), any()))
            .thenReturn(pr);

        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, fileChanges, "Fix memory leak", "Unclosed connections", "production", "app-pod-123", "Run integration tests"
        );

        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("prUrl", "https://github.com/test-org/test-repo/pull/42");
        assertThat(result).containsEntry("prNumber", 42);
        assertThat(result).containsKey("branch");

        verify(repoCache).getOrClone(eq(TEST_REPO_URL), anyString());
        verify(gitOperations).createBranch(eq(TEST_REPO_PATH), anyString());
        verify(gitOperations).applyChanges(eq(TEST_REPO_PATH), eq(fileChanges));
        verify(gitOperations).commitAndPush(eq(TEST_REPO_PATH), contains("fix:"), anyString());
        verify(githubClient).getRepository(eq("test-org"), eq("test-repo"), anyString());
        verify(githubClient).createPullRequest(eq("test-org"), eq("test-repo"), anyString(), any());
    }

    @Test
    void testCreatePR_missingRequiredParams() {
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            null, null, null, null, null, null, null
        );

        assertThat(result).containsEntry("success", false);
        assertThat(result.get("error").toString()).contains("required");
    }

    @Test
    void testCreatePR_invalidRepoUrl() {
        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            "not-a-valid-url", Map.of("file.txt", "content"), "fix", "cause", "ns", "pod", "test"
        );

        assertThat(result).containsEntry("success", false);
        assertThat(result).containsKey("error");
    }

    @Test
    void testCreatePR_gitCloneFails() throws Exception {
        when(repoCache.getOrClone(anyString(), anyString()))
            .thenThrow(new RuntimeException("Failed to clone repository: Authentication failed"));

        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, Map.of("file.txt", "content"), "fix", "cause", "ns", "pod", "test"
        );

        assertThat(result).containsEntry("success", false);
        assertThat(result.get("error").toString()).contains("Failed to clone repository");
    }

    @Test
    void testCreatePR_multipleFileChanges() throws Exception {
        Map<String, String> fileChanges = Map.of(
            "src/main/java/App.java", "// App code",
            "src/main/java/Config.java", "// Config code",
            "src/test/java/AppTest.java", "// Test code",
            "pom.xml", "<project>...</project>"
        );

        when(repoCache.getOrClone(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());

        GitHubRestClient.GitHubRepository repository =
            new GitHubRestClient.GitHubRepository("test-repo", "test-org/test-repo", "main", "https://github.com/test-org/test-repo");
        when(githubClient.getRepository(anyString(), anyString(), anyString())).thenReturn(repository);

        GitHubRestClient.GitHubPullRequest pr =
            new GitHubRestClient.GitHubPullRequest(1, "https://github.com/test-org/test-repo/pull/1", "open", "Fix");
        when(githubClient.createPullRequest(anyString(), anyString(), anyString(), any())).thenReturn(pr);

        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, fileChanges, "Multiple fixes", "Various issues", "ns", "pod", "test"
        );

        assertThat(result).containsEntry("success", true);

        ArgumentCaptor<Map<String, String>> changesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(gitOperations).applyChanges(any(), changesCaptor.capture());
        assertThat(changesCaptor.getValue()).hasSize(4);
        assertThat(changesCaptor.getValue()).containsKeys(
            "src/main/java/App.java", "src/main/java/Config.java",
            "src/test/java/AppTest.java", "pom.xml"
        );
    }

    @Test
    void testCreatePR_branchAlreadyExists() throws Exception {
        when(repoCache.getOrClone(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doThrow(new RuntimeException("Branch already exists"))
            .when(gitOperations).createBranch(any(), anyString());

        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, Map.of("file.txt", "content"), "fix", "cause", "ns", "pod", "test"
        );

        assertThat(result).containsEntry("success", false);
        assertThat(result.get("error").toString()).contains("Branch already exists");
    }

    @Test
    void testCreatePR_githubAPIRateLimit() throws Exception {
        when(repoCache.getOrClone(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());

        GitHubRestClient.GitHubRepository repository =
            new GitHubRestClient.GitHubRepository("test-repo", "test-org/test-repo", "main", "https://github.com/test-org/test-repo");
        when(githubClient.getRepository(anyString(), anyString(), anyString())).thenReturn(repository);
        when(githubClient.createPullRequest(anyString(), anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("API rate limit exceeded"));

        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, Map.of("file.txt", "content"), "fix", "cause", "ns", "pod", "test"
        );

        assertThat(result).containsEntry("success", false);
        assertThat(result.get("error").toString()).contains("rate limit");
    }

    @Test
    void testCreatePR_failureAfterClone() throws Exception {
        when(repoCache.getOrClone(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doThrow(new RuntimeException("Commit failed"))
            .when(gitOperations).commitAndPush(any(), anyString(), anyString());

        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, Map.of("file.txt", "content"), "fix", "cause", "ns", "pod", "test"
        );

        assertThat(result).containsEntry("success", false);
    }

    @Test
    void testExtractOwnerAndRepo_httpsUrl() throws Exception {
        when(repoCache.getOrClone(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());

        GitHubRestClient.GitHubRepository repository =
            new GitHubRestClient.GitHubRepository("kubernetes", "kubernetes/kubernetes", "master", "https://github.com/kubernetes/kubernetes");
        when(githubClient.getRepository(eq("kubernetes"), eq("kubernetes"), anyString())).thenReturn(repository);

        GitHubRestClient.GitHubPullRequest pr =
            new GitHubRestClient.GitHubPullRequest(1, "https://github.com/kubernetes/kubernetes/pull/1", "open", "Fix");
        when(githubClient.createPullRequest(eq("kubernetes"), eq("kubernetes"), anyString(), any())).thenReturn(pr);

        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            "https://github.com/kubernetes/kubernetes", Map.of("file.txt", "content"), "fix", "cause", "ns", "pod", "test"
        );

        assertThat(result).containsEntry("success", true);
        verify(githubClient).getRepository(eq("kubernetes"), eq("kubernetes"), anyString());
    }

    @Test
    void testExtractOwnerAndRepo_gitUrl() throws Exception {
        when(repoCache.getOrClone(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());

        GitHubRestClient.GitHubRepository repository =
            new GitHubRestClient.GitHubRepository("argo-rollouts", "argoproj/argo-rollouts", "main", "https://github.com/argoproj/argo-rollouts");
        when(githubClient.getRepository(eq("argoproj"), eq("argo-rollouts"), anyString())).thenReturn(repository);

        GitHubRestClient.GitHubPullRequest pr =
            new GitHubRestClient.GitHubPullRequest(1, "https://github.com/argoproj/argo-rollouts/pull/1", "open", "Fix");
        when(githubClient.createPullRequest(eq("argoproj"), eq("argo-rollouts"), anyString(), any())).thenReturn(pr);

        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            "https://github.com/argoproj/argo-rollouts.git", Map.of("file.txt", "content"), "fix", "cause", "ns", "pod", "test"
        );

        assertThat(result).containsEntry("success", true);
        verify(githubClient).getRepository(eq("argoproj"), eq("argo-rollouts"), anyString());
    }

    @Test
    void testCreatePR_prBodyContainsAllSections() throws Exception {
        when(repoCache.getOrClone(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());

        GitHubRestClient.GitHubRepository repository =
            new GitHubRestClient.GitHubRepository("test-repo", "test-org/test-repo", "main", "https://github.com/test-org/test-repo");
        when(githubClient.getRepository(anyString(), anyString(), anyString())).thenReturn(repository);

        GitHubRestClient.GitHubPullRequest pr =
            new GitHubRestClient.GitHubPullRequest(1, "https://github.com/test-org/test-repo/pull/1", "open", "Fix");
        when(githubClient.createPullRequest(anyString(), anyString(), anyString(), any())).thenReturn(pr);

        gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, Map.of("src/App.java", "code", "README.md", "docs"),
            "Fixed null pointer exception", "Missing null check in handler",
            "production", "app-pod-xyz", "Run unit tests and integration tests"
        );

        ArgumentCaptor<GitHubRestClient.CreatePullRequestRequest> captor =
            ArgumentCaptor.forClass(GitHubRestClient.CreatePullRequestRequest.class);
        verify(githubClient).createPullRequest(anyString(), anyString(), anyString(), captor.capture());

        String prBody = captor.getValue().body();
        assertThat(prBody).contains("Root Cause Analysis");
        assertThat(prBody).contains("Missing null check in handler");
        assertThat(prBody).contains("Changes Made");
        assertThat(prBody).contains("src/App.java");
        assertThat(prBody).contains("Fixed null pointer exception");
        assertThat(prBody).contains("Testing Recommendations");
        assertThat(prBody).contains("Run unit tests and integration tests");
        assertThat(prBody).contains("Related Kubernetes Resources");
        assertThat(prBody).contains("production");
        assertThat(prBody).contains("app-pod-xyz");
        assertThat(prBody).contains("automatically generated");
    }

    @Test
    void testCreatePR_withNullOptionalFields() throws Exception {
        when(repoCache.getOrClone(anyString(), anyString())).thenReturn(TEST_REPO_PATH);
        doNothing().when(gitOperations).createBranch(any(), anyString());
        doNothing().when(gitOperations).applyChanges(any(), any());
        doNothing().when(gitOperations).commitAndPush(any(), anyString(), anyString());

        GitHubRestClient.GitHubRepository repository =
            new GitHubRestClient.GitHubRepository("test-repo", "test-org/test-repo", "main", "https://github.com/test-org/test-repo");
        when(githubClient.getRepository(anyString(), anyString(), anyString())).thenReturn(repository);

        GitHubRestClient.GitHubPullRequest pr =
            new GitHubRestClient.GitHubPullRequest(1, "https://github.com/test-org/test-repo/pull/1", "open", "Fix");
        when(githubClient.createPullRequest(anyString(), anyString(), anyString(), any())).thenReturn(pr);

        Map<String, Object> result = gitHubPRTool.createGitHubPR(
            TEST_REPO_URL, Map.of("file.txt", "content"), "fix", null, null, null, null
        );

        assertThat(result).containsEntry("success", true);

        ArgumentCaptor<GitHubRestClient.CreatePullRequestRequest> captor =
            ArgumentCaptor.forClass(GitHubRestClient.CreatePullRequestRequest.class);
        verify(githubClient).createPullRequest(anyString(), anyString(), anyString(), captor.capture());

        String prBody = captor.getValue().body();
        assertThat(prBody).contains("Not available");
        assertThat(prBody).contains("unknown");
        assertThat(prBody).contains("Run existing test suite");
    }
}
