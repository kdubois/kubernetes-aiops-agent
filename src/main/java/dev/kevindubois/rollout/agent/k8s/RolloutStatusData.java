package dev.kevindubois.rollout.agent.k8s;

/**
 * Typed Argo Rollouts resource status.
 * Either carries an error message, or the rollout's phase, current step,
 * traffic split, and stable/canary pod counts.
 */
public record RolloutStatusData(
        String error,
        String rolloutName,
        String namespace,
        String phase,
        Integer currentStepIndex,
        int canaryWeight,
        int stableWeight,
        long stablePodCount,
        long canaryPodCount) {

    public static RolloutStatusData error(String message) {
        return new RolloutStatusData(message, null, null, null, null, 0, 100, 0, 0);
    }

    public boolean hasError() {
        return error != null;
    }
}
