package dev.kevindubois.rollout.agent.analysis;

import dev.kevindubois.rollout.agent.analysis.AnalysisResult;
import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.kevindubois.rollout.agent.observability.ActivityEvents;
import dev.kevindubois.rollout.agent.remediation.RemediationOrchestrator;
import dev.kevindubois.rollout.agent.analysis.AnalysisOrchestratorAgent;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import io.quarkus.logging.Log;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.time.temporal.ChronoUnit;

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
    RemediationOrchestrator remediationOrchestrator;

    @Inject
    ActivityEvents activityEvents;

    @Inject
    K8sTools k8sTools;

    /**
     * Execute the full analysis pipeline: workflow + uncertainty gate + events + remediation trigger.
     *
     * @param memoryId    conversation/session ID for the agent memory
     * @param prompt      assembled prompt including context
     * @param namespace   Kubernetes namespace to analyze
     * @param rolloutName rollout to pause when the gate fires (nullable — first rollout in the namespace)
     * @param repoUrl     repository URL for remediation (nullable)
     * @param baseBranch  target branch for PRs (defaults to "main")
     * @param pauseGate   opt-in promotion pause gate (nullable — treated as disabled)
     * @return the analysis result and the rollout paused by the gate, if any
     */
    @Retry(maxRetries = 3, delay = 1, delayUnit = ChronoUnit.SECONDS,
           retryOn = RetriableException.class, abortOn = NonRetriableException.class)
    @ExponentialBackoff(factor = 2, maxDelay = 60, maxDelayUnit = ChronoUnit.SECONDS)
    public AnalysisOutcome analyze(String memoryId, String prompt, String namespace, String rolloutName,
                                   String repoUrl, String baseBranch, PauseGate pauseGate) throws Exception {
        AnalysisResult result = analysisOrchestratorAgent.execute(memoryId, prompt, namespace);

        activityEvents.analysisCompleted(result);

        String pausedRollout = pauseIfNeeded(pauseGate, result, namespace, rolloutName);

        remediationOrchestrator.triggerIfNeeded(result, prompt, repoUrl, baseBranch);

        Log.info("Analysis pipeline completed successfully");
        return new AnalysisOutcome(result, pausedRollout);
    }

    /**
     * Pauses the rollout when the gate requests it. The measurement itself stays
     * successful: the pause, not an abort, is what holds the rollout for review.
     * An already-paused rollout is not re-patched; a rollout a human unpaused is
     * paused again while the analysis is still below the threshold.
     */
    private String pauseIfNeeded(PauseGate pauseGate, AnalysisResult result, String namespace, String rolloutName) {
        if (pauseGate == null) {
            pauseGate = PauseGate.DISABLED;
        }
        if (!pauseGate.shouldPause(result)) {
            return null;
        }
        Log.infof("Uncertainty gate: confidence %d below threshold %d, pausing rollout for review",
                result.confidence(), pauseGate.threshold());
        String rollout = k8sTools.pauseRollout(namespace, rolloutName);
        if (rollout == null) {
            Log.warnf("Uncertainty gate requested a pause but no rollout was paused in namespace %s", namespace);
            activityEvents.pauseFailed("namespace=" + namespace + ", confidence=" + result.confidence());
        }
        return rollout;
    }
}
