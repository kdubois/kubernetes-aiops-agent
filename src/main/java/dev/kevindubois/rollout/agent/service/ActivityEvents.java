package dev.kevindubois.rollout.agent.service;

import dev.kevindubois.rollout.agent.model.ActivityEventStore;
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

    public void remediationTriggered() {
        store.publish("REMEDIATION", "Remediation triggered", "Analyzing root cause for automated fix");
    }

    public void remediationFailed(String reason) {
        store.publish("REMEDIATION", "Failed", reason);
    }

    public void remediationCompleted(String artifactUrl) {
        store.publish("REMEDIATION", "GitHub artifact created", artifactUrl);
    }
}
