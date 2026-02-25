package dev.kevindubois.rollout.agent.remediation;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkus.logging.Log;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Tool that creates GitHub issues for problems that need human attention.
 * Used when the agent identifies issues that cannot be automatically fixed.
 */
@ApplicationScoped
public class GitHubIssueTool {
    
    private final String githubToken;
    
    @Inject
    @RestClient
    GitHubRestClient githubClient;
    
    public GitHubIssueTool() {
        this.githubToken = System.getenv("GITHUB_TOKEN");
        if (githubToken == null || githubToken.isEmpty()) {
            Log.warn("GITHUB_TOKEN environment variable not set");
        } else {
            Log.info("GitHub Issue tool initialized");
        }
    }
    
    /**
     * Create a GitHub issue to report a problem
     * 
     * @param repoUrl URL of the GitHub repository
     * @param title Issue title
     * @param description Detailed description of the problem
     * @param rootCause Root cause analysis
     * @param namespace Kubernetes namespace
     * @param podName Kubernetes pod name
     * @param labels Comma-separated list of labels to apply
     * @param assignees Comma-separated list of GitHub usernames to assign
     * @return Result of the issue creation
     */
    @Tool("Create a GitHub issue to report a problem that needs human attention")
    public Map<String, Object> createGitHubIssue(
            String repoUrl,
            String title,
            String description,
            String rootCause,
            String namespace,
            String podName,
            String labels,
            String assignees
    ) {
        Log.info("=== Executing Tool: createGitHubIssue ===");
        
        if (githubToken == null || githubToken.isEmpty()) {
            return Map.of("success", false, "error", "GITHUB_TOKEN environment variable is required");
        }
        
        if (repoUrl == null || title == null || description == null) {
            return Map.of("success", false, "error", "Missing required parameters: repoUrl, title, description");
        }
        
        Log.info(MessageFormat.format("Creating issue for repository: {0}", repoUrl));
        
        try {
            // Extract owner and repo from URL
            String[] ownerRepo = extractOwnerAndRepo(repoUrl);
            String owner = ownerRepo[0];
            String repo = ownerRepo[1];
            String authHeader = "Bearer " + githubToken;
            
            // Build issue body
            String issueBody = generateIssueBody(description, rootCause, namespace, podName);
            
            // Parse labels and assignees
            String[] labelArray = (labels != null && !labels.isEmpty()) 
                ? labels.split(",") 
                : new String[0];
            String[] assigneeArray = (assignees != null && !assignees.isEmpty()) 
                ? assignees.split(",") 
                : new String[0];
            
            // Trim whitespace from labels and assignees
            for (int i = 0; i < labelArray.length; i++) {
                labelArray[i] = labelArray[i].trim();
            }
            for (int i = 0; i < assigneeArray.length; i++) {
                assigneeArray[i] = assigneeArray[i].trim();
            }
            
            // Create issue request
            GitHubRestClient.CreateIssueRequest issueRequest =
                new GitHubRestClient.CreateIssueRequest(title, issueBody, labelArray, assigneeArray);
            
            GitHubRestClient.GitHubIssue issue =
                githubClient.createIssue(owner, repo, authHeader, issueRequest);
            
            Log.info(MessageFormat.format("Successfully created issue: {0}", issue.html_url()));
            
            return Map.of(
                "success", true,
                "issueUrl", issue.html_url(),
                "issueNumber", issue.number()
            );
            
        } catch (Exception e) {
            Log.error("Failed to create issue", e);
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
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
     * Generate issue body with analysis results
     */
    private String generateIssueBody(
            String description,
            String rootCause,
            String namespace,
            String podName
    ) {
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
            ## Problem Description
            %s
            
            ## Root Cause Analysis
            %s
            
            ## Related Kubernetes Resources
            - **Namespace**: `%s`
            - **Pod**: `%s`
            
            ---
            *This issue was automatically created by Kubernetes AI Agent*
            *Please review and take appropriate action*
            """,
            description,
            rootCause,
            namespace,
            podName
        );
    }
}
