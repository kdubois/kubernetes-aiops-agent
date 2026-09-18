package dev.kevindubois.rollout.agent.k8s;

import java.util.Map;

/**
 * Typed result from K8sTools diagnostics and metrics queries.
 * Either carries an error message, or stable/canary pod data maps.
 */
public record PodDataResult(String error, Map<String, Object> stable, Map<String, Object> canary) {

    public static PodDataResult error(String message) {
        return new PodDataResult(message, null, null);
    }

    public static PodDataResult of(Map<String, Object> stable, Map<String, Object> canary) {
        return new PodDataResult(null, stable, canary);
    }

    public boolean hasError() {
        return error != null;
    }
}
