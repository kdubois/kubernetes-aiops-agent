package dev.kevindubois.rollout.agent.observability;

import dev.kevindubois.rollout.agent.analysis.AnalysisResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
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

    /** The model name configured for the analysis tier (e.g. rits/zai-org/glm-5-3). */
    @Inject
    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name", defaultValue = "analysis model")
    String analysisModelName;

    /** The model name configured for the second-opinion tier. */
    @Inject
    @ConfigProperty(name = "analysis.escalation.model")
    Optional<String> secondOpinionModelName;

    private String shortModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return "model";
        }
        String name = modelName.contains("/") ? modelName.substring(modelName.lastIndexOf('/') + 1) : modelName;
        return name.isEmpty() ? modelName : name;
    }

    /** Short model name of the analysis tier, for UI badges and event labels. */
    public String analysisModelLabel() {
        return shortModelName(analysisModelName);
    }

    /** Short model name of the second-opinion tier, for UI badges and event labels. */
    public String secondOpinionModelLabel() {
        return shortModelName(secondOpinionModelName.orElse("second-opinion model"));
    }

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
     * Publishes the analysis model's self-reported confidence in its own
     * conclusion, right after the insight — the claim the quality check
     * then grades.
     */
    public void analysisConfidence(int selfReported) {
        store.publish("CONFIDENCE_SCORE", "Analysis confidence: " + selfReported + "/100",
                "self-reported by the analysis model");
    }

    /**
     * The decision model graded the analysis conclusion against the diagnostic
     * data. The score is the probability that the conclusion is supported by
     * the evidence, on a 0-100 scale.
     */
    public void qualityChecked(double probability, String verdict, int selfReported) {
        int score = (int) Math.round(probability * 100);
        String finding = switch (verdict) {
            case "supported" -> "the conclusion is supported by the evidence";
            case "neutral" -> "the evidence neither supports nor disputes the conclusion";
            default -> "the conclusion is not supported by the evidence";
        };
        store.publish("QUALITY_CHECK",
                String.format("Quality check: %d/100 — %s", score, finding),
                String.format("decision model (Jev) graded the analysis conclusion against the diagnostic data; analysis self-confidence %d/100",
                        selfReported));
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
     * The quality check disputed the analysis conclusion and the
     * second-opinion tier re-analyzed it, with both tiers' decisions visible.
     */
    public void analysisEscalated(double disputedProbability, AnalysisResult disputed, AnalysisResult escalated) {
        store.publish("MODEL_ESCALATION", "Escalated for a second opinion",
                String.format("%s said %s (%d/100), quality check disputed at %d/100 — %s re-analyzed: %s (%d/100)",
                        analysisModelLabel(), disputed.promote() ? "continue" : "rollback", disputed.confidence(),
                        (int) Math.round(disputedProbability * 100),
                        secondOpinionModelLabel(), escalated.promote() ? "continue" : "rollback", escalated.confidence()));
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
     * confidence (the self-reported value, lowered only by a clearly
     * disputed check) and its inputs.
     */
    public void analysisDecided(AnalysisResult result, int effectiveConfidence, Double qualityProbability,
                                boolean gateTriggered) {
        String decision = result.promote() ? "Continue rollout" : "Rollback canary";
        String combination = qualityProbability != null
                ? String.format(" (analysis %d/100, quality check %d/100)",
                        result.confidence(), (int) Math.round(qualityProbability * 100))
                : "";
        String detail;
        if (gateTriggered) {
            detail = String.format("effective confidence %d/100%s — held for human review",
                    effectiveConfidence, combination);
        } else if (result.promote()) {
            detail = String.format("effective confidence %d/100%s — canary proceeds",
                    effectiveConfidence, combination);
        } else {
            detail = String.format("effective confidence %d/100%s — canary will be rolled back",
                    effectiveConfidence, combination);
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
