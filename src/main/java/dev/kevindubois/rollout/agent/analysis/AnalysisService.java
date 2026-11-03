package dev.kevindubois.rollout.agent.analysis;

import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.kevindubois.rollout.agent.observability.ActivityEvents;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import io.quarkus.logging.Log;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Single entry point for rollout analysis used by both the REST endpoint and A2A executor.
 * Runs the workflow, applies the uncertainty pause gate, publishes activity events,
 * and triggers remediation on rollback.
 */
@ApplicationScoped
public class AnalysisService {

    @Inject
    AnalysisOrchestratorAgent analysisOrchestratorAgent;

    @Inject
    ActivityEvents activityEvents;

    @Inject
    K8sTools k8sTools;

    @Inject
    AnalysisGrader analysisGrader;

    /**
     * Execute the full analysis pipeline: workflow + grader second opinion +
     * uncertainty gate + events + remediation trigger.
     *
     * <p>The response confidence stays the self-reported value from the
     * analysis model; the grader-combined effective confidence only drives
     * the pause gate and the activity events.
     *
     * @param prompt         assembled prompt including context, forwarded to the analysis model as operator notes
     * @param namespace      Kubernetes namespace to analyze
     * @param stableSelector label selector for stable pods (e.g. "role=stable")
     * @param canarySelector label selector for canary pods (e.g. "role=canary")
     * @param rolloutName    rollout to pause when the gate fires (nullable — first rollout in the namespace)
     * @param repoUrl        repository URL for remediation (nullable)
     * @param baseBranch     target branch for PRs (defaults to "main")
     * @param pauseGate      opt-in promotion pause gate (nullable — treated as disabled)
     * @return the analysis result and the rollout paused by the gate, if any
     */
    @Retry(maxRetries = 3, delay = 1, delayUnit = ChronoUnit.SECONDS,
           retryOn = RetriableException.class, abortOn = NonRetriableException.class)
    @ExponentialBackoff(factor = 2, maxDelay = 60, maxDelayUnit = ChronoUnit.SECONDS)
    public AnalysisOutcome analyze(String prompt, String namespace, String stableSelector, String canarySelector,
                                   String rolloutName, String repoUrl, String baseBranch, PauseGate pauseGate)
            throws Exception {
        // null scope arguments fail the sub-agents' argument resolution
        // (optional agents are not supported in this version), so pass "" instead
        ResultWithAgenticScope<AnalysisResult> workflow = analysisOrchestratorAgent.execute(
                prompt != null ? prompt : "",
                namespace,
                stableSelector != null && !stableSelector.isBlank() ? stableSelector : "role=stable",
                canarySelector != null && !canarySelector.isBlank() ? canarySelector : "role=canary",
                repoUrl != null ? repoUrl : "",
                baseBranch != null && !baseBranch.isBlank() ? baseBranch : "main");

        AnalysisResult result = workflow.result();
        String diagnosticReport = workflow.agenticScope().readState("diagnosticReport", "");
        String rolloutReport = workflow.agenticScope().readState("rolloutReport", "");
        String metricsReport = workflow.agenticScope().readState("metricsReport", "");

        int effectiveConfidence = effectiveConfidence(result, diagnosticReport, rolloutReport, metricsReport);
        activityEvents.confidenceScored(result.confidence(), effectiveConfidence, result.summary());
        activityEvents.analysisCompleted(result, effectiveConfidence);

        String pausedRollout = pauseIfNeeded(pauseGate, result, effectiveConfidence, namespace, rolloutName);

        Log.info("Analysis pipeline completed successfully");
        return new AnalysisOutcome(result, pausedRollout);
    }

    /**
     * Combines the self-reported confidence with the grader second opinion.
     * The grader is a veto with a margin, not a second confidence scale: a
     * decision-model probability is a judgment with a wide neutral band, so
     * only a clearly negative grade (below {@link #CLEARLY_UNSUPPORTED})
     * lowers the effective confidence. In the neutral band the self-reported
     * confidence stands. When the grader is unavailable (no key, call
     * failure, timeout) the self-reported confidence stands as well.
     */
    static final double CLEARLY_UNSUPPORTED = 0.30;

    private int effectiveConfidence(AnalysisResult result, String diagnosticReport, String rolloutReport,
            String metricsReport) {
        Optional<Double> probability = analysisGrader.grade(result, diagnosticReport, rolloutReport, metricsReport);
        if (probability.isEmpty()) {
            return result.confidence();
        }
        Log.infof("Analysis grader: self-reported confidence %d, grader probability %.2f",
                result.confidence(), probability.get());
        if (probability.get() >= CLEARLY_UNSUPPORTED) {
            return result.confidence();
        }
        int effective = (int) Math.round(probability.get() * 100);
        Log.infof("Grader clearly disputes the conclusion; effective confidence %d", effective);
        return effective;
    }

    /**
     * Pauses the rollout when the gate requests it. The measurement itself stays
     * successful: the pause, not an abort, is what holds the rollout for review.
     * An already-paused rollout is not re-patched; a rollout a human unpaused is
     * paused again while the analysis is still below the threshold.
     */
    private String pauseIfNeeded(PauseGate pauseGate, AnalysisResult result, int effectiveConfidence,
                                 String namespace, String rolloutName) {
        if (pauseGate == null) {
            pauseGate = PauseGate.DISABLED;
        }
        if (!pauseGate.shouldPause(result, effectiveConfidence)) {
            return null;
        }
        Log.infof("Uncertainty gate: effective confidence %d below threshold %d, pausing rollout for review",
                effectiveConfidence, pauseGate.threshold());
        String rollout = k8sTools.pauseRollout(namespace, rolloutName);
        if (rollout == null) {
            Log.warnf("Uncertainty gate requested a pause but no rollout was paused in namespace %s", namespace);
            activityEvents.pauseFailed("namespace=" + namespace + ", confidence=" + effectiveConfidence);
        }
        return rollout;
    }
}
