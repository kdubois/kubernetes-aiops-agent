package dev.kevindubois.rollout.agent.observability;

import dev.kevindubois.rollout.agent.analysis.AnalysisResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Semantic activity events for request entry points and orchestration.
 * Agent and tool lifecycle events are published by {@link dev.kevindubois.rollout.agent.observability.ActivityEventListener}
 * and {@link dev.kevindubois.rollout.agent.k8s.K8sTools} respectively.
 */
@ApplicationScoped
public class ActivityEvents {

    @Inject
    ActivityEventStore store;

    /**
     * Descriptive model labels for the escalation event. The reference
     * deployment runs the cheap tier on Qwen and the strong tier on DeepSeek.
     */
    @Inject
    @ConfigProperty(name = "analysis.escalation.tier1-label", defaultValue = "Qwen")
    String tier1Label;

    @Inject
    @ConfigProperty(name = "analysis.escalation.tier2-label", defaultValue = "DeepSeek")
    String tier2Label;

    public void requestStarted(String detail) {
        store.publish("ANALYSIS_START", "Analysis request received", detail);
    }

    public void requestFailed(String message) {
        store.publish("ERROR", "Analysis failed", message);
    }

    /**
     * Publishes the confidence that drove the pause gate (the effective one —
     * the grader-combined value when the second opinion is available), with
     * the self-reported value and the grader probability kept visible in the
     * details.
     */
    public void confidenceScored(int selfReported, int effective, String summary, Double graderProbability) {
        String details = (summary != null ? summary : "") + " (self-reported: " + selfReported;
        if (graderProbability != null) {
            details += ", grader: " + String.format("%.2f", graderProbability);
        }
        store.publish("CONFIDENCE_SCORE", "Score: " + effective + "/100", details + ")");
    }

    /**
     * The decision model graded the analysis conclusion.
     */
    public void graderScored(double probability, String verdict, int selfReported) {
        store.publish("GRADER",
                String.format("Grader: %s (%.2f)", verdict, probability),
                String.format("self-reported confidence %d, grader probability %.2f — %s",
                        selfReported, probability, verdict));
    }

    /**
     * The pause gate held the rollout, with the full causal chain.
     * A negative grader probability means no second opinion was available
     * and the gate fired on the self-reported confidence alone.
     */
    public void pauseGateTriggered(int selfReported, double graderProbability, int effective, int threshold) {
        store.publish("PAUSE_GATE",
                "Pause gate held the rollout",
                String.format("self-reported %d, grader %.2f, effective %d < threshold %d",
                        selfReported, graderProbability, effective, threshold));
    }

    /**
     * The grader disputed the default model's conclusion and the strong-model
     * escalation tier re-analyzed it, with both tiers' decisions visible.
     */
    public void analysisEscalated(double disputedProbability, AnalysisResult disputed, AnalysisResult escalated) {
        store.publish("MODEL_ESCALATION", "Analysis escalated to the strong model",
                String.format("tier 1 (%s): %s %d/100, grader %.2f → tier 2 (%s): %s %d/100",
                        tier1Label, disputed.promote() ? "promote" : "rollback", disputed.confidence(),
                        disputedProbability,
                        tier2Label, escalated.promote() ? "promote" : "rollback", escalated.confidence()));
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
