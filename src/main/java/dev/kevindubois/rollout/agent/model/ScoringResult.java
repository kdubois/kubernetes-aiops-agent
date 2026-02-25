package dev.kevindubois.rollout.agent.model;

public record ScoringResult(
    int score,
    boolean needsRetry,
    String reason
) {
    public static ScoringResult accept(int score) {
        return new ScoringResult(score, false, "Quality acceptable");
    }
    
    public static ScoringResult retry(int score, String reason) {
        return new ScoringResult(score, true, reason);
    }
}

// Made with Bob
