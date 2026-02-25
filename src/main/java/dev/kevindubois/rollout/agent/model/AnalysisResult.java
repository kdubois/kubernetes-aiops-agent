package dev.kevindubois.rollout.agent.model;

public record AnalysisResult(
    boolean promote,
    int confidence,
    String analysis,
    String rootCause,
    String remediation,
    String prLink
) {
    public static AnalysisResult empty() {
        return new AnalysisResult(false, 0, "", "", "", null);
    }
}

// Made with Bob
