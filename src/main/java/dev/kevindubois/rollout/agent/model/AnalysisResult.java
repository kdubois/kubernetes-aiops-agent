package dev.kevindubois.rollout.agent.model;

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
    public boolean isOperational() {
        return issueCategory == IssueCategory.OPERATIONAL;
    }

    public boolean isCodeBug() {
        return issueCategory == IssueCategory.CODE_BUG;
    }
}
