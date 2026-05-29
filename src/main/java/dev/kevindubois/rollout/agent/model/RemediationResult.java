package dev.kevindubois.rollout.agent.model;

public record RemediationResult(
    String prLink,
    String analysis,
    String remediation
) {}