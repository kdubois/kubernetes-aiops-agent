package dev.kevindubois.rollout.agent.remediation;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.logging.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool for reading source code files from a Git repository.
 * Enables the remediation agent to analyze actual code before creating fixes.
 */
@ApplicationScoped
public class SourceCodeTool {
    
    private final GitOperations gitOps;
    private final String githubToken;
    
    public SourceCodeTool() {
        this(new GitOperations(), System.getenv("GITHUB_TOKEN"));
    }
    
    // Package-private constructor for testing
    SourceCodeTool(GitOperations gitOps, String githubToken) {
        this.gitOps = gitOps;
        this.githubToken = githubToken;
        if (githubToken == null || githubToken.isEmpty()) {
            Log.warn("GITHUB_TOKEN environment variable not set");
        } else {
            Log.info("Source code tool initialized");
        }
    }
    
    /**
     * Read source code files from a Git repository.
     * Supports reading multiple files in a single call for efficiency.
     * 
     * @param repoUrl URL of the GitHub repository (e.g., "https://github.com/owner/repo")
     * @param filePaths List of file paths to read (e.g., ["src/main/resources/application.properties", "pom.xml"])
     * @param branch Branch name to read from (e.g., "main", "develop")
     * @return Map containing file contents and metadata
     */
    @Tool("Read source code files from a Git repository. Can read multiple files in one call for efficiency.")
    public Map<String, Object> readSourceFiles(
            String repoUrl,
            List<String> filePaths,
            String branch
    ) {
        Log.info("=== Executing Tool: readSourceFiles ===");
        
        // Validate required parameters
        if (repoUrl == null || repoUrl.isEmpty()) {
            return Map.of("success", false, "error", "repoUrl is required");
        }
        
        if (filePaths == null || filePaths.isEmpty()) {
            return Map.of("success", false, "error", "filePaths list is required and cannot be empty");
        }
        
        if (branch == null || branch.isEmpty()) {
            branch = "main"; // Default to main branch
        }
        
        Log.info(MessageFormat.format("Reading {0} files from repository: {1}, branch: {2}", 
                filePaths.size(), repoUrl, branch));
        
        Path repoPath = null;
        
        try {
            // Clone repository (shallow clone for efficiency)
            repoPath = gitOps.cloneRepository(repoUrl, githubToken);
            
            // Checkout specific branch if not main/master
            if (!branch.equals("main") && !branch.equals("master")) {
                gitOps.createBranch(repoPath, branch);
            }
            
            // Read requested files
            Map<String, Object> result = new HashMap<>();
            Map<String, String> fileContents = new HashMap<>();
            List<String> notFound = new java.util.ArrayList<>();
            
            for (String filePath : filePaths) {
                Path fullPath = repoPath.resolve(filePath);
                
                if (Files.exists(fullPath) && Files.isRegularFile(fullPath)) {
                    try {
                        String content = Files.readString(fullPath);
                        fileContents.put(filePath, content);
                        Log.debug(MessageFormat.format("Read file: {0} ({1} chars)", 
                                filePath, content.length()));
                    } catch (Exception e) {
                        Log.warn(MessageFormat.format("Failed to read file {0}: {1}", 
                                filePath, e.getMessage()));
                        notFound.add(filePath + " (read error: " + e.getMessage() + ")");
                    }
                } else {
                    Log.warn(MessageFormat.format("File not found: {0}", filePath));
                    notFound.add(filePath);
                }
            }
            
            result.put("success", true);
            result.put("repoUrl", repoUrl);
            result.put("branch", branch);
            result.put("filesRead", fileContents.size());
            result.put("files", fileContents);
            
            if (!notFound.isEmpty()) {
                result.put("notFound", notFound);
            }
            
            Log.info(MessageFormat.format("Successfully read {0}/{1} files", 
                    fileContents.size(), filePaths.size()));
            
            return result;
            
        } catch (Exception e) {
            Log.error("Failed to read source files", e);
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "repoUrl", repoUrl,
                "branch", branch
            );
        } finally {
            // Cleanup temporary directory
            if (repoPath != null) {
                gitOps.cleanup(repoPath);
            }
        }
    }
}