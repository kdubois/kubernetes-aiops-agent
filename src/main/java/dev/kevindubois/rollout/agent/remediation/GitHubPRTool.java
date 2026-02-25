package dev.kevindubois.rollout.agent.remediation;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkus.logging.Log;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Map;

/**
 * Tool that creates GitHub PRs with fixes.
 * Git operations are deterministic, only the fix content comes from AI.
 */
@ApplicationScoped
public class GitHubPRTool {
    
    private final GitOperations gitOps;
    private final String githubToken;
    
    @Inject
    @RestClient
    GitHubRestClient githubClient;
    
    public GitHubPRTool() {
        this(new GitOperations(), System.getenv("GITHUB_TOKEN"));
    }
    
    // Package-private constructor for testing
    GitHubPRTool(GitOperations gitOps, String githubToken) {
        this.gitOps = gitOps;
        this.githubToken = githubToken;
        if (githubToken == null || githubToken.isEmpty()) {
            Log.warn("GITHUB_TOKEN environment variable not set");
        } else {
            Log.info("GitHub PR tool initialized");
        }
    }
    
    /**
     * Create a GitHub pull request with code fixes
     * 
     * @param repoUrl URL of the GitHub repository
     * @param fileChanges Map of file paths to their new content
     * @param fixDescription Description of the fix
     * @param rootCause Root cause of the issue
     * @param namespace Kubernetes namespace
     * @param podName Kubernetes pod name
     * @param testingRecommendations Testing recommendations
     * @return Result of the PR creation
     */
    @Tool("Create a GitHub pull request with code fixes")
    public Map<String, Object> createGitHubPR(
            String repoUrl,
            Map<String, String> fileChanges,
            String fixDescription,
            String rootCause,
            String namespace,
            String podName,
            String testingRecommendations
    ) {
        Log.info("=== Executing Tool: createGitHubPR ===");
        
        if (githubToken == null || githubToken.isEmpty()) {
            return Map.of("success", false, "error", "GITHUB_TOKEN environment variable is required");
        }
        
        if (repoUrl == null || fileChanges == null || fixDescription == null) {
            return Map.of("success", false, "error", "Missing required parameters: repoUrl, fileChanges, fixDescription");
        }
        Log.info(MessageFormat.format("Creating PR for repository: {0}", repoUrl));
        
        
        // Deterministic git workflow (HOW to fix):
        String branchName = MessageFormat.format("fix/k8s-issue-{0}", System.currentTimeMillis());
        String token = System.getenv("GITHUB_TOKEN");
        Path repoPath = null;
        
        try {
            // 1. Clone (library)
            repoPath = gitOps.cloneRepository(repoUrl, token);
            
            // 2. Create branch (library)
            gitOps.createBranch(repoPath, branchName);
            
            // 3. Apply AI-suggested changes (library file I/O)
            gitOps.applyChanges(repoPath, fileChanges);
            
            // 4. Commit and push (library)
            String commitMsg = MessageFormat.format("fix: {0}", fixDescription);
            gitOps.commitAndPush(repoPath, commitMsg, token);
            
            // 5. Create PR via GitHub REST API
            String[] ownerRepo = extractOwnerAndRepo(repoUrl);
            String owner = ownerRepo[0];
            String repo = ownerRepo[1];
            String authHeader = "Bearer " + githubToken;
            
            // Get repository to find default branch
            GitHubRestClient.GitHubRepository repository =
                githubClient.getRepository(owner, repo, authHeader);
            
            String baseBranch = repository.default_branch();
            String prTitle = MessageFormat.format("Fix: {0}", fixDescription);
            String prBody = generatePRBody(rootCause, fixDescription, testingRecommendations, namespace, podName, fileChanges);
            
            // Create pull request
            GitHubRestClient.CreatePullRequestRequest prRequest =
                new GitHubRestClient.CreatePullRequestRequest(prTitle, branchName, baseBranch, prBody);
            
            GitHubRestClient.GitHubPullRequest pr =
                githubClient.createPullRequest(owner, repo, authHeader, prRequest);
            
            Log.info(MessageFormat.format("Successfully created PR: {0}", pr.html_url()));
            
            return Map.of(
                "success", true,
                "prUrl", pr.html_url(),
                "prNumber", pr.number(),
                "branch", branchName
            );
            
        } catch (Exception e) {
            Log.error("Failed to create PR", e);
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        } finally {
            // Cleanup temporary directory
            if (repoPath != null) {
                gitOps.cleanup(repoPath);
            }
        }
    }
    
    /**
     * Extract owner and repository name from URL
     * @return Array with [owner, repo]
     */
    private String[] extractOwnerAndRepo(String repoUrl) {
        // Handle formats: https://github.com/owner/repo or https://github.com/owner/repo.git
        String cleaned = repoUrl.replace("https://github.com/", "")
            .replace(".git", "");
        return cleaned.split("/", 2);
    }
    
    /**
     * Generate PR body with analysis results
     */
    private String generatePRBody(
            String rootCause,
            String fixDescription,
            String testingRecommendations,
            String namespace,
            String podName,
            Map<String, String> fileChanges
    ) {
        String changesSummary = fileChanges != null ? 
            String.join(", ", fileChanges.keySet()) : "No files changed";
        
        if (testingRecommendations == null || testingRecommendations.isEmpty()) {
            testingRecommendations = "Run existing test suite";
        }
        
        if (rootCause == null || rootCause.isEmpty()) {
            rootCause = "Not available";
        }
        
        if (namespace == null) {
            namespace = "unknown";
        }
        
        if (podName == null) {
            podName = "unknown";
        }
        
        return String.format("""
            ## Root Cause Analysis
            %s
            
            ## Changes Made
            Modified files: %s
            
            %s
            
            ## Testing Recommendations
            %s
            
            ## Related Kubernetes Resources
            - **Namespace**: `%s`
            - **Pod**: `%s`
            
            ---
            *This PR was automatically generated by Kubernetes AI Agent*
            *Review carefully before merging*
            """,
            rootCause,
            changesSummary,
            fixDescription,
            testingRecommendations,
            namespace,
            podName
        );
    }
}


