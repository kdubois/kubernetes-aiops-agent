package dev.kevindubois.rollout.agent.analysis;

import java.util.Map;

/**
 * Opt-in promotion pause gate. When enabled and the analysis promotes with confidence
 * below the threshold, the rollout is paused for human review instead of proceeding
 * automatically.
 *
 * @param enabled   whether the gate is active
 * @param threshold pause when the confidence is below this value (0-100)
 */
public record PauseGate(boolean enabled, int threshold) {

    public static final PauseGate DISABLED = new PauseGate(false, 50);

    /**
     * Reads the gate from a request context or A2A metadata map. Recognizes
     * "pauseOnUncertainty" (boolean) and "uncertaintyThreshold" (0-100, default 50).
     */
    public static PauseGate fromMap(Map<String, Object> map) {
        if (map == null) {
            return DISABLED;
        }
        Object enabled = map.get("pauseOnUncertainty");
        boolean on = enabled != null && Boolean.parseBoolean(enabled.toString());
        return new PauseGate(on, threshold(map.get("uncertaintyThreshold")));
    }

    private static int threshold(Object value) {
        if (value == null) {
            return 50;
        }
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(value.toString())));
        } catch (NumberFormatException e) {
            return 50;
        }
    }

    /**
     * Whether the rollout should be paused for this result. Only a promote decision
     * can pause: a rollback already aborts the rollout, so there is nothing to hold.
     *
     * @param confidence the confidence that drives the gate — the effective
     *                   confidence when a grader second opinion is available,
     *                   otherwise the self-reported one
     */
    public boolean shouldPause(AnalysisResult result, int confidence) {
        return enabled && result.promote() && confidence < threshold;
    }
}
