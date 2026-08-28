package dev.kevindubois.rollout.agent.model;

import java.util.List;
import java.util.Map;

/**
 * Typed result from reading source files from a GitHub repository.
 */
public record SourceReadResult(
    boolean success,
    String repoUrl,
    String branch,
    int filesRead,
    Map<String, String> files,
    Map<String, String> filesWithLineNumbers,
    List<String> notFound,
    String error
) {
    public static SourceReadResult error(String error, String repoUrl, String branch) {
        return new SourceReadResult(false, repoUrl, branch, 0, Map.of(), Map.of(), List.of(), error);
    }
}
