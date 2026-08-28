package dev.kevindubois.rollout.agent.remediation;

import dev.kevindubois.rollout.agent.model.SourceReadResult;
import dev.kevindubois.rollout.agent.utils.GitHubUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import io.quarkus.logging.Log;

import java.util.*;

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

    public SourceReadResult readSourceFiles(String repoUrl, List<String> filePaths, String branch) {
        if (repoUrl == null || repoUrl.isEmpty()) {
            return SourceReadResult.error("repoUrl is required", repoUrl, branch);
        }
        if (filePaths == null || filePaths.isEmpty()) {
            return SourceReadResult.error("filePaths list is required and cannot be empty", repoUrl, branch);
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

            Map<String, String> fileContents = new HashMap<>();
            Map<String, String> fileContentsWithLineNumbers = new HashMap<>();
            List<String> notFound = new ArrayList<>();

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

            Log.infof("Successfully read %d/%d files", fileContents.size(), filePaths.size());

            return new SourceReadResult(
                    true, repoUrl, branch, fileContents.size(),
                    fileContents, fileContentsWithLineNumbers, notFound, null);

        } catch (Exception e) {
            Log.error("Failed to read source files", e);
            return SourceReadResult.error(e.getMessage(), repoUrl, branch);
        }
    }
}
