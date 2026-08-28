package dev.kevindubois.rollout.agent.remediation;

import dev.kevindubois.rollout.agent.utils.GitHubUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import io.quarkus.logging.Log;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads source code files from a Git repository using GitHub API.
 * Used by the remediation pipeline to fetch files for context before patching.
 */
@ApplicationScoped
public class SourceCodeTool {

    @ConfigProperty(name = "github.token")
    String githubToken;

    @Inject
    @RestClient
    GitHubRestClient githubClient;

    /**
     * Read source code files from a GitHub repository and return them with line numbers.
     */
    public Map<String, Object> readSourceFiles(String repoUrl, List<String> filePaths, String branch) {
        if (repoUrl == null || repoUrl.isEmpty()) {
            return Map.of("success", false, "error", "repoUrl is required");
        }
        if (filePaths == null || filePaths.isEmpty()) {
            return Map.of("success", false, "error", "filePaths list is required and cannot be empty");
        }
        if (branch == null || branch.isEmpty()) {
            branch = "main";
        }

        Log.infof("Reading %d files from repository: %s, branch: %s", filePaths.size(), repoUrl, branch);

        try {
            String[] ownerRepo = GitHubUtils.extractOwnerAndRepo(repoUrl);
            String owner = ownerRepo[0];
            String repo = ownerRepo[1];
            String authHeader = GitHubUtils.authHeader(githubToken);

            Map<String, Object> result = new HashMap<>();
            Map<String, String> fileContents = new HashMap<>();
            Map<String, String> fileContentsWithLineNumbers = new HashMap<>();
            List<String> notFound = new java.util.ArrayList<>();

            for (String filePath : filePaths) {
                try {
                    GitHubRestClient.GitHubFileContent fileContent =
                        githubClient.getFileContent(owner, repo, filePath, branch, authHeader);

                    String content = new String(Base64.getDecoder().decode(fileContent.content().replace("\n", "")));
                    fileContents.put(filePath, content);

                    String[] lines = content.split("\n");
                    StringBuilder numberedContent = new StringBuilder();
                    for (int i = 0; i < lines.length; i++) {
                        numberedContent.append(String.format("%4d | %s\n", i + 1, lines[i]));
                    }
                    fileContentsWithLineNumbers.put(filePath, numberedContent.toString());

                } catch (Exception e) {
                    Log.warnf("File not found or error reading: %s - %s", filePath, e.getMessage());
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

            Log.infof("Successfully read %d/%d files", fileContents.size(), filePaths.size());
            return result;

        } catch (Exception e) {
            Log.error("Failed to read source files", e);
            return Map.of("success", false, "error", e.getMessage(), "repoUrl", repoUrl, "branch", branch);
        }
    }
}
