package dev.kevindubois.rollout.agent.remediation;

public record RemediationResult(
    String prLink,
    String remediation
) {}