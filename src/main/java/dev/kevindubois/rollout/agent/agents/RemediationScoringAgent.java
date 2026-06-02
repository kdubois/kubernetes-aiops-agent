package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.RemediationResult;
import dev.kevindubois.rollout.agent.model.ScoringResult;
import dev.langchain4j.agentic.Agent;
import io.quarkus.logging.Log;

/**
 * Non-AI agent that deterministically validates a {@link RemediationResult}.
 * Checks whether the PR link is a valid GitHub URL and that required fields are present.
 * No LLM call needed — these are simple string checks.
 */
public class RemediationScoringAgent {

    @Agent(outputKey = "scoringResult", description = "Evaluates remediation quality")
    public static ScoringResult evaluate(RemediationResult remediationResult) {
        Log.info("RemediationScoringAgent: evaluating result (non-AI agent)");

        if (remediationResult == null) {
            return ScoringResult.retry(0, "RemediationResult is null");
        }

        String prLink = remediationResult.prLink();
        String analysis = remediationResult.analysis();
        String remediation = remediationResult.remediation();

        if (prLink == null || prLink.isBlank()) {
            return ScoringResult.retry(10, "prLink is missing");
        }

        if (!prLink.startsWith("https://github.com/")) {
            return ScoringResult.retry(20, "prLink is not a valid GitHub URL: " + prLink);
        }

        if (analysis == null || analysis.isBlank()) {
            return ScoringResult.retry(30, "analysis field is empty");
        }

        if (remediation == null || remediation.isBlank()) {
            return ScoringResult.retry(30, "remediation field is empty");
        }

        Log.infof("RemediationScoringAgent: PASS (prLink=%s)", prLink);
        return ScoringResult.accept(90);
    }
}
