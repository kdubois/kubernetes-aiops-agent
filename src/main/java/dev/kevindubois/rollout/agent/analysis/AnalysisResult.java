package dev.kevindubois.rollout.agent.analysis;

import java.util.List;

public record AnalysisResult(
    boolean promote,
    int confidence,
    String analysis,
    String rootCause,
    String remediation,
    String summary,
    IssueCategory issueCategory,
    List<String> suspectClasses
) {
}
