package dev.kevindubois.rollout.agent.remediation;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import io.quarkus.logging.Log;

import java.text.MessageFormat;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool for reading source code files from a Git repository using GitHub API.
 * Enables the remediation agent to analyze actual code before creating fixes.
 * Uses GitHub API to fetch files directly without cloning the entire repository.
 */
@ApplicationScoped
public class SourceCodeTool {
    
    private final String githubToken;
    
    @Inject
    @RestClient
    GitHubRestClient githubClient;
    
    public SourceCodeTool() {
        this(System.getenv("GITHUB_TOKEN"));
    }
    
    // Package-private constructor for testing
    SourceCodeTool(String githubToken) {
        this.githubToken = githubToken;
        if (githubToken == null || githubToken.isEmpty()) {
            Log.warn("GITHUB_TOKEN environment variable not set");
        } else {
            Log.info("Source code tool initialized");
        }
    }
    
    /**
     * Read source code files from a Git repository using GitHub API.
     * Fetches only the requested files without cloning the entire repository.
     *
     * IMPORTANT: File paths must match the actual repository structure.
     * Common Java project structure:
     * - src/main/java/[package]/ClassName.java
     * - src/main/resources/application.properties
     * - pom.xml (Maven) or build.gradle (Gradle)
     *
     * Example: For package "dev.kevindubois.demo" and class "LoadGeneratorService":
     * Path should be: "src/main/java/dev/kevindubois/demo/LoadGeneratorService.java"
     *
     * @param repoUrl URL of the GitHub repository (e.g., "https://github.com/owner/repo")
     * @param filePaths List of file paths to read (e.g., ["src/main/java/dev/kevindubois/demo/LoadGeneratorService.java"])
     * @param branch Branch name to read from (e.g., "main", "develop")
     * @return Map containing file contents and metadata
     */
    @Tool("Read source code files from a Git repository using GitHub API. Returns file content with line numbers to help identify correct insertion points. CRITICAL: Use correct file paths matching repository structure (e.g., src/main/java/[package]/ClassName.java for Java files).")
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
        
        try {
            String[] ownerRepo = extractOwnerAndRepo(repoUrl);
            String owner = ownerRepo[0];
            String repo = ownerRepo[1];
            String authHeader = formatAuthHeader(githubToken);
            
            // Read requested files via GitHub API
            Map<String, Object> result = new HashMap<>();
            Map<String, String> fileContents = new HashMap<>();
            Map<String, String> fileContentsWithLineNumbers = new HashMap<>();
            List<String> notFound = new java.util.ArrayList<>();
            
            for (String filePath : filePaths) {
                try {
                    GitHubRestClient.GitHubFileContent fileContent =
                        githubClient.getFileContent(owner, repo, filePath, branch, authHeader);
                    
                    // Decode base64 content
                    String content = new String(Base64.getDecoder().decode(fileContent.content().replace("\n", "")));
                    fileContents.put(filePath, content);
                    
                    // Add line numbers to help agent understand structure
                    String[] lines = content.split("\n");
                    StringBuilder numberedContent = new StringBuilder();
                    for (int i = 0; i < lines.length; i++) {
                        numberedContent.append(String.format("%4d | %s\n", i + 1, lines[i]));
                    }
                    fileContentsWithLineNumbers.put(filePath, numberedContent.toString());
                    
                    Log.debug(MessageFormat.format("Read file: {0} ({1} lines, {2} chars)",
                            filePath, lines.length, content.length()));
                    
                } catch (Exception e) {
                    Log.warn(MessageFormat.format("File not found or error reading: {0} - {1}",
                            filePath, e.getMessage()));
                    notFound.add(filePath);
                }
            }
            
            result.put("success", true);
            result.put("repoUrl", repoUrl);
            result.put("branch", branch);
            result.put("filesRead", fileContents.size());
            result.put("files", fileContents);
            result.put("filesWithLineNumbers", fileContentsWithLineNumbers);
            
            if (!notFound.isEmpty()) {
                result.put("notFound", notFound);
                result.put("hint", "File paths must match repository structure. For Java: src/main/java/[package]/ClassName.java");
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
        }
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
}