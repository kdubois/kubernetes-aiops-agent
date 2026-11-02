package dev.kevindubois.rollout.agent.a2a;

import java.util.Objects;

/**
 * Immutable response for Kubernetes Agent analysis results.
 *
 * @param analysis      Full analysis text from the agent
 * @param rootCause     Identified root cause of the issue
 * @param remediation   Recommended remediation steps
 * @param promote       Whether to promote the canary deployment
 * @param confidence    Confidence score between 0 and 100 (inclusive)
 * @param pausedRollout Rollout paused by the uncertainty gate, if any (nullable)
 * @param error         Short error message when the analysis failed, null on success
 */
public record KubernetesAgentResponse(
    String analysis,
    String rootCause,
    String remediation,
    boolean promote,
    int confidence,
    String pausedRollout,
    String error
) {
    /**
     * Compact canonical constructor with validation.
     *
     * @throws IllegalArgumentException if confidence is not in range [0, 100]
     * @throws NullPointerException if analysis, rootCause, or remediation is null
     */
    public KubernetesAgentResponse {
        Objects.requireNonNull(analysis, "analysis cannot be null");
        Objects.requireNonNull(rootCause, "rootCause cannot be null");
        Objects.requireNonNull(remediation, "remediation cannot be null");

        if (confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException(
                "confidence must be between 0 and 100, got: " + confidence
            );
        }

        analysis = analysis.trim();
        rootCause = rootCause.trim();
        remediation = remediation.trim();
        if (pausedRollout != null) {
            pausedRollout = pausedRollout.trim();
            if (pausedRollout.isEmpty()) {
                pausedRollout = null;
            }
        }
        if (error != null) {
            error = error.trim();
            if (error.isEmpty()) {
                error = null;
            }
        }
    }
}
