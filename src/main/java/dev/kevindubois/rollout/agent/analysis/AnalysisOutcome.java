package dev.kevindubois.rollout.agent.analysis;

/**
 * Result of the analysis pipeline including orchestration effects that happen
 * after the workflow itself, such as a rollout pause requested by the
 * uncertainty gate.
 *
 * @param result        the analysis result from the workflow
 * @param pausedRollout the rollout name that was paused for human review, or null
 */
public record AnalysisOutcome(AnalysisResult result, String pausedRollout) {

    public static AnalysisOutcome of(AnalysisResult result) {
        return new AnalysisOutcome(result, null);
    }
}
