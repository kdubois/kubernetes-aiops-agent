package dev.kevindubois.rollout.agent.model;

public record AnalysisResult(
    boolean promote,
    int confidence,
    String analysis,
    String rootCause,
    String remediation
) {}
