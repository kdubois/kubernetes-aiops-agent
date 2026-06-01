package dev.kevindubois.rollout.agent.service;

import dev.kevindubois.rollout.agent.model.RemediationResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Captures successful remediation tool outcomes so the orchestrator can recover
 * when the LLM puts its JSON response in reasoning_content instead of content.
 */
@ApplicationScoped
public class RemediationOutcomeHolder {

    private volatile RemediationResult outcome;

    public void reset() {
        outcome = null;
    }

    public void recordPullRequest(String prUrl, String description) {
        outcome = new RemediationResult(
                prUrl,
                description != null ? description : "Automated fix PR created",
                "Created GitHub pull request");
    }

    public void recordIssue(String issueUrl, String description) {
        outcome = new RemediationResult(
                issueUrl,
                description != null ? description : "GitHub issue created",
                "Created GitHub issue for human review");
    }

    public Optional<RemediationResult> getOutcome() {
        return Optional.ofNullable(outcome);
    }
}
