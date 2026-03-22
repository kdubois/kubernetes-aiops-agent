package dev.kevindubois.rollout.agent.remediation;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import io.quarkus.logging.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tool that creates GitHub PRs using line-based patches instead of full file content.
 * This avoids LLM token limits when dealing with large files.
 */
@ApplicationScoped
public class GitHubPatchPRTool {
    
    private final GitOperations gitOps;
    private final String githubToken;
    
    @Inject
    @RestClient
    GitHubRestClient githubClient;
    
    public GitHubPatchPRTool() {
        this(new GitOperations(), System.getenv("GITHUB_TOKEN"));
    }
    
    // Package-private constructor for testing
    GitHubPatchPRTool(GitOperations gitOps, String githubToken) {
        this.gitOps = gitOps;
        this.githubToken = githubToken;
        if (githubToken == null || githubToken.isEmpty()) {
            Log.warn("GITHUB_TOKEN environment variable not set");
        } else {
            Log.info("GitHub Patch PR tool initialized");
        }
    }
    
    /**
     * Represents a single line-based change to a file
     */
    public static class LineChange {
        public int lineNumber;      // 1-based line number
        public String action;        // "replace", "insert_after", "insert_before", "delete"
        public String content;       // New content (for replace/insert actions)
        public String expectedLine;  // Optional: expected content at lineNumber for validation
        
        public LineChange() {}
        
        public LineChange(int lineNumber, String action, String content) {
            this.lineNumber = lineNumber;
            this.action = action;
            this.content = content;
        }
        
        public LineChange(int lineNumber, String action, String content, String expectedLine) {
            this.lineNumber = lineNumber;
            this.action = action;
            this.content = content;
            this.expectedLine = expectedLine;
        }
    }
    
    /**
     * Represents changes to a single file
     */
    public static class FilePatch {
        public String filePath;
        public List<LineChange> changes;
        
        public FilePatch() {}
        
        public FilePatch(String filePath, List<LineChange> changes) {
            this.filePath = filePath;
            this.changes = changes;
        }
    }
    
    /**
     * Create a GitHub pull request using line-based patches.
     * This is more efficient than providing full file content and avoids LLM token limits.
     * 
     * @param repoUrl URL of the GitHub repository
     * @param patches List of file patches with line-based changes
     * @param fixDescription Description of the fix
     * @param rootCause Root cause of the issue
     * @param namespace Kubernetes namespace
     * @param podName Kubernetes pod name
     * @param testingRecommendations Testing recommendations
     * @return Result of the PR creation
     */
    @Tool("Create a GitHub pull request using line-based patches. Specify exact line numbers and changes to make. More efficient than providing full file content.")
    public Map<String, Object> createGitHubPRWithPatches(
            String repoUrl,
            List<Map<String, Object>> patches,
            String fixDescription,
            String rootCause,
            String namespace,
            String podName,
            String testingRecommendations
    ) {
        Log.info("=== Executing Tool: createGitHubPRWithPatches ===");
        
        // Validate required parameters
        if (repoUrl == null || patches == null || patches.isEmpty() || fixDescription == null) {
            return Map.of("success", false, "error", "Missing required parameters: repoUrl, patches, fixDescription");
        }
        
        Log.info(MessageFormat.format("Creating PR with patches for repository: {0}", repoUrl));
        
        // Convert raw maps to FilePatch objects
        List<FilePatch> filePatchList;
        try {
            filePatchList = convertToFilePatches(patches);
        } catch (Exception e) {
            Log.error("Failed to parse patches", e);
            return Map.of("success", false, "error", "Invalid patch format: " + e.getMessage());
        }
        
        String branchName = "fix/k8s-issue-" + UUID.randomUUID().toString().substring(0, 8);
        Path repoPath = null;
        
        try {
            // 1. Clone repository
            repoPath = gitOps.cloneRepository(repoUrl, githubToken);
            
            // 2. Create branch
            gitOps.createBranch(repoPath, branchName);
            
            // 3. Apply patches to each file
            for (FilePatch patch : filePatchList) {
                applyPatchToFile(repoPath, patch);
            }
            
            // 4. Commit and push
            String commitMsg = formatCommitMessage(fixDescription);
            gitOps.commitAndPush(repoPath, commitMsg, githubToken);
            
            // 5. Create PR via GitHub REST API
            GitHubRestClient.GitHubPullRequest pr = createPullRequestOnGitHub(
                repoUrl, branchName, fixDescription, rootCause,
                testingRecommendations, namespace, podName, filePatchList
            );
            
            Log.info(MessageFormat.format("Successfully created PR: {0}", pr.html_url()));
            
            return Map.of(
                "success", true,
                "prUrl", pr.html_url(),
                "prNumber", pr.number(),
                "branch", branchName
            );
            
        } catch (Exception e) {
            Log.error("Failed to create PR with patches", e);
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
     * Convert raw Map objects from LangChain4j to FilePatch objects
     */
    @SuppressWarnings("unchecked")
    private List<FilePatch> convertToFilePatches(List<Map<String, Object>> rawPatches) {
        return rawPatches.stream()
            .map(patchMap -> {
                String filePath = (String) patchMap.get("filePath");
                List<Map<String, Object>> rawChanges = (List<Map<String, Object>>) patchMap.get("changes");
                
                List<LineChange> changes = rawChanges.stream()
                    .map(changeMap -> {
                        int lineNumber = ((Number) changeMap.get("lineNumber")).intValue();
                        String action = (String) changeMap.get("action");
                        String content = (String) changeMap.get("content");
                        return new LineChange(lineNumber, action, content);
                    })
                    .collect(Collectors.toList());
                
                return new FilePatch(filePath, changes);
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Check if all changes are insert_after operations on consecutive line numbers
     */
    private boolean isConsecutiveInsertAfter(List<LineChange> changes) {
        if (changes.isEmpty()) {
            return false;
        }
        
        // Check if all are insert_after
        boolean allInsertAfter = changes.stream()
            .allMatch(c -> "insert_after".equalsIgnoreCase(c.action));
        
        if (!allInsertAfter) {
            return false;
        }
        
        // Check if line numbers are consecutive
        List<Integer> lineNumbers = changes.stream()
            .map(c -> c.lineNumber)
            .sorted()
            .toList();
        
        for (int i = 1; i < lineNumbers.size(); i++) {
            if (lineNumbers.get(i) != lineNumbers.get(i - 1) + 1) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Apply a patch to a single file
     */
    private void applyPatchToFile(Path repoPath, FilePatch patch) throws Exception {
        Path filePath = repoPath.resolve(patch.filePath);
        
        if (!Files.exists(filePath)) {
            throw new Exception("File not found: " + patch.filePath);
        }
        
        // Read current file content
        List<String> lines = Files.readAllLines(filePath);
        
        // Group changes by line number and action type
        // For insert_after on consecutive lines, we need to process them in ascending order
        // For other operations, descending order avoids offset issues
        List<LineChange> sortedChanges = new ArrayList<>(patch.changes);
        
        // Check if all changes are insert_after on consecutive lines
        boolean allConsecutiveInserts = isConsecutiveInsertAfter(sortedChanges);
        
        if (allConsecutiveInserts) {
            // Sort in ascending order for consecutive inserts
            sortedChanges.sort((a, b) -> Integer.compare(a.lineNumber, b.lineNumber));
            Log.debug("Processing consecutive insert_after operations in ascending order");
        } else {
            // Sort in descending order to avoid offset issues for mixed operations
            sortedChanges.sort((a, b) -> Integer.compare(b.lineNumber, a.lineNumber));
            Log.debug("Processing changes in descending order");
        }
        
        // Apply each change with validation
        for (LineChange change : sortedChanges) {
            int lineIndex = change.lineNumber - 1; // Convert to 0-based index
            
            // Validate expectedLine if provided
            if (change.expectedLine != null && !change.expectedLine.isEmpty()) {
                if (lineIndex >= 0 && lineIndex < lines.size()) {
                    String actualLine = lines.get(lineIndex).trim();
                    String expectedLine = change.expectedLine.trim();
                    if (!actualLine.equals(expectedLine)) {
                        Log.warn(MessageFormat.format(
                            "Line {0} validation failed in {1}. Expected: ''{2}'', Actual: ''{3}''. Skipping this change.",
                            change.lineNumber, patch.filePath, expectedLine, actualLine));
                        continue; // Skip this change
                    }
                }
            }
            
            switch (change.action.toLowerCase()) {
                case "replace":
                    if (lineIndex >= 0 && lineIndex < lines.size()) {
                        String oldLine = lines.get(lineIndex);
                        lines.set(lineIndex, change.content);
                        Log.debug(MessageFormat.format("Replaced line {0} in {1}: ''{2}'' → ''{3}''",
                                change.lineNumber, patch.filePath, oldLine.trim(), change.content.trim()));
                    }
                    break;
                    
                case "insert_after":
                    if (lineIndex >= 0 && lineIndex < lines.size()) {
                        lines.add(lineIndex + 1, change.content);
                        Log.debug(MessageFormat.format("Inserted line after {0} in {1}: ''{2}''",
                                change.lineNumber, patch.filePath, change.content.trim()));
                    }
                    break;
                    
                case "insert_before":
                    if (lineIndex >= 0 && lineIndex <= lines.size()) {
                        lines.add(lineIndex, change.content);
                        Log.debug(MessageFormat.format("Inserted line before {0} in {1}: ''{2}''",
                                change.lineNumber, patch.filePath, change.content.trim()));
                    }
                    break;
                    
                case "delete":
                    if (lineIndex >= 0 && lineIndex < lines.size()) {
                        String deletedLine = lines.remove(lineIndex);
                        Log.debug(MessageFormat.format("Deleted line {0} in {1}: ''{2}''",
                                change.lineNumber, patch.filePath, deletedLine.trim()));
                    }
                    break;
                    
                default:
                    Log.warn(MessageFormat.format("Unknown action: {0}", change.action));
            }
        }
        
        // Write modified content back to file
        Files.write(filePath, lines);
        Log.info(MessageFormat.format("Applied {0} changes to {1}", 
                patch.changes.size(), patch.filePath));
    }
    
    /**
     * Create a pull request on GitHub via REST API
     */
    private GitHubRestClient.GitHubPullRequest createPullRequestOnGitHub(
            String repoUrl,
            String branchName,
            String fixDescription,
            String rootCause,
            String testingRecommendations,
            String namespace,
            String podName,
            List<FilePatch> patches
    ) throws Exception {
        String[] ownerRepo = extractOwnerAndRepo(repoUrl);
        String owner = ownerRepo[0];
        String repo = ownerRepo[1];
        String authHeader = formatAuthHeader(githubToken);
        
        // Get repository to find default branch
        GitHubRestClient.GitHubRepository repository =
            githubClient.getRepository(owner, repo, authHeader);
        
        String baseBranch = repository.default_branch();
        String prTitle = MessageFormat.format("Fix: {0}", fixDescription);
        String prBody = generatePRBody(rootCause, fixDescription, testingRecommendations, 
                namespace, podName, patches);
        
        // Create pull request
        GitHubRestClient.CreatePullRequestRequest prRequest =
            new GitHubRestClient.CreatePullRequestRequest(prTitle, branchName, baseBranch, prBody);
        
        return githubClient.createPullRequest(owner, repo, authHeader, prRequest);
    }
    
    /**
     * Format commit message with conventional commit prefix
     */
    private String formatCommitMessage(String fixDescription) {
        return MessageFormat.format("fix: {0}", fixDescription);
    }
    
    /**
     * Format authorization header for GitHub API
     */
    private String formatAuthHeader(String token) {
        return "Bearer " + token;
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
     * Generate PR body with analysis results and patch summary
     */
    private String generatePRBody(
            String rootCause,
            String fixDescription,
            String testingRecommendations,
            String namespace,
            String podName,
            List<FilePatch> patches
    ) {
        StringBuilder patchSummary = new StringBuilder();
        for (FilePatch patch : patches) {
            patchSummary.append("- `").append(patch.filePath).append("`: ")
                    .append(patch.changes.size()).append(" change(s)\n");
            for (LineChange change : patch.changes) {
                patchSummary.append("  - Line ").append(change.lineNumber)
                        .append(": ").append(change.action).append("\n");
            }
        }
        
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
            %s
            
            %s
            
            ## Testing Recommendations
            %s
            
            ## Related Kubernetes Resources
            - **Namespace**: `%s`
            - **Pod**: `%s`
            
            ---
            *This PR was automatically generated by Kubernetes AI Agent using line-based patches*
            *Review carefully before merging*
            """,
            rootCause,
            patchSummary.toString(),
            fixDescription,
            testingRecommendations,
            namespace,
            podName
        );
    }
}