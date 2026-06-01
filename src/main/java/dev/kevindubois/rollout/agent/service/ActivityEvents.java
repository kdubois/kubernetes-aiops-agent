package dev.kevindubois.rollout.agent.service;

import dev.kevindubois.rollout.agent.model.ActivityEventStore;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.utils.TextUtils;
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

    public void analysisCompleted(AnalysisResult result) {
        String summary = TextUtils.extractSummary(result.analysis());
        if (summary != null) {
            store.publish("ANALYSIS_SUMMARY", "Analysis summary", summary);
        }
        store.publish("ANALYSIS_INSIGHT", TextUtils.truncate(result.analysis(), 200),
                "Root cause: " + (result.rootCause() != null ? result.rootCause() : "No issues"));
        store.publish("DECISION",
                result.promote() ? "PROMOTE recommended" : "ROLLBACK recommended",
                "confidence: " + result.confidence() + "%");
    }

    public void remediationTriggered() {
        store.publish("REMEDIATION", "Remediation triggered", "Analyzing root cause for automated fix");
    }

    public void remediationRetrying(int failedAttempt, String reason) {
        store.publish("RETRY", "Retrying remediation (attempt " + failedAttempt + " failed)", reason);
    }

    public void remediationFailed(String reason) {
        store.publish("REMEDIATION", "Failed", reason);
    }

    public void remediationCompleted(String artifactUrl) {
        store.publish("REMEDIATION", "GitHub artifact created", artifactUrl);
    }
}
