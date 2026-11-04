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

    public void requestStarted(String detail, int measurement, int totalMeasurements) {
        store.publish("ANALYSIS_START",
                String.format("Analysis %d of %d — %s", measurement, totalMeasurements, detail));
    }

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
        String details = (summary != null ? summary : "") + " (analysis: " + selfReported;
        if (graderProbability != null) {
            details += ", quality check: " + (int) Math.round(graderProbability * 100);
        }
        store.publish("CONFIDENCE_SCORE", "Score: " + effective + "/100", details + ")");
    }

    /**
     * The decision model graded the analysis conclusion. The score is the
     * quality-check probability expressed on a 0-100 scale.
     */
    public void qualityChecked(double probability, String verdict, int selfReported) {
        int score = (int) Math.round(probability * 100);
        store.publish("QUALITY_CHECK",
                String.format("Quality check: %s (%d/100)", verdict, score),
                String.format("analysis confidence %d/100, quality score %d/100 — %s",
                        selfReported, score, verdict));
    }

    /**
     * The pause gate held the rollout, with the full causal chain.
     * A negative grader probability means no second opinion was available
     * and the gate fired on the self-reported confidence alone.
     */
    public void pauseGateTriggered(int selfReported, double graderProbability, int effective, int threshold) {
        int qualityScore = graderProbability < 0 ? -1 : (int) Math.round(graderProbability * 100);
        String qualityPart = qualityScore >= 0
                ? String.format(", quality check %d/100", qualityScore) : "";
        store.publish("PAUSE_GATE",
                "Held for human review",
                String.format("analysis confidence %d/100%s, effective %d/100 below threshold %d/100",
                        selfReported, qualityPart, effective, threshold));
    }

    /**
     * The grader disputed the default model's conclusion and the strong-model
     * escalation tier re-analyzed it, with both tiers' decisions visible.
     */
    public void analysisEscalated(double disputedProbability, AnalysisResult disputed, AnalysisResult escalated) {
        store.publish("MODEL_ESCALATION", "Escalated to the strong model for a second opinion",
                String.format("%s said %s (%d/100), quality check disputed at %d/100 — %s re-analyzed: %s (%d/100)",
                        tier1Label, disputed.promote() ? "continue" : "rollback", disputed.confidence(),
                        (int) Math.round(disputedProbability * 100),
                        tier2Label, escalated.promote() ? "continue" : "rollback", escalated.confidence()));
    }

    /**
     * Publishes the analysis conclusion — what the model decided and why —
     * before any quality grading, so the audience sees the reasoning first.
     */
    public void analysisSummary(AnalysisResult result, String modelLabel) {
        if (result.summary() != null && !result.summary().isBlank()) {
            String title = modelLabel != null
                    ? "[" + modelLabel + "] " + result.summary() : result.summary();
            store.publish("ANALYSIS_SUMMARY", title);
        }
        store.publish("ANALYSIS_INSIGHT", result.analysis(),
                "Root cause: " + (result.rootCause() != null ? result.rootCause() : "No issues"));
    }

    /**
     * Publishes the decision after the quality check, with the effective
     * confidence and what happens next.
     */
    public void analysisDecided(AnalysisResult result, int effectiveConfidence, boolean gateTriggered) {
        String decision = result.promote() ? "Continue rollout" : "Rollback canary";
        String detail;
        if (gateTriggered) {
            detail = String.format("effective confidence %d/100 — held for human review", effectiveConfidence);
        } else if (result.promote()) {
            detail = String.format("effective confidence %d/100 — canary proceeds", effectiveConfidence);
        } else {
            detail = String.format("effective confidence %d/100 — canary will be rolled back", effectiveConfidence);
        }
        store.publish("DECISION", decision, detail);
    }

    public void remediationTriggered() {
        store.publish("REMEDIATION", "Remediation triggered", "Analyzing root cause for automated fix");
    }

    public void pauseFailed(String detail) {
        store.publish("PAUSE_FAILED", "Pause requested but no rollout was paused", detail);
    }

    /** A human resumed a rollout the pause gate had held. */
    public void rolloutResumed(String namespace, String rollout) {
        store.publish("HUMAN_ACTION", "Human resumed the rollout", namespace + "/" + rollout);
    }

    /** A human aborted the rollout back to the stable version. */
    public void rolloutAborted(String namespace, String rollout) {
        store.publish("HUMAN_ACTION", "Human aborted to stable", namespace + "/" + rollout);
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
