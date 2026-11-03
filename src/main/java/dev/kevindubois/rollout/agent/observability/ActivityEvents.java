package dev.kevindubois.rollout.agent.observability;

import dev.kevindubois.rollout.agent.analysis.AnalysisResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Semantic activity events for request entry points and orchestration.
 * Agent and tool lifecycle events are published by {@link dev.kevindubois.rollout.agent.observability.ActivityEventListener}
 * and {@link dev.kevindubois.rollout.agent.k8s.K8sTools} respectively.
 */
@ApplicationScoped
public class ActivityEvents {

    @Inject
    ActivityEventStore store;

    public void requestStarted(String detail) {
        store.publish("ANALYSIS_START", "Analysis request received", detail);
    }

    public void requestFailed(String message) {
        store.publish("ERROR", "Analysis failed", message);
    }

    /**
     * Publishes the confidence that drove the pause gate (the effective one —
     * the grader-combined value when the second opinion is available), with
     * the self-reported value kept visible in the details.
     */
    public void confidenceScored(int selfReported, int effective, String summary) {
        store.publish("CONFIDENCE_SCORE", "Score: " + effective + "/100",
                (summary != null ? summary : "") + " (self-reported: " + selfReported + ")");
    }

    /**
     * The grader disputed the default model's conclusion and the strong-model
     * escalation tier re-analyzed it.
     */
    public void analysisEscalated(double disputedProbability, AnalysisResult disputed, AnalysisResult escalated) {
        store.publish("MODEL_ESCALATION", "Analysis escalated to the strong model",
                String.format("grader disputed at %.2f (%s, confidence %d); re-analyzed: %s, confidence %d",
                        disputedProbability,
                        disputed.promote() ? "promote" : "rollback", disputed.confidence(),
                        escalated.promote() ? "promote" : "rollback", escalated.confidence()));
    }

    public void analysisCompleted(AnalysisResult result, int effectiveConfidence) {
        if (result.summary() != null && !result.summary().isBlank()) {
            store.publish("ANALYSIS_SUMMARY", "Analysis summary", result.summary());
        }
        store.publish("ANALYSIS_INSIGHT", result.analysis(),
                "Root cause: " + (result.rootCause() != null ? result.rootCause() : "No issues"));
        store.publish("DECISION",
                result.promote() ? "PROMOTE recommended" : "ROLLBACK recommended",
                "confidence: " + effectiveConfidence + "%");
    }

    public void remediationTriggered() {
        store.publish("REMEDIATION", "Remediation triggered", "Analyzing root cause for automated fix");
    }

    public void pauseFailed(String detail) {
        store.publish("PAUSE_FAILED", "Pause requested but no rollout was paused", detail);
    }

    public void remediationFailed(String reason) {
        store.publish("REMEDIATION", "Failed", reason);
    }

    public void remediationCompleted(String artifactUrl) {
        if (artifactUrl != null && !artifactUrl.isEmpty()) {
            store.publish("REMEDIATION", "GitHub artifact created", artifactUrl);
        } else {
            store.publish("REMEDIATION", "Remediation completed", "No GitHub artifact created");
        }
    }
}
