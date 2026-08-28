package dev.kevindubois.rollout.agent.utils;

/**
 * Shared GitHub URL parsing and auth-header formatting.
 */
public final class GitHubUtils {

    private GitHubUtils() {}

    /**
     * Extract owner and repository name from a GitHub URL.
     * Handles https://github.com/owner/repo, https://github.com/owner/repo.git,
     * and git@github.com:owner/repo.git formats.
     *
     * @return array of [owner, repo]
     * @throws IllegalArgumentException if the URL cannot be parsed
     */
    public static String[] extractOwnerAndRepo(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("repoUrl must not be blank");
        }
        String cleaned = repoUrl
                .replace("git@github.com:", "")
                .replace("https://github.com/", "")
                .replace(".git", "");
        String[] parts = cleaned.split("/", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Cannot parse owner/repo from: " + repoUrl);
        }
        return parts;
    }

    /**
     * Format a Bearer authorization header for GitHub API calls.
     */
    public static String authHeader(String token) {
        return "Bearer " + token;
    }
}
